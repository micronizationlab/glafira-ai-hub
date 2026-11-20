using GLAFIRA.Desktop.Hub.Core;
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
    public void CapabilityMismatchDoesNotClaimCreateTask()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration("agent-1", "device-1", ["research"], 1, true), now);
        _store.Enqueue(CreateTask("task-1", required: ["coding"]), now);

        Assert.Null(_store.ClaimNext("agent-1", now, TimeSpan.FromMinutes(1)));
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

        Assert.Single(claims.Where(lease => lease is not null));
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

    private static HubTaskRequest CreateTask(
        string id,
        IReadOnlyCollection<string>? required = null,
        IReadOnlyCollection<string>? dependencies = null,
        HubTaskEffectRisk risk = HubTaskEffectRisk.ReadOnly,
        string? approval = null) =>
        new(id, "project-a", "idem-" + id, "research.search", "payload://" + id,
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
