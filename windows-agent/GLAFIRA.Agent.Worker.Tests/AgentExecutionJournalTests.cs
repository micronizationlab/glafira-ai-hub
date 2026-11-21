using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentExecutionJournalTests : IDisposable
{
    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "glafira-agent-journal-" + Guid.NewGuid().ToString("N"));
    private readonly string _path;
    private readonly TestProtector _protector = new();

    public AgentExecutionJournalTests()
    {
        Directory.CreateDirectory(_directory);
        _path = Path.Combine(_directory, "execution.dpapi");
    }

    [Fact]
    public async Task ActiveLeaseSurvivesJournalReload()
    {
        var journal = CreateJournal();
        var lease = Lease();

        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        var restored = await CreateJournal().LoadAsync(CancellationToken.None);

        var actual = Assert.Single(restored.ActiveLeases);
        Assert.Equal(lease.TaskId, actual.TaskId);
        Assert.Equal(lease.LeaseId, actual.LeaseId);
        Assert.Equal(lease.FencingToken, actual.FencingToken);
        Assert.Equal(lease.RequiredCapabilities, actual.RequiredCapabilities);
    }

    [Fact]
    public async Task CompletedResultMovesAtomicallyToOutboxAndIsRemovedOnlyAfterExactAck()
    {
        var journal = CreateJournal();
        var lease = Lease();
        var pending = Pending(lease);

        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        await journal.PersistCompletedResultAsync(pending, CancellationToken.None);
        // Repeated persistence of the same terminal result is idempotent.
        await journal.PersistCompletedResultAsync(pending, CancellationToken.None);

        var restored = await CreateJournal().LoadAsync(CancellationToken.None);
        Assert.Empty(restored.ActiveLeases);
        var restoredPending = Assert.Single(restored.PendingResults);
        Assert.Equal(lease.TaskId, restoredPending.Lease.TaskId);
        Assert.Equal(lease.LeaseId, restoredPending.Lease.LeaseId);
        Assert.Equal(lease.FencingToken, restoredPending.Lease.FencingToken);
        Assert.Equal(lease.PayloadSha256, restoredPending.Lease.PayloadSha256);
        Assert.Equal(pending.ResultSha256, restoredPending.ResultSha256);

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.AcknowledgeResultAsync(lease.TaskId, lease.LeaseId, lease.FencingToken + 1, CancellationToken.None));

        Assert.True(await journal.AcknowledgeResultAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken, CancellationToken.None));
        Assert.False(await journal.AcknowledgeResultAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken, CancellationToken.None));
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).PendingResults);
    }

    [Fact]
    public async Task LegacyJournalWithoutProgressPropertyLoadsAndUpgradesOnWrite()
    {
        var lease = Lease();
        var legacyJson = JsonSerializer.Serialize(new
        {
            activeLeases = new[] { lease },
            pendingResults = Array.Empty<AgentPendingResult>()
        }, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        await File.WriteAllBytesAsync(
            _path,
            _protector.Protect(Encoding.UTF8.GetBytes(legacyJson)));

        var journal = CreateJournal();
        var legacySnapshot = await journal.LoadAsync(CancellationToken.None);
        Assert.Single(legacySnapshot.ActiveLeases);
        Assert.Empty(legacySnapshot.PendingProgresses);

        await journal.PersistProgressAsync(
            new AgentPendingProgress(lease, 10, "journal upgraded"), CancellationToken.None);
        Assert.Single((await journal.LoadAsync(CancellationToken.None)).PendingProgresses);
    }

    [Fact]
    public async Task ProgressOutboxSurvivesReloadAndRequiresExactLeaseFenceAck()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        await journal.PersistProgressAsync(
            new AgentPendingProgress(lease, 25, "processing safely"), CancellationToken.None);

        var pending = Assert.Single((await journal.LoadAsync(CancellationToken.None)).PendingProgresses);
        Assert.Equal("processing safely", pending.ProgressMessage);
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.AcknowledgeProgressAsync(lease.TaskId, lease.LeaseId, lease.FencingToken + 1, CancellationToken.None));
        Assert.Single((await journal.LoadAsync(CancellationToken.None)).PendingProgresses);

        Assert.True(await journal.AcknowledgeProgressAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken, CancellationToken.None));
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).PendingProgresses);
    }

    [Fact]
    public async Task TerminalResultAtomicallySupersedesUnacknowledgedProgress()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        await journal.PersistProgressAsync(
            new AgentPendingProgress(lease, 50, "still running"), CancellationToken.None);
        await journal.PersistCompletedResultAsync(Pending(lease), CancellationToken.None);

        var snapshot = await journal.LoadAsync(CancellationToken.None);
        Assert.Empty(snapshot.ActiveLeases);
        Assert.Empty(snapshot.PendingProgresses);
        Assert.Single(snapshot.PendingResults);
    }

    [Fact]
    public async Task SameLeaseCanPersistServerConfirmedRenewedExpiry()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        var renewed = lease with { LeaseExpiresAtEpochMs = lease.LeaseExpiresAtEpochMs + 60_000 };

        await journal.SaveActiveLeaseAsync(renewed, CancellationToken.None);

        Assert.Equal(renewed.LeaseExpiresAtEpochMs,
            Assert.Single((await journal.LoadAsync(CancellationToken.None)).ActiveLeases).LeaseExpiresAtEpochMs);
    }

    [Fact]
    public async Task ActiveLeaseCanBeClearedOnlyAfterItsExpiryAndExactFence()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);

        Assert.False(await journal.ClearExpiredActiveLeaseAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken,
            lease.LeaseExpiresAtEpochMs - 1, CancellationToken.None));
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.ClearExpiredActiveLeaseAsync(
                lease.TaskId, lease.LeaseId, lease.FencingToken + 1,
                lease.LeaseExpiresAtEpochMs + 1, CancellationToken.None));

        Assert.True(await journal.ClearExpiredActiveLeaseAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken,
            lease.LeaseExpiresAtEpochMs + 1, CancellationToken.None));
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).ActiveLeases);
    }

    [Fact]
    public async Task ExplicitlyRejectedActiveLeaseCanBeDiscardedOnlyWithExactFence()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.DiscardStaleActiveLeaseAsync(
                lease.TaskId, lease.LeaseId, lease.FencingToken + 1, CancellationToken.None));
        Assert.Single((await journal.LoadAsync(CancellationToken.None)).ActiveLeases);

        Assert.True(await journal.DiscardStaleActiveLeaseAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken, CancellationToken.None));
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).ActiveLeases);
    }

    [Fact]
    public async Task StaleResultCanBeDiscardedOnlyWithExactLeaseFence()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        await journal.PersistCompletedResultAsync(Pending(lease), CancellationToken.None);

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.DiscardStaleResultAsync(lease.TaskId, lease.LeaseId, lease.FencingToken + 1, CancellationToken.None));
        Assert.True(await journal.DiscardStaleResultAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken, CancellationToken.None));
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).PendingResults);
    }

    [Fact]
    public async Task DifferentLeaseCannotOverwriteAnActiveTaskIdentity()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.SaveActiveLeaseAsync(lease with { LeaseId = "different-lease" }, CancellationToken.None));
        Assert.Equal(lease.LeaseId,
            Assert.Single((await journal.LoadAsync(CancellationToken.None)).ActiveLeases).LeaseId);
    }

    [Fact]
    public async Task ResultCannotBePersistedWithoutMatchingActiveLease()
    {
        var journal = CreateJournal();
        var lease = Lease();

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.PersistCompletedResultAsync(Pending(lease), CancellationToken.None));
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).PendingResults);
    }

    [Fact]
    public async Task CorruptJournalFailsClosedInsteadOfReturningAnEmptyQueue()
    {
        await File.WriteAllTextAsync(_path, "not a valid protected snapshot");

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            CreateJournal().LoadAsync(CancellationToken.None));
    }

    [Fact]
    public async Task ResultDigestMismatchIsRejectedBeforeJournalMutation()
    {
        var journal = CreateJournal();
        var lease = Lease();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        var invalid = Pending(lease) with { ResultSha256 = new string('0', 64) };

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.PersistCompletedResultAsync(invalid, CancellationToken.None));
        Assert.Single((await journal.LoadAsync(CancellationToken.None)).ActiveLeases);
    }

    private DpapiAgentExecutionJournal CreateJournal() => new(_path, _protector);

    private static AgentTaskLease Lease() => new(
        "task-001", "project-001", "web.research", "idem-task-001",
        "payload://task-001", new string('a', 64), "ReadOnly", null,
        DateTimeOffset.UtcNow.AddMinutes(2).ToUnixTimeMilliseconds(),
        "parent-001", "subtask-001", "Research", "Read the supplied material",
        "RESEARCHER", new HashSet<string> { "web.research" },
        DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        DateTimeOffset.UtcNow.AddMinutes(5).ToUnixTimeMilliseconds(),
        10, "lease-0001", 42);

    private static AgentPendingResult Pending(AgentTaskLease lease)
    {
        const string resultJson = "\"{\\\"outcome\\\":\\\"SUCCEEDED\\\"}\"";
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
        return new AgentPendingResult(lease, "SUCCEEDED", 100, "completed",
            resultJson, "none", digest);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
            Directory.Delete(_directory, recursive: true);
    }

    private sealed class TestProtector : IAgentLocalDataProtector
    {
        public byte[] Protect(byte[] plaintext) => plaintext.ToArray();
        public byte[] Unprotect(byte[] protectedBytes) => protectedBytes.ToArray();
    }
}
