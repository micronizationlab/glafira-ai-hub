using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentTaskExecutorTests
{
    private static AgentTaskEnvelope Envelope(string id, string type = "research.search") =>
        new(id, "project-a", $"idempotency-{id}", type, $"payload://{id}",
            new string('a', 64), new HashSet<string> { "research" }, null);

    private static AgentRuntimeState ReadyState()
    {
        var state = new AgentRuntimeState();
        state.SetStatus(AgentServiceStatus.Ready);
        return state;
    }

    [Fact]
    public async Task ExecutesOnlyRegisteredTypedHandler()
    {
        var handler = new TestHandler("research.search", (_, _) =>
            Task.FromResult(new AgentTaskResult("task-1", "SUCCEEDED")));
        var registry = new AgentTaskHandlerRegistry([handler]);
        await using var executor = new AgentTaskExecutor(registry, ReadyState());

        var result = await executor.ExecuteAsync(Envelope("task-1"), CancellationToken.None);

        Assert.Equal("SUCCEEDED", result.Outcome);
        Assert.Equal(1, handler.Calls);
    }

    [Fact]
    public async Task RejectsUnknownTaskTypeWithoutCallingHandler()
    {
        await using var executor = new AgentTaskExecutor(
            new AgentTaskHandlerRegistry([]), ReadyState());

        var result = await executor.ExecuteAsync(Envelope("task-2", "process.shell"), CancellationToken.None);

        Assert.Equal("NO_AGENT_HANDLER", result.ErrorCode);
    }

    [Fact]
    public async Task RejectsExecutionUntilOwnerEnrollmentMakesAgentReady()
    {
        var state = new AgentRuntimeState();
        var handler = new TestHandler("research.search", (_, _) =>
            Task.FromResult(new AgentTaskResult("task-3", "SUCCEEDED")));
        await using var executor = new AgentTaskExecutor(
            new AgentTaskHandlerRegistry([handler]), state);

        var result = await executor.ExecuteAsync(Envelope("task-3"), CancellationToken.None);

        Assert.Equal("AGENT_NOT_READY", result.ErrorCode);
        Assert.Equal(0, handler.Calls);
    }

    [Fact]
    public async Task EnforcesConfiguredParallelCapacity()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var active = 0;
        var peak = 0;
        var handler = new TestHandler("research.search", async (task, token) =>
        {
            var count = Interlocked.Increment(ref active);
            UpdateMaximum(ref peak, count);
            entered.TrySetResult();
            await release.Task.WaitAsync(token);
            Interlocked.Decrement(ref active);
            return new AgentTaskResult(task.TaskId, "SUCCEEDED");
        });
        await using var executor = new AgentTaskExecutor(
            new AgentTaskHandlerRegistry([handler]), ReadyState(), maxParallelTasks: 2);

        var first = executor.ExecuteAsync(Envelope("task-a"), CancellationToken.None);
        var second = executor.ExecuteAsync(Envelope("task-b"), CancellationToken.None);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(2));
        await WaitUntilAsync(() => executor.ActiveTaskCount == 2);
        Assert.Equal(2, executor.ActiveTaskCount);
        Assert.InRange(peak, 1, 2);

        release.TrySetResult();
        var results = await Task.WhenAll(first, second);
        Assert.All(results, result => Assert.Equal("SUCCEEDED", result.Outcome));
        Assert.Equal(0, executor.ActiveTaskCount);
    }

    [Fact]
    public async Task DoesNotExposeHandlerExceptionDetails()
    {
        var handler = new TestHandler("research.search", (_, _) =>
            throw new InvalidOperationException("private path and secret must not escape"));
        await using var executor = new AgentTaskExecutor(
            new AgentTaskHandlerRegistry([handler]), ReadyState());

        var result = await executor.ExecuteAsync(Envelope("task-failure"), CancellationToken.None);

        Assert.Equal("HANDLER_FAILURE", result.ErrorCode);
        Assert.DoesNotContain("secret", result.ErrorCode);
    }

    private static void UpdateMaximum(ref int target, int value)
    {
        int current;
        do
        {
            current = Volatile.Read(ref target);
            if (current >= value) return;
        } while (Interlocked.CompareExchange(ref target, value, current) != current);
    }

    private static async Task WaitUntilAsync(Func<bool> predicate)
    {
        var deadline = DateTime.UtcNow.AddSeconds(2);
        while (!predicate() && DateTime.UtcNow < deadline)
            await Task.Delay(10);
        Assert.True(predicate());
    }

    private sealed class TestHandler(
        string taskType,
        Func<AgentTaskEnvelope, CancellationToken, Task<AgentTaskResult>> execute) : IAgentTaskHandler
    {
        private int _calls;
        public string TaskType { get; } = taskType;
        public int Calls => Volatile.Read(ref _calls);

        public Task<AgentTaskResult> ExecuteAsync(
            AgentTaskEnvelope task, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref _calls);
            return execute(task, cancellationToken);
        }
    }
}
