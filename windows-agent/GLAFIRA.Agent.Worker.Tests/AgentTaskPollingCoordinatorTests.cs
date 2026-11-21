using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentTaskPollingCoordinatorTests : IDisposable
{
    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "glafira-agent-poll-" + Guid.NewGuid().ToString("N"));
    private readonly string _journalPath;
    private readonly TestProtector _protector = new();

    public AgentTaskPollingCoordinatorTests()
    {
        Directory.CreateDirectory(_directory);
        _journalPath = Path.Combine(_directory, "execution.dpapi");
    }

    [Fact]
    public async Task ClaimsOnlyRegisteredTaskTypesAndAcknowledgesDurableResult()
    {
        var state = ReadyState();
        var handler = new TestHandler("web.research");
        var transport = new FakeTransport(Lease());
        var journal = Journal();
        var coordinator = Coordinator(state, [handler], transport, journal);

        var worked = await coordinator.PollOnceAsync(Credential(), CancellationToken.None);

        Assert.True(worked);
        Assert.Equal(1, handler.Calls);
        Assert.Equal(new[] { "web.research" }, transport.LastSupportedTaskTypes);
        Assert.Contains(transport.Updates, update => update.State == "RUNNING");
        Assert.Contains(transport.Updates, update => update.State == "SUCCEEDED");
        var snapshot = await journal.LoadAsync(CancellationToken.None);
        Assert.Empty(snapshot.ActiveLeases);
        Assert.Empty(snapshot.PendingResults);
    }

    [Fact]
    public async Task FailedResultDeliveryStaysInOutboxAndRetriesWithoutReexecution()
    {
        var state = ReadyState();
        var handler = new TestHandler("web.research");
        var transport = new FakeTransport(Lease()) { FailTerminalUpdates = 1 };
        var journal = Journal();
        var coordinator = Coordinator(state, [handler], transport, journal);

        await Assert.ThrowsAsync<HttpRequestException>(() =>
            coordinator.PollOnceAsync(Credential(), CancellationToken.None));

        var pending = await journal.LoadAsync(CancellationToken.None);
        Assert.Empty(pending.ActiveLeases);
        Assert.Single(pending.PendingResults);
        Assert.Equal(1, handler.Calls);

        var retried = await coordinator.PollOnceAsync(Credential(), CancellationToken.None);

        Assert.False(retried);
        Assert.Equal(1, handler.Calls);
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).PendingResults);
        Assert.Equal(2, transport.TerminalAttempts);
    }

    [Fact]
    public async Task DoesNotClaimWhenNoTypedHandlerIsRegistered()
    {
        var transport = new FakeTransport(Lease());
        var coordinator = Coordinator(ReadyState(), [], transport, Journal());

        var worked = await coordinator.PollOnceAsync(Credential(), CancellationToken.None);

        Assert.False(worked);
        Assert.Equal(0, transport.ClaimCalls);
    }

    [Fact]
    public async Task NonReadOnlyTaskIsRejectedWithoutCallingItsHandler()
    {
        var state = ReadyState();
        var handler = new TestHandler("web.research");
        var transport = new FakeTransport(Lease() with { EffectRisk = "ExternalSideEffect" });
        var journal = Journal();
        var coordinator = Coordinator(state, [handler], transport, journal);

        Assert.True(await coordinator.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Equal(0, handler.Calls);
        var terminal = Assert.Single(transport.Updates.Where(update => update.State == "FAILED"));
        var serialized = JsonSerializer.Deserialize<string>(terminal.ResultJson)!;
        var result = JsonSerializer.Deserialize<AgentTaskResult>(serialized)!;
        Assert.Equal("NON_READ_ONLY_TASK_DISABLED", result.ErrorCode);
    }

    [Fact]
    public async Task ResumesJournaledReadOnlyLeaseAfterWorkerRestart()
    {
        var state = ReadyState();
        var handler = new TestHandler("web.research");
        var lease = Lease();
        var journal = Journal();
        await journal.SaveActiveLeaseAsync(lease, CancellationToken.None);
        var transport = new FakeTransport();
        var coordinator = Coordinator(state, [handler], transport, journal);

        Assert.True(await coordinator.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Equal(1, handler.Calls);
        Assert.Equal(0, transport.ClaimCalls);
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).PendingResults);
    }

    [Fact]
    public async Task ExpiredActiveLeaseIsClearedBeforeAReplacementClaim()
    {
        var state = ReadyState();
        var handler = new TestHandler("web.research");
        var journal = Journal();
        var expired = Lease() with { LeaseExpiresAtEpochMs = DateTimeOffset.UtcNow.AddSeconds(-1).ToUnixTimeMilliseconds() };
        await journal.SaveActiveLeaseAsync(expired, CancellationToken.None);
        var transport = new FakeTransport(Lease() with { TaskId = "task-next", LeaseId = "lease-next" });
        var coordinator = Coordinator(state, [handler], transport, journal);

        Assert.False(await coordinator.PollOnceAsync(Credential(), CancellationToken.None));
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).ActiveLeases);
        Assert.Equal(0, transport.ClaimCalls);

        Assert.True(await coordinator.PollOnceAsync(Credential(), CancellationToken.None));
        Assert.Equal(1, handler.Calls);
        Assert.Equal(1, transport.ClaimCalls);
    }

    [Fact]
    public async Task ExplicitStaleLeaseRejectionDiscardsOnlyThatPendingResult()
    {
        var state = ReadyState();
        var handler = new TestHandler("web.research");
        var transport = new FakeTransport(Lease()) { RejectTerminalUpdates = 1 };
        var journal = Journal();
        var coordinator = Coordinator(state, [handler], transport, journal);

        Assert.True(await coordinator.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Equal(1, handler.Calls);
        Assert.Empty((await journal.LoadAsync(CancellationToken.None)).PendingResults);
        Assert.Equal(1, transport.TerminalAttempts);
    }

    [Fact]
    public async Task MismatchedHubAckLeavesResultInOutbox()
    {
        var state = ReadyState();
        var handler = new TestHandler("web.research");
        var transport = new FakeTransport(Lease()) { ReturnMismatchedAck = true };
        var journal = Journal();
        var coordinator = Coordinator(state, [handler], transport, journal);

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            coordinator.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Single((await journal.LoadAsync(CancellationToken.None)).PendingResults);
    }

    private DpapiAgentExecutionJournal Journal() => new(_journalPath, _protector);

    private AgentTaskPollingCoordinator Coordinator(
        AgentRuntimeState state,
        IEnumerable<IAgentTaskHandler> handlers,
        FakeTransport transport,
        IAgentExecutionJournal journal)
    {
        var registry = new AgentTaskHandlerRegistry(handlers);
        var executor = new AgentTaskExecutor(registry, state, maxParallelTasks: 1);
        return new AgentTaskPollingCoordinator(
            state, registry, executor, transport, journal,
            renewInterval: TimeSpan.FromSeconds(5));
    }

    private static AgentRuntimeState ReadyState()
    {
        var state = new AgentRuntimeState();
        state.SetOwnerProfile(["web.research"], 1);
        state.SetStatus(AgentServiceStatus.Ready);
        return state;
    }

    private static AgentEnrollmentCredential Credential()
    {
        var now = DateTimeOffset.UtcNow;
        return new AgentEnrollmentCredential(
            "agent-001", "desktop-001", new Uri("https://127.0.0.1:8443"),
            new string('a', 64), Convert.ToBase64String(new byte[32]),
            new HashSet<string> { "desktop.task.claim", "desktop.task.result" },
            now.AddMinutes(-1), now.AddHours(1));
    }

    private static AgentTaskLease Lease()
    {
        var now = DateTimeOffset.UtcNow;
        return new AgentTaskLease(
            "task-001", "project-001", "web.research", "idem-task-001",
            "payload://task-001", new string('a', 64), "ReadOnly", null,
            now.AddMinutes(2).ToUnixTimeMilliseconds(),
            "parent-001", "subtask-001", "Research", "Read the supplied material",
            "RESEARCHER", new HashSet<string> { "web.research" },
            now.ToUnixTimeMilliseconds(), now.AddMinutes(5).ToUnixTimeMilliseconds(),
            10, "lease-0001", 42);
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

    private sealed class TestHandler(string taskType) : IAgentTaskHandler
    {
        private int _calls;
        public string TaskType { get; } = taskType;
        public int Calls => Volatile.Read(ref _calls);

        public Task<AgentTaskResult> ExecuteAsync(
            AgentTaskEnvelope task, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref _calls);
            return Task.FromResult(new AgentTaskResult(task.TaskId, "SUCCEEDED", "result://local", new string('b', 64)));
        }
    }

    private sealed class FakeTransport(params AgentTaskLease[] leases) : IAgentTaskTransport
    {
        private readonly Queue<AgentTaskLease> _leases = new(leases);
        public int ClaimCalls { get; private set; }
        public int FailTerminalUpdates { get; set; }
        public int RejectTerminalUpdates { get; set; }
        public int TerminalAttempts { get; private set; }
        public bool ReturnMismatchedAck { get; set; }
        public IReadOnlyList<string> LastSupportedTaskTypes { get; private set; } = [];
        public List<RecordedUpdate> Updates { get; } = [];

        public Task<AgentTaskLease?> ClaimNextAsync(
            AgentEnrollmentCredential credential,
            IReadOnlyCollection<string> ownerApprovedCapabilities,
            int maxParallelTasks,
            IReadOnlyCollection<string> supportedTaskTypes,
            CancellationToken cancellationToken = default)
        {
            ClaimCalls++;
            LastSupportedTaskTypes = supportedTaskTypes.ToArray();
            return Task.FromResult(_leases.TryDequeue(out var lease) ? lease : null);
        }

        public Task<AgentTaskUpdateAck> SendTaskUpdateAsync(
            AgentEnrollmentCredential credential,
            AgentTaskLease lease,
            string state,
            int progressPercent,
            string progressMessage,
            string resultJson = "null",
            string errorCode = "none",
            CancellationToken cancellationToken = default)
        {
            Updates.Add(new RecordedUpdate(state, resultJson, errorCode));
            if (state is "SUCCEEDED" or "FAILED")
            {
                TerminalAttempts++;
                if (FailTerminalUpdates > 0)
                {
                    FailTerminalUpdates--;
                    throw new HttpRequestException("simulated network interruption");
                }
                if (RejectTerminalUpdates > 0)
                {
                    RejectTerminalUpdates--;
                    throw new InvalidDataException("LEASE_FENCING_REJECTED");
                }
            }

            var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
            var taskId = ReturnMismatchedAck && state is "SUCCEEDED" or "FAILED" ? "wrong-task" : lease.TaskId;
            var leaseExpiry = state is "ACCEPTED" or "RUNNING"
                ? DateTimeOffset.UtcNow.AddMinutes(2).ToUnixTimeMilliseconds()
                : 0L;
            return Task.FromResult(new AgentTaskUpdateAck(
                taskId, lease.LeaseId, lease.FencingToken, state, digest, leaseExpiry));
        }
    }

    private sealed record RecordedUpdate(string State, string ResultJson, string ErrorCode);
}
