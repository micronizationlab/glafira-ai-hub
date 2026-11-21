using System.Security.Cryptography;
using System.Text;
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
        Assert.Equal(pending, Assert.Single(restored.PendingResults));
        Assert.Equal(lease.PayloadSha256, Assert.Single(restored.PendingResults).Lease.PayloadSha256);

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            journal.AcknowledgeResultAsync(lease.TaskId, lease.LeaseId, lease.FencingToken + 1, CancellationToken.None));

        Assert.True(await journal.AcknowledgeResultAsync(
            lease.TaskId, lease.LeaseId, lease.FencingToken, CancellationToken.None));
        Assert.False(await journal.AcknowledgeResultAsync(
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
