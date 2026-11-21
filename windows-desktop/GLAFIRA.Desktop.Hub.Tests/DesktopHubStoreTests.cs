using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Core.Tests;

public sealed class DesktopHubStoreTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-hub-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly DesktopHubStore _store;

    public DesktopHubStoreTests() => _store = new DesktopHubStore(_databasePath);

    [Fact]
    public void UnapprovedAgentCannotClaimUntilOwnerApprovesPairing()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, false), now);
        _store.Enqueue(CreateTask("task-1"), now);

        Assert.Null(_store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1)));

        Assert.True(_store.SetAgentOwnerApproval("agent-1", true));
        var lease = _store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);
        Assert.Equal("task-1", lease.TaskId);
    }

    [Fact]
    public void CapabilityMismatchDoesNotClaimTask()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("task-1", required: ["coding"]), now);

        Assert.Null(_store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1)));
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("task-1"));
    }

    [Fact]
    public void CompatibleTaskBeyondFirst128CandidatesIsClaimed()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, true), now);
        for (var index = 0; index < 128; index++)
            _store.Enqueue(CreateTask($"unsupported-{index}", required: ["coding"]), now.AddMilliseconds(index));
        _store.Enqueue(CreateTask("eligible-129"), now.AddMilliseconds(129));

        var lease = _store.ClaimNext("agent-1", now.AddSeconds(1), TimeSpan.FromMinutes(1));

        Assert.NotNull(lease);
        Assert.Equal("eligible-129", lease.TaskId);
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("unsupported-0"));
    }

    [Fact]
    public void SupportedTaskTypeFilterLeavesUnregisteredTaskQueued()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("task-1"), now);

        Assert.Null(_store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1), ["analysis.document"]));
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("task-1"));
    }

    [Fact]
    public void EnforcesPerAgentConcurrencyLimit()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("task-1"), now);
        _store.Enqueue(CreateTask("task-2"), now);

        var first = _store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1));

        Assert.NotNull(first);
        Assert.Null(_store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1)));
    }

    [Fact]
    public void DependencyBlocksChildUntilPredecessorSucceeded()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 2, true), now);
        _store.Enqueue(CreateTask("parent"), now);
        _store.Enqueue(CreateTask("child", dependencies: ["parent"]), now);

        var parent = _store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1));
        Assert.NotNull(parent);
        Assert.Null(_store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1)));

        Assert.True(_store.Complete("agent-1", parent.TaskId, parent.LeaseId,
            parent.FencingToken, succeeded: true, now.AddSeconds(1)));

        var child = _store.ClaimNext("agent-1", now.AddSeconds(2), TimeSpan.FromMinutes(1));
        Assert.NotNull(child);
        Assert.Equal("child", child.TaskId);
    }

    [Fact]
    public void QueuedOwnershipTransferUsesGenerationCasAndBindsLeaseHistory()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration(
            "agent-1", "device-1", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("owner-transfer"), now);

        Assert.False(_store.TransferQueuedTaskOwnership(
            "owner-transfer", "windows-core-0001", 2,
            "android-core-0001", 3, now.AddMilliseconds(1)));
        Assert.True(_store.TransferQueuedTaskOwnership(
            "owner-transfer", "windows-core-0001", 1,
            "android-core-0001", 2, now.AddMilliseconds(2)));
        Assert.False(_store.MatchesTaskOwnership("owner-transfer", "windows-core-0001", 1));
        Assert.True(_store.MatchesTaskOwnership("owner-transfer", "android-core-0001", 2));
        Assert.False(_store.TransferQueuedTaskOwnership(
            "owner-transfer", "windows-core-0001", 1,
            "ios-core-0001", 2, now.AddMilliseconds(3)));

        var task = Assert.Single(_store.GetTasks(), item => item.TaskId == "owner-transfer");
        Assert.Equal("android-core-0001", task.OwnerCoreId);
        Assert.Equal(2, task.OwnerGeneration);

        var lease = Assert.IsType<HubTaskLease>(
            _store.ClaimNext("agent-1", now.AddSeconds(1), TimeSpan.FromMinutes(1)));
        Assert.Equal("android-core-0001", lease.OwnerCoreId);
        Assert.Equal(2, lease.OwnerGeneration);
        Assert.False(_store.TransferQueuedTaskOwnership(
            "owner-transfer", "android-core-0001", 2,
            "ios-core-0001", 3, now.AddSeconds(2)));

        var history = _store.GetTaskHistory("owner-transfer");
        var transfer = Assert.Single(history, item => item.EventCode == "TASK_OWNER_TRANSFERRED");
        var leased = Assert.Single(history, item => item.EventCode == "TASK_LEASED");
        Assert.Equal("android-core-0001", transfer.OwnerCoreId);
        Assert.Equal(2, transfer.OwnerGeneration);
        Assert.Equal(transfer.OwnerCoreId, leased.OwnerCoreId);
        Assert.Equal(transfer.OwnerGeneration, leased.OwnerGeneration);
    }

    [Fact]
    public void ExpiredReadOnlyLeaseReturnsToQueueButExternalSideEffectIsQuarantined()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 2, true), now);
        _store.Enqueue(CreateTask("read-only"), now);
        _store.Enqueue(CreateTask("external", risk: HubTaskEffectRisk.ExternalSideEffect, approval: "owner-approval-1"), now);

        var first = _store.ClaimNext("agent-1", now, TimeSpan.FromSeconds(1));
        Assert.NotNull(first);
        var second = _store.ClaimNext("agent-1", now, TimeSpan.FromSeconds(1));
        Assert.NotNull(second);

        var recovered = _store.RecoverExpiredLeases(now.AddSeconds(2));

        Assert.Equal(2, recovered.Count);
        Assert.Contains(recovered, item => item.TaskId == "read-only" && item.State == HubTaskState.Queued);
        Assert.Contains(recovered, item => item.TaskId == "external" && item.State == HubTaskState.OutcomeUnknown);
    }


    [Fact]
    public async Task ConcurrentAgentsCannotReceiveTheSameQueuedTask()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, true), now);
        _store.RegisterAgent(new HubAgentRegistration("agent-2", "device-2", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("single-task"), now);

        var claims = await Task.WhenAll(
            System.Threading.Tasks.Task.Run(() => _store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1))),
            System.Threading.Tasks.Task.Run(() => _store.ClaimNext("agent-2", now, TimeSpan.FromMinutes(1))));

        Assert.Single(claims, lease => lease is not null);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("single-task"));
    }

    [Fact]
    public void RejectsExternalSideEffectWithoutOwnerApprovalReference()
    {
        var now = DateTimeOffset.UtcNow;
        Assert.Throws<InvalidOperationException>(() =>
            _store.Enqueue(CreateTask("external", risk: HubTaskEffectRisk.ExternalSideEffect), now));
    }

    [Fact]
    public void ExactDuplicateCompletionIsAcknowledgedAfterStoreRestartButConflictIsRejected()
    {
        var now = DateTimeOffset.UtcNow;
        const string agentId = "agent-duplicate-ack";
        const string taskId = "duplicate-ack-task";
        const string acceptedResult = "durable accepted result";
        _store.RegisterAgent(new HubAgentRegistration(agentId, "device-duplicate-ack", ["research"], 1, true), now);
        _store.Enqueue(CreateTask(taskId), now);

        var lease = _store.ClaimNext(agentId, now.AddSeconds(1), TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);
        Assert.True(_store.Complete(
            agentId, taskId, lease.LeaseId, lease.FencingToken,
            succeeded: true, now.AddSeconds(2), result: acceptedResult));

        // Simulate a lost response to the Agent: the durable commit succeeded, but
        // the sender did not observe its ACK before the Hub process restarted.
        var reopened = new DesktopHubStore(_databasePath);
        Assert.True(reopened.Complete(
            agentId, taskId, lease.LeaseId, lease.FencingToken,
            succeeded: true, now.AddMinutes(2), result: acceptedResult));

        // The same lease identity with a different terminal outcome or payload is
        // not an idempotent retry and must never replace the canonical result.
        Assert.False(reopened.Complete(
            agentId, taskId, lease.LeaseId, lease.FencingToken,
            succeeded: false, now.AddMinutes(2), result: acceptedResult));
        Assert.False(reopened.Complete(
            agentId, taskId, lease.LeaseId, lease.FencingToken,
            succeeded: true, now.AddMinutes(2), result: "conflicting result"));

        Assert.Equal(HubTaskState.Succeeded, reopened.GetTaskState(taskId));
        Assert.Equal(acceptedResult, reopened.GetTaskResult(taskId)?.Result);
        Assert.Null(reopened.GetTaskResult(taskId)?.ErrorCode);
        Assert.Single(reopened.GetTaskHistory(taskId), item => item.EventCode == "TASK_SUCCEEDED");
    }

    [Fact]
    public void StaleFencingTokenCannotCompleteReissuedLease()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("task-1"), now);

        var first = _store.ClaimNext("agent-1", now, TimeSpan.FromSeconds(1));
        Assert.NotNull(first);
        _store.RecoverExpiredLeases(now.AddSeconds(2));
        var second = _store.ClaimNext("agent-1", now.AddSeconds(3), TimeSpan.FromMinutes(1));
        Assert.NotNull(second);
        Assert.True(second.FencingToken > first.FencingToken);

        Assert.False(_store.Complete("agent-1", "task-1", first.LeaseId,
            first.FencingToken, succeeded: true, now.AddSeconds(4)));
        Assert.True(_store.Complete("agent-1", "task-1", second.LeaseId,
            second.FencingToken, succeeded: true, now.AddSeconds(4)));
    }

    [Fact]
    public void DashboardQueriesExposeAgentAndTaskStateWithoutPayloadOrSecrets()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-dashboard", "device-dashboard",
            ["research", "summarize"], 2, true), now);
        _store.Enqueue(CreateTask("dashboard-task"), now);

        var agent = Assert.Single(_store.GetAgents(now));
        Assert.Equal("agent-dashboard", agent.AgentId);
        Assert.True(agent.OwnerApproved);
        Assert.True(agent.IsOnline);
        Assert.Equal(2, agent.MaxParallelTasks);

        var task = Assert.Single(_store.GetTasks());
        Assert.Equal("dashboard-task", task.TaskId);
        Assert.Equal(HubTaskState.Queued, task.State);
        Assert.Equal(HubTaskEffectRisk.ReadOnly, task.EffectRisk);
    }

    [Fact]
    public void ProgressUpdatesRequireTheCurrentApprovedLeaseAndAreRecorded()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-progress", "device-progress", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("progress-task"), now);
        var lease = _store.ClaimNext("agent-progress", now.AddSeconds(1), TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);

        Assert.True(_store.ReportProgress("agent-progress", "progress-task", lease.LeaseId,
            lease.FencingToken, 45, "Downloaded 45 percent", now.AddSeconds(2)));
        Assert.False(_store.ReportProgress("agent-progress", "progress-task", lease.LeaseId,
            lease.FencingToken + 1, 46, "Wrong fencing token", now.AddSeconds(3)));
        Assert.False(_store.ReportProgress("other-agent", "progress-task", lease.LeaseId,
            lease.FencingToken, 47, "Wrong agent", now.AddSeconds(3)));

        var progress = Assert.Single(_store.GetTaskHistory("progress-task"),
            item => item.EventCode == "TASK_PROGRESS");
        Assert.Equal(45, progress.ProgressPercent);
        Assert.Equal("Downloaded 45 percent", progress.ProgressMessage);
        Assert.Equal("agent-progress", progress.AgentId);
        Assert.Equal(lease.LeaseId, progress.LeaseId);
        Assert.Equal(lease.FencingToken, progress.FencingToken);

        Assert.True(_store.Complete("agent-progress", "progress-task", lease.LeaseId,
            lease.FencingToken, succeeded: true, now.AddSeconds(4)));
        Assert.False(_store.ReportProgress("agent-progress", "progress-task", lease.LeaseId,
            lease.FencingToken, 90, "Late progress", now.AddSeconds(5)));
    }

    [Fact]
    public void ProgressJournalFailureRollsBackLeaseRenewalAndProgressAck()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration(
            "agent-progress-fault", "device-progress-fault", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("progress-fault-task"), now);
        var lease = _store.ClaimNext(
            "agent-progress-fault", now.AddSeconds(1), TimeSpan.FromSeconds(30));
        Assert.NotNull(lease);

        using (var connection = new SqliteConnection($"Data Source={_databasePath};Foreign Keys=True;"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = """
                CREATE TRIGGER inject_progress_event_failure
                BEFORE INSERT ON TaskEvents
                WHEN NEW.event_code = 'TASK_PROGRESS'
                BEGIN
                    SELECT RAISE(ABORT, 'injected-progress-ack-failure');
                END;
                """;
            command.ExecuteNonQuery();
        }

        var failure = Assert.Throws<SqliteException>(() => _store.ReportProgress(
            "agent-progress-fault",
            "progress-fault-task",
            lease!.LeaseId,
            lease.FencingToken,
            60,
            "progress must roll back",
            now.AddSeconds(20),
            TimeSpan.FromMinutes(2)));
        Assert.Contains("injected-progress-ack-failure", failure.Message);

        Assert.DoesNotContain(
            _store.GetTaskHistory("progress-fault-task"),
            item => item.EventCode == "TASK_PROGRESS");
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("progress-fault-task"));

        // The original 30-second lease must still expire at T+31s. If the renewal
        // escaped the failed transaction, this recovery would find nothing.
        var recovered = _store.RecoverExpiredLeases(now.AddSeconds(40));
        var item = Assert.Single(recovered);
        Assert.Equal("progress-fault-task", item.TaskId);
        Assert.Equal(HubTaskState.Queued, item.State);
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("progress-fault-task"));
        Assert.DoesNotContain(
            _store.GetTaskHistory("progress-fault-task"),
            history => history.EventCode == "TASK_PROGRESS");

        using (var connection = new SqliteConnection($"Data Source={_databasePath};Foreign Keys=True;"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "DROP TRIGGER inject_progress_event_failure;";
            command.ExecuteNonQuery();
        }

        var reopened = new DesktopHubStore(_databasePath);
        Assert.Equal(HubTaskState.Queued, reopened.GetTaskState("progress-fault-task"));
        Assert.DoesNotContain(
            reopened.GetTaskHistory("progress-fault-task"),
            history => history.EventCode == "TASK_PROGRESS");
    }

    [Fact]
    public void AcceptedProgressRenewsLeaseAndKeepsItOutOfExpiredRecovery()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-renew", "device-renew", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("renew-task"), now);
        var lease = _store.ClaimNext("agent-renew", now.AddSeconds(1), TimeSpan.FromSeconds(30));
        Assert.NotNull(lease);

        Assert.True(_store.ReportProgress("agent-renew", "renew-task", lease.LeaseId,
            lease.FencingToken, 50, "still working", now.AddSeconds(20), TimeSpan.FromMinutes(2)));

        var recovered = _store.RecoverExpiredLeases(now.AddSeconds(40));
        Assert.Empty(recovered);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("renew-task"));

        Assert.Single(_store.RecoverExpiredLeases(now.AddMinutes(3)));
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("renew-task"));
    }

    [Fact]
    public void ProgressHistoryColumnsAreAddedToAnExistingTaskEventJournal()
    {
        var now = DateTimeOffset.UtcNow;
        _store.Enqueue(CreateTask("progress-migration"), now);
        using (var connection = new SqliteConnection($"Data Source={_databasePath};Foreign Keys=True;"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "ALTER TABLE TaskEvents DROP COLUMN progress_percent; ALTER TABLE TaskEvents DROP COLUMN progress_message;";
            command.ExecuteNonQuery();
        }

        var migrated = new DesktopHubStore(_databasePath);
        var columns = new HashSet<string>(StringComparer.Ordinal);
        using (var connection = new SqliteConnection($"Data Source={_databasePath};Foreign Keys=True;"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA table_info(TaskEvents);";
            using var reader = command.ExecuteReader();
            while (reader.Read()) columns.Add(reader.GetString(1));
        }
        Assert.Contains("progress_percent", columns);
        Assert.Contains("progress_message", columns);
        Assert.NotNull(migrated.GetTaskState("progress-migration"));
    }

    [Fact]
    public void RevokedAgentCannotReportProgressOrCompleteAnActiveLease()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-revoked", "device-revoked", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("revoked-task"), now);
        var lease = _store.ClaimNext("agent-revoked", now.AddSeconds(1), TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);

        Assert.True(_store.SetAgentOwnerApproval("agent-revoked", false));
        Assert.False(_store.ReportProgress("agent-revoked", "revoked-task", lease.LeaseId,
            lease.FencingToken, 10, "should be rejected", now.AddSeconds(2)));
        Assert.False(_store.Complete("agent-revoked", "revoked-task", lease.LeaseId,
            lease.FencingToken, succeeded: true, now.AddSeconds(3)));
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("revoked-task"));
    }

    [Fact]
    public void DeviceIdResolvesToStableAgentId()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-lookup", "device-lookup", ["research"], 1, true), now);
        Assert.Equal("agent-lookup", _store.GetAgentIdByDeviceId("device-lookup"));
        Assert.Null(_store.GetAgentIdByDeviceId("missing-device"));
    }

    [Fact]
    public void TaskStateHistoryIsDurableAndOrderedAcrossStoreRestart()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-history", "device-history", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("history-task"), now);

        var lease = _store.ClaimNext("agent-history", now.AddSeconds(1), TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);
        Assert.True(_store.Complete("agent-history", lease.TaskId, lease.LeaseId,
            lease.FencingToken, succeeded: true, now.AddSeconds(2)));

        var reopened = new DesktopHubStore(_databasePath);
        var history = reopened.GetTaskHistory("history-task");

        Assert.Collection(history,
            item => { Assert.Equal(HubTaskState.Queued, item.State); Assert.Equal("TASK_ENQUEUED", item.EventCode); },
            item => { Assert.Equal(HubTaskState.Leased, item.State); Assert.Equal("TASK_LEASED", item.EventCode); Assert.Equal("agent-history", item.AgentId); },
            item => { Assert.Equal(HubTaskState.Succeeded, item.State); Assert.Equal("TASK_SUCCEEDED", item.EventCode); });
        Assert.True(history[0].EventId < history[1].EventId && history[1].EventId < history[2].EventId);
        Assert.All(history, item => Assert.Equal("history-task", item.TaskId));
    }

    [Fact]
    public void OwnerCanCancelQueuedTaskAndItCannotBeClaimed()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-cancel", "device-cancel", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("queued-cancel"), now);

        Assert.True(_store.CancelTask("queued-cancel", now.AddSeconds(1)));
        Assert.Equal(HubTaskState.Cancelled, _store.GetTaskState("queued-cancel"));
        Assert.Null(_store.ClaimNext("agent-cancel", now.AddSeconds(2), TimeSpan.FromMinutes(1)));
        Assert.False(_store.CancelTask("queued-cancel", now.AddSeconds(3)));

        var last = _store.GetTaskHistory("queued-cancel").Last();
        Assert.Equal(HubTaskState.Cancelled, last.State);
        Assert.Equal("TASK_CANCELLED_OWNER_REQUEST", last.EventCode);
    }

    [Fact]
    public void OwnerCancellationFencesAnActiveReadOnlyLease()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-cancel-read", "device-cancel-read", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("leased-read-cancel", risk: HubTaskEffectRisk.ReadOnly), now);
        var lease = _store.ClaimNext("agent-cancel-read", now, TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);

        Assert.True(_store.CancelTask("leased-read-cancel", now.AddSeconds(1)));
        Assert.Equal(HubTaskState.Cancelled, _store.GetTaskState("leased-read-cancel"));
        Assert.False(_store.ReportProgress("agent-cancel-read", lease.TaskId, lease.LeaseId,
            lease.FencingToken, 50, "late progress", now.AddSeconds(2)));
        Assert.False(_store.Complete("agent-cancel-read", lease.TaskId, lease.LeaseId,
            lease.FencingToken, succeeded: true, now.AddSeconds(3)));
        Assert.Equal("TASK_CANCELLED_OWNER_REQUEST",
            _store.GetTaskHistory("leased-read-cancel").Last().EventCode);
    }

    [Theory]
    [InlineData(HubTaskEffectRisk.LocalWrite)]
    [InlineData(HubTaskEffectRisk.ExternalSideEffect)]
    public void CancellingLeasedNonReadOnlyTaskBecomesOutcomeUnknown(HubTaskEffectRisk risk)
    {
        var now = DateTimeOffset.UtcNow;
        var agentId = "agent-cancel-risk";
        var taskId = "leased-risk-cancel-" + risk;
        _store.RegisterAgent(new HubAgentRegistration(agentId, "device-cancel-risk", ["research"], 1, true), now);
        var approval = risk == HubTaskEffectRisk.ExternalSideEffect ? "approval-" + taskId : null;
        _store.Enqueue(CreateTask(taskId, risk: risk, approval: approval), now);
        var lease = _store.ClaimNext(agentId, now, TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);

        Assert.True(_store.CancelTask(taskId, now.AddSeconds(1)));

        Assert.Equal(HubTaskState.OutcomeUnknown, _store.GetTaskState(taskId));
        Assert.False(_store.ReportProgress(agentId, lease.TaskId, lease.LeaseId,
            lease.FencingToken, 50, "late progress", now.AddSeconds(2)));
        Assert.False(_store.Complete(agentId, lease.TaskId, lease.LeaseId,
            lease.FencingToken, succeeded: true, now.AddSeconds(3)));
        Assert.Empty(_store.RecoverExpiredLeases(now.AddMinutes(2)));
        var last = _store.GetTaskHistory(taskId).Last();
        Assert.Equal(HubTaskState.OutcomeUnknown, last.State);
        Assert.Equal("TASK_CANCELLED_OUTCOME_UNKNOWN", last.EventCode);
    }

    [Fact]
    public async Task OwnerCancellationAndAgentCompletionRaceHaveExactlyOneDurableWinner()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-cancel-race", "device-cancel-race", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("cancel-race"), now);
        var lease = _store.ClaimNext("agent-cancel-race", now, TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);
        var secondStore = new DesktopHubStore(_databasePath);

        var cancel = Task.Run(() => _store.CancelTask("cancel-race", now.AddSeconds(1)));
        var complete = Task.Run(() => secondStore.Complete(
            "agent-cancel-race", lease.TaskId, lease.LeaseId, lease.FencingToken,
            succeeded: true, now.AddSeconds(1), result: "read-only result"));
        await Task.WhenAll(cancel, complete);

        Assert.NotEqual(cancel.Result, complete.Result);
        var state = _store.GetTaskState("cancel-race")
            ?? throw new InvalidOperationException("Cancellation race task disappeared.");
        Assert.Contains(state, new[] { HubTaskState.Cancelled, HubTaskState.Succeeded });
        var history = _store.GetTaskHistory("cancel-race");
        Assert.Equal(1, history.Count(item => item.State is HubTaskState.Cancelled or HubTaskState.Succeeded));
        if (state == HubTaskState.Cancelled)
        {
            Assert.Null(_store.GetTaskResult("cancel-race"));
            Assert.Equal("TASK_CANCELLED_OWNER_REQUEST", history[^1].EventCode);
        }
        else
        {
            Assert.NotNull(_store.GetTaskResult("cancel-race"));
            Assert.Equal("TASK_SUCCEEDED", history[^1].EventCode);
        }
    }

    [Fact]
    public void OwnerCannotCancelAlreadyTerminalTask()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-cancel-terminal", "device-cancel-terminal", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("terminal-cancel"), now);
        var lease = _store.ClaimNext("agent-cancel-terminal", now, TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);
        Assert.True(_store.Complete("agent-cancel-terminal", lease.TaskId, lease.LeaseId,
            lease.FencingToken, succeeded: true, now.AddSeconds(1)));

        Assert.False(_store.CancelTask("terminal-cancel", now.AddSeconds(2)));
        Assert.Equal(HubTaskState.Succeeded, _store.GetTaskState("terminal-cancel"));
    }

    [Fact]
    public void ExpiredLeaseRecoveryIsRecordedInTaskHistory()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-recover", "device-recover", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("recover-task"), now);
        Assert.NotNull(_store.ClaimNext("agent-recover", now, TimeSpan.FromSeconds(1)));

        _store.RecoverExpiredLeases(now.AddSeconds(2));

        var history = _store.GetTaskHistory("recover-task");
        Assert.NotEmpty(history);
        var last = history[^1];
        Assert.Equal(HubTaskState.Queued, last.State);
        Assert.Equal("LEASE_EXPIRED_READ_ONLY_REQUEUED", last.EventCode);
    }

    [Fact]
    public void TaskHistoryLimitIsValidated()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => _store.GetTaskHistory("task-1", 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => _store.GetTaskHistory("task-1", 5_001));
    }

    [Fact]
    public void DashboardTaskQueryEnforcesAReasonableLimit()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => _store.GetTasks(0));
        Assert.Throws<ArgumentOutOfRangeException>(() => _store.GetTasks(1_001));
    }

    private static HubTaskRequest CreateTask(
        string id,
        IReadOnlyCollection<string>? required = null,
        IReadOnlyCollection<string>? dependencies = null,
        HubTaskEffectRisk risk = HubTaskEffectRisk.ReadOnly,
        string? approval = null) =>
        new(id, "project-a", "windows-core-0001", 1,
            "idem-" + id, "research.search", "payload://" + id,
            new string('a', 64), required ?? ["research"], dependencies ?? [],
            risk, approval);

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var path in new[] { _databasePath, _databasePath + "-wal", _databasePath + "-shm" })
        {
            try { if (File.Exists(path)) File.Delete(path); }
            catch (IOException) { }
        }
    }
}
