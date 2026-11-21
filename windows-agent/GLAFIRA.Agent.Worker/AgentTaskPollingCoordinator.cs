using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Coordinates one safe, typed Agent execution at a time. The active lease is journaled
/// before execution; terminal output is journaled before transmission and retained until
/// the Hub returns the exact lease/fencing ACK. Only ReadOnly handlers are executable.
/// </summary>
public sealed class AgentTaskPollingCoordinator
{
    private readonly AgentRuntimeState _runtimeState;
    private readonly AgentTaskHandlerRegistry _handlers;
    private readonly AgentTaskExecutor _executor;
    private readonly IAgentTaskTransport _transport;
    private readonly IAgentExecutionJournal _journal;
    private readonly TimeProvider _timeProvider;
    private readonly TimeSpan _renewInterval;

    public AgentTaskPollingCoordinator(
        AgentRuntimeState runtimeState,
        AgentTaskHandlerRegistry handlers,
        AgentTaskExecutor executor,
        IAgentTaskTransport transport,
        IAgentExecutionJournal journal,
        TimeProvider? timeProvider = null,
        TimeSpan? renewInterval = null)
    {
        _runtimeState = runtimeState ?? throw new ArgumentNullException(nameof(runtimeState));
        _handlers = handlers ?? throw new ArgumentNullException(nameof(handlers));
        _executor = executor ?? throw new ArgumentNullException(nameof(executor));
        _transport = transport ?? throw new ArgumentNullException(nameof(transport));
        _journal = journal ?? throw new ArgumentNullException(nameof(journal));
        _timeProvider = timeProvider ?? TimeProvider.System;
        _renewInterval = renewInterval ?? TimeSpan.FromSeconds(30);
        if (_renewInterval < TimeSpan.FromSeconds(5) || _renewInterval > TimeSpan.FromMinutes(1))
            throw new ArgumentOutOfRangeException(nameof(renewInterval));
    }

    public async Task<bool> PollOnceAsync(
        AgentEnrollmentCredential credential,
        CancellationToken cancellationToken)
    {
        if (_runtimeState.Status != AgentServiceStatus.Ready ||
            _handlers.RegisteredTaskTypes.Count == 0)
            return false;

        await FlushPendingResultsAsync(credential, cancellationToken).ConfigureAwait(false);
        var snapshot = await _journal.LoadAsync(cancellationToken).ConfigureAwait(false);
        if (snapshot.ActiveLeases.Count > 0)
        {
            var active = snapshot.ActiveLeases.OrderBy(item => item.LeaseExpiresAtEpochMs).First();
            var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
            if (active.LeaseExpiresAtEpochMs <= now)
            {
                await _journal.ClearExpiredActiveLeaseAsync(
                    active.TaskId, active.LeaseId, active.FencingToken, now, cancellationToken).ConfigureAwait(false);
                return false;
            }
            // A process restart may leave an active lease without a terminal result.
            // Re-running is permitted only for ReadOnly tasks; the executor fails closed otherwise.
            await ExecuteLeaseAsync(credential, active, cancellationToken).ConfigureAwait(false);
            return true;
        }

        var lease = await _transport.ClaimNextAsync(
            credential,
            _runtimeState.Capabilities,
            1, // First runtime slice is deliberately single-flight; scale only after outbox stress tests.
            _handlers.RegisteredTaskTypes,
            cancellationToken).ConfigureAwait(false);
        if (lease is null) return false;

        await _journal.SaveActiveLeaseAsync(lease, cancellationToken).ConfigureAwait(false);
        await ExecuteLeaseAsync(credential, lease, cancellationToken).ConfigureAwait(false);
        return true;
    }

    private async Task ExecuteLeaseAsync(
        AgentEnrollmentCredential credential,
        AgentTaskLease lease,
        CancellationToken cancellationToken)
    {
        var leaseState = new ActiveLeaseState(lease);
        var initialAck = await _transport.SendTaskUpdateAsync(
            credential, leaseState.Current, "RUNNING", 0, "Agent started registered handler",
            cancellationToken: cancellationToken).ConfigureAwait(false);
        UpdateLeaseExpiry(leaseState, initialAck);
        await _journal.SaveActiveLeaseAsync(leaseState.Current, cancellationToken).ConfigureAwait(false);

        using var renewalCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var renewalTask = RenewWhileRunningAsync(credential, leaseState, renewalCancellation);
        AgentTaskResult result;
        Exception? renewalFailure = null;
        try
        {
            result = await _executor.ExecuteAsync(leaseState.Current.ToEnvelope(), renewalCancellation.Token).ConfigureAwait(false);
        }
        finally
        {
            renewalCancellation.Cancel();
            try { await renewalTask.ConfigureAwait(false); }
            catch (OperationCanceledException) when (renewalCancellation.IsCancellationRequested) { }
            catch (Exception error) { renewalFailure = error; }
        }

        if (renewalFailure is not null || result.Outcome == "CANCELLED")
            result = new AgentTaskResult(lease.TaskId, "FAILED", ErrorCode: "LEASE_RENEWAL_OR_EXECUTION_CANCELLED");

        var resultText = JsonSerializer.Serialize(result);
        var resultJson = JsonSerializer.Serialize(resultText);
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
        var state = result.Outcome == "SUCCEEDED" ? "SUCCEEDED" : "FAILED";
        var pending = new AgentPendingResult(
            leaseState.Current, state, 100, "Registered handler finished", resultJson,
            result.ErrorCode ?? "none", digest);
        await _journal.PersistCompletedResultAsync(pending, cancellationToken).ConfigureAwait(false);
        await FlushPendingResultsAsync(credential, cancellationToken).ConfigureAwait(false);
    }

    private async Task RenewWhileRunningAsync(
        AgentEnrollmentCredential credential,
        ActiveLeaseState leaseState,
        CancellationTokenSource executionCancellation)
    {
        var cancellationToken = executionCancellation.Token;
        while (true)
        {
            await Task.Delay(_renewInterval, _timeProvider, cancellationToken).ConfigureAwait(false);
            try
            {
                var ack = await _transport.SendTaskUpdateAsync(
                    credential, leaseState.Current, "RUNNING", 50, "Registered handler is still running",
                    cancellationToken: cancellationToken).ConfigureAwait(false);
                UpdateLeaseExpiry(leaseState, ack);
                await _journal.SaveActiveLeaseAsync(leaseState.Current, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // If the Hub cannot extend the lease, stop local work before its authority expires.
                executionCancellation.Cancel();
                throw;
            }
        }
    }

    private void UpdateLeaseExpiry(ActiveLeaseState leaseState, AgentTaskUpdateAck ack)
    {
        if (ack.TaskId != leaseState.Current.TaskId || ack.LeaseId != leaseState.Current.LeaseId ||
            ack.FencingToken != leaseState.Current.FencingToken || ack.State != "RUNNING" ||
            ack.LeaseExpiresAtEpochMs <= _timeProvider.GetUtcNow().ToUnixTimeMilliseconds())
            throw new InvalidDataException("TASK_LEASE_RENEWAL_ACK_INVALID");
        leaseState.Update(leaseState.Current with { LeaseExpiresAtEpochMs = ack.LeaseExpiresAtEpochMs });
    }

    private sealed class ActiveLeaseState(AgentTaskLease initialLease)
    {
        private AgentTaskLease _current = initialLease;
        public AgentTaskLease Current => Volatile.Read(ref _current);
        public void Update(AgentTaskLease lease) => Interlocked.Exchange(ref _current, lease);
    }

    private async Task FlushPendingResultsAsync(
        AgentEnrollmentCredential credential,
        CancellationToken cancellationToken)
    {
        var snapshot = await _journal.LoadAsync(cancellationToken).ConfigureAwait(false);
        foreach (var pending in snapshot.PendingResults.OrderBy(item => item.Lease.LeaseExpiresAtEpochMs))
        {
            AgentTaskUpdateAck ack;
            try
            {
                ack = await _transport.SendTaskUpdateAsync(
                    credential, pending.Lease, pending.State, pending.ProgressPercent,
                    pending.ProgressMessage, pending.ResultJson, pending.ErrorCode,
                    cancellationToken).ConfigureAwait(false);
            }
            catch (InvalidDataException error) when (error.Message == "LEASE_FENCING_REJECTED")
            {
                // The Hub has explicitly rejected this stale lease. It is safe to discard
                // this read-only result; the server owns requeue/reconciliation from here.
                await _journal.DiscardStaleResultAsync(
                    pending.Lease.TaskId, pending.Lease.LeaseId, pending.Lease.FencingToken,
                    cancellationToken).ConfigureAwait(false);
                continue;
            }
            if (ack.TaskId != pending.Lease.TaskId || ack.LeaseId != pending.Lease.LeaseId ||
                ack.FencingToken != pending.Lease.FencingToken ||
                ack.State != pending.State || ack.ResultSha256 != pending.ResultSha256)
                throw new InvalidDataException("AGENT_RESULT_ACK_IDENTITY_MISMATCH");
            await _journal.AcknowledgeResultAsync(
                pending.Lease.TaskId, pending.Lease.LeaseId, pending.Lease.FencingToken,
                cancellationToken).ConfigureAwait(false);
        }
    }
}
