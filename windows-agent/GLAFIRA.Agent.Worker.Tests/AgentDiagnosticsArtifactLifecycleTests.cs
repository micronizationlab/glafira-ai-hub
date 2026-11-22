using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentDiagnosticsArtifactLifecycleTests : IDisposable
{
    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "glafira-agent-diag-lifecycle-" + Guid.NewGuid().ToString("N"));
    private readonly string _journalPath;
    private readonly string _artifactDirectory;
    private readonly TestProtector _protector = new();

    public AgentDiagnosticsArtifactLifecycleTests()
    {
        Directory.CreateDirectory(_directory);
        _journalPath = Path.Combine(_directory, "execution.dpapi");
        _artifactDirectory = Path.Combine(_directory, "artifacts");
    }

    [Fact]
    public async Task ExactArtifactAckAlwaysPrecedesTerminalResult()
    {
        var sequence = new List<string>();
        var source = new CountingDiagnosticsSource();
        var taskTransport = new FakeTaskTransport(sequence, DiagnosticsLease());
        var artifactTransport = new FakeArtifactTransport(sequence);
        var coordinator = Coordinator(source, taskTransport, artifactTransport);

        var worked = await coordinator.PollOnceAsync(Credential(), CancellationToken.None);

        Assert.True(worked);
        Assert.Equal(new[] { "RUNNING", "ARTIFACT", "SUCCEEDED" }, sequence);
        Assert.Equal(1, source.Calls);
        Assert.Equal(1, artifactTransport.Attempts);
        Assert.Equal(1, taskTransport.TerminalAttempts);
        Assert.Empty((await Journal().LoadAsync(CancellationToken.None)).PendingResults);
        Assert.Empty(await ArtifactStore().ListPendingArtifactIdsAsync(CancellationToken.None));
    }

    [Fact]
    public async Task LostArtifactAckRetriesAfterRestartWithoutReexecutingHandler()
    {
        var sequence = new List<string>();
        var source = new CountingDiagnosticsSource();
        var taskTransport = new FakeTaskTransport(sequence, DiagnosticsLease());
        var artifactTransport = new FakeArtifactTransport(sequence) { FailAttempts = 1 };
        var first = Coordinator(source, taskTransport, artifactTransport);

        await Assert.ThrowsAsync<HttpRequestException>(() =>
            first.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Equal(1, source.Calls);
        Assert.Equal(0, taskTransport.TerminalAttempts);
        Assert.Single((await Journal().LoadAsync(CancellationToken.None)).PendingResults);
        Assert.Single(await ArtifactStore().ListPendingArtifactIdsAsync(CancellationToken.None));

        var restarted = Coordinator(source, taskTransport, artifactTransport);
        Assert.False(await restarted.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Equal(1, source.Calls);
        Assert.Equal(2, artifactTransport.Attempts);
        Assert.Equal(1, taskTransport.TerminalAttempts);
        Assert.Equal(new[] { "RUNNING", "ARTIFACT", "ARTIFACT", "SUCCEEDED" }, sequence);
        Assert.Empty((await Journal().LoadAsync(CancellationToken.None)).PendingResults);
        Assert.Empty(await ArtifactStore().ListPendingArtifactIdsAsync(CancellationToken.None));
    }

    [Fact]
    public async Task LostTerminalAckRetriesAfterRestartWithoutArtifactOrHandlerReplay()
    {
        var sequence = new List<string>();
        var source = new CountingDiagnosticsSource();
        var taskTransport = new FakeTaskTransport(sequence, DiagnosticsLease())
        {
            FailTerminalAttempts = 1
        };
        var artifactTransport = new FakeArtifactTransport(sequence);
        var first = Coordinator(source, taskTransport, artifactTransport);

        await Assert.ThrowsAsync<HttpRequestException>(() =>
            first.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Equal(1, source.Calls);
        Assert.Equal(1, artifactTransport.Attempts);
        Assert.Equal(1, taskTransport.TerminalAttempts);
        Assert.Single((await Journal().LoadAsync(CancellationToken.None)).PendingResults);
        Assert.Empty(await ArtifactStore().ListPendingArtifactIdsAsync(CancellationToken.None));

        var restarted = Coordinator(source, taskTransport, artifactTransport);
        Assert.False(await restarted.PollOnceAsync(Credential(), CancellationToken.None));

        Assert.Equal(1, source.Calls);
        Assert.Equal(1, artifactTransport.Attempts);
        Assert.Equal(2, taskTransport.TerminalAttempts);
        Assert.Equal(new[] { "RUNNING", "ARTIFACT", "SUCCEEDED", "SUCCEEDED" }, sequence);
        Assert.Empty((await Journal().LoadAsync(CancellationToken.None)).PendingResults);
    }

    private AgentTaskPollingCoordinator Coordinator(
        CountingDiagnosticsSource source,
        FakeTaskTransport taskTransport,
        FakeArtifactTransport artifactTransport)
    {
        var state = ReadyState();
        var store = ArtifactStore();
        var handler = new AgentDiagnosticsSnapshotTaskHandler(store, source);
        var registry = new AgentTaskHandlerRegistry([handler]);
        var executor = new AgentTaskExecutor(registry, state);
        var outbox = new AgentResultArtifactOutbox(store, artifactTransport);
        return new AgentTaskPollingCoordinator(
            state, registry, executor, taskTransport, Journal(), outbox,
            renewInterval: TimeSpan.FromSeconds(5));
    }

    private DpapiAgentExecutionJournal Journal() => new(_journalPath, _protector);
    private FileAgentResultArtifactStore ArtifactStore() => new(_artifactDirectory);

    private static AgentRuntimeState ReadyState()
    {
        var state = new AgentRuntimeState();
        state.SetOwnerProfile([AgentDiagnosticsSnapshotTaskHandler.RequiredCapability], 1);
        state.SetStatus(AgentServiceStatus.Ready);
        return state;
    }

    private static AgentTaskLease DiagnosticsLease()
    {
        var now = DateTimeOffset.UtcNow;
        return new AgentTaskLease(
            "task-diag-lifecycle", "project-diag", "windows-core-0001", 7,
            AgentDiagnosticsSnapshotTaskHandler.SupportedTaskType,
            "idem-diag-lifecycle",
            AgentDiagnosticsSnapshotTaskHandler.RequestPayloadReference,
            AgentDiagnosticsSnapshotTaskHandler.RequestPayloadSha256,
            "ReadOnly", "approval-diag", now.AddMinutes(2).ToUnixTimeMilliseconds(),
            "parent-diag", "subtask-diag", "Safe diagnostics", "Collect safe diagnostics",
            "DIAGNOSTICS", new HashSet<string>
            {
                AgentDiagnosticsSnapshotTaskHandler.RequiredCapability
            },
            now.ToUnixTimeMilliseconds(), now.AddMinutes(5).ToUnixTimeMilliseconds(),
            10, "lease-diag-lifecycle", 91);
    }

    private static AgentEnrollmentCredential Credential()
    {
        var now = DateTimeOffset.UtcNow;
        return new AgentEnrollmentCredential(
            "agent-diag", "desktop-diag", new Uri("https://127.0.0.1:8443"),
            new string('a', 64), Convert.ToBase64String(new byte[32]),
            new HashSet<string> { "desktop.task.claim", "desktop.task.result" },
            now.AddMinutes(-1), now.AddHours(1));
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory)) Directory.Delete(_directory, recursive: true);
    }

    private sealed class TestProtector : IAgentLocalDataProtector
    {
        public byte[] Protect(byte[] plaintext) => plaintext.ToArray();
        public byte[] Unprotect(byte[] protectedBytes) => protectedBytes.ToArray();
    }

    private sealed class CountingDiagnosticsSource : IAgentSafeDiagnosticsSource
    {
        private int _calls;
        public int Calls => Volatile.Read(ref _calls);

        public ValueTask<AgentSafeRuntimeDiagnostics> CollectAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref _calls);
            return ValueTask.FromResult(new AgentSafeRuntimeDiagnostics(
                AgentDiagnosticsPlatform.Windows,
                Architecture.X64,
                Architecture.X64,
                new Version(8, 0, 12)));
        }
    }

    private sealed class FakeArtifactTransport(List<string> sequence) : IAgentResultArtifactTransport
    {
        public int FailAttempts { get; set; }
        public int Attempts { get; private set; }

        public Task<AgentTaskArtifactAck> UploadTaskArtifactAsync(
            AgentEnrollmentCredential credential,
            AgentResultArtifact artifact,
            CancellationToken cancellationToken = default)
        {
            Attempts++;
            sequence.Add("ARTIFACT");
            if (FailAttempts > 0)
            {
                FailAttempts--;
                throw new HttpRequestException("simulated lost artifact ACK");
            }
            var metadata = artifact.Metadata;
            return Task.FromResult(new AgentTaskArtifactAck(
                metadata.ArtifactId, metadata.TaskId, metadata.OwnerCoreId,
                metadata.OwnerGeneration, metadata.LeaseId, metadata.FencingToken,
                metadata.SchemaId, metadata.SchemaVersion, metadata.ByteLength,
                metadata.Sha256, duplicate: Attempts > 1));
        }
    }

    private sealed class FakeTaskTransport(
        List<string> sequence,
        params AgentTaskLease[] leases) : IAgentTaskTransport
    {
        private readonly Queue<AgentTaskLease> _leases = new(leases);
        public int FailTerminalAttempts { get; set; }
        public int TerminalAttempts { get; private set; }

        public Task<AgentTaskLease?> ClaimNextAsync(
            AgentEnrollmentCredential credential,
            IReadOnlyCollection<string> ownerApprovedCapabilities,
            int maxParallelTasks,
            IReadOnlyCollection<string> supportedTaskTypes,
            CancellationToken cancellationToken = default) =>
            Task.FromResult(_leases.TryDequeue(out var lease) ? lease : null);

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
            sequence.Add(state);
            if (state is "SUCCEEDED" or "FAILED")
            {
                TerminalAttempts++;
                if (FailTerminalAttempts > 0)
                {
                    FailTerminalAttempts--;
                    throw new HttpRequestException("simulated lost terminal ACK");
                }
            }
            var digest = Convert.ToHexString(
                SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
            return Task.FromResult(new AgentTaskUpdateAck(
                lease.TaskId, lease.LeaseId, lease.FencingToken, state, digest,
                state == "RUNNING"
                    ? DateTimeOffset.UtcNow.AddMinutes(2).ToUnixTimeMilliseconds()
                    : 0));
        }
    }
}
