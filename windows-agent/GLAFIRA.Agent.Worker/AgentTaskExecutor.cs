using System.Collections.Concurrent;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Executes only an already-authenticated, owner-authorized lease. The Desktop host
/// remains responsible for enrollment, signature verification, scope checks, replay
/// protection and lease fencing before calling this local execution boundary.
/// </summary>
public sealed class AgentTaskExecutor : IAsyncDisposable
{
    private readonly AgentTaskHandlerRegistry _handlers;
    private readonly AgentRuntimeState _runtimeState;
    private readonly SemaphoreSlim _capacity;
    private readonly ConcurrentDictionary<string, byte> _active = new(StringComparer.Ordinal);
    private int _disposed;

    public AgentTaskExecutor(
        AgentTaskHandlerRegistry handlers,
        AgentRuntimeState runtimeState,
        int maxParallelTasks = 1)
    {
        ArgumentNullException.ThrowIfNull(handlers);
        ArgumentNullException.ThrowIfNull(runtimeState);
        if (maxParallelTasks is < 1 or > 16)
            throw new ArgumentOutOfRangeException(nameof(maxParallelTasks));

        _handlers = handlers;
        _runtimeState = runtimeState;
        _capacity = new SemaphoreSlim(maxParallelTasks, maxParallelTasks);
    }

    public int ActiveTaskCount => _active.Count;

    public async Task<AgentTaskResult> ExecuteAsync(
        AgentTaskEnvelope task,
        CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        ArgumentNullException.ThrowIfNull(task);

        if (!IsValidEnvelope(task))
            return Failed(task.TaskId, "INVALID_TASK_ENVELOPE");
        if (_runtimeState.Status != AgentServiceStatus.Ready)
            return Failed(task.TaskId, "AGENT_NOT_READY");
        if (!_handlers.TryResolve(task.TaskType, out var handler) || handler is null)
            return Failed(task.TaskId, "NO_AGENT_HANDLER");
        if (!_active.TryAdd(task.TaskId, 0))
            return Failed(task.TaskId, "DUPLICATE_ACTIVE_TASK");

        var acquired = false;
        try
        {
            await _capacity.WaitAsync(cancellationToken).ConfigureAwait(false);
            acquired = true;

            // Pause, drain, revocation or service shutdown can occur while waiting.
            if (_runtimeState.Status != AgentServiceStatus.Ready)
                return Failed(task.TaskId, "AGENT_NOT_READY");

            var result = await handler.ExecuteAsync(task, cancellationToken).ConfigureAwait(false);
            if (!StringComparer.Ordinal.Equals(result.TaskId, task.TaskId))
                return Failed(task.TaskId, "HANDLER_RESULT_TASK_MISMATCH");
            if (result.Outcome is not ("SUCCEEDED" or "FAILED" or "CANCELLED"))
                return Failed(task.TaskId, "HANDLER_RESULT_INVALID");
            return result;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return new AgentTaskResult(task.TaskId, "CANCELLED", ErrorCode: "TASK_CANCELLED");
        }
        catch (Exception)
        {
            // Do not leak exception text, file paths, credentials or provider output.
            return Failed(task.TaskId, "HANDLER_FAILURE");
        }
        finally
        {
            if (acquired)
                _capacity.Release();
            _active.TryRemove(task.TaskId, out _);
        }
    }

    private static bool IsValidEnvelope(AgentTaskEnvelope task) =>
        !string.IsNullOrWhiteSpace(task.TaskId) &&
        task.TaskId.Length <= 128 &&
        !string.IsNullOrWhiteSpace(task.ProjectId) &&
        task.ProjectId.Length <= 128 &&
        !string.IsNullOrWhiteSpace(task.IdempotencyKey) &&
        task.IdempotencyKey.Length <= 128 &&
        !string.IsNullOrWhiteSpace(task.TaskType) &&
        task.TaskType.Length <= 96 &&
        !string.IsNullOrWhiteSpace(task.PayloadRef) &&
        task.PayloadRef.Length <= 512 &&
        System.Text.RegularExpressions.Regex.IsMatch(
            task.PayloadSha256, "^[a-fA-F0-9]{64}$") &&
        task.RequiredCapabilities is not null &&
        task.RequiredCapabilities.Count <= 64;

    private static AgentTaskResult Failed(string taskId, string code) =>
        new(taskId, "FAILED", ErrorCode: code);

    public ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
            _capacity.Dispose();
        return ValueTask.CompletedTask;
    }
}
