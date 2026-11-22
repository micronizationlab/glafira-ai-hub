using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentDiagnosticsSnapshotTaskHandlerTests
{
    private static readonly AgentSafeRuntimeDiagnostics SafeDiagnostics = new(
        AgentDiagnosticsPlatform.Windows,
        Architecture.X64,
        Architecture.Arm64,
        new Version(8, 0, 12));

    [Fact]
    public void RequestContractUsesThePinnedCanonicalContentAddress()
    {
        var payload = Encoding.UTF8.GetBytes(
            "{\"schemaVersion\":1,\"source\":\"local-safe-runtime\"}");
        var digest = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();

        Assert.Equal(digest, AgentDiagnosticsSnapshotTaskHandler.RequestPayloadSha256);
        Assert.Equal(digest, AgentDiagnosticsSnapshotTaskHandler.RequestPayloadReference);
    }

    [Fact]
    public async Task ExactReadOnlyRequestWritesOnlyDeterministicRedactedSchema()
    {
        const string canary = "PRIVATE_CANARY_MUST_NOT_LEAVE_TASK";
        var store = new CapturingStore();
        var handler = Handler(store);
        await using var executor = Executor(handler);
        var task = Envelope("task-diag-1") with
        {
            ApprovalRef = canary,
            Title = canary,
            Instruction = canary,
            Executor = canary
        };

        var result = await executor.ExecuteAsync(task, CancellationToken.None);

        Assert.Equal("SUCCEEDED", result.Outcome);
        Assert.Null(result.ErrorCode);
        Assert.Single(store.Writes);
        var artifact = store.Writes[0];
        Assert.Equal(
            AgentDiagnosticsSnapshotTaskHandler.ArtifactResultReferencePrefix + artifact.Metadata.ArtifactId,
            result.ResultRef);
        Assert.Equal(result.ResultSha256, artifact.Metadata.Sha256);
        Assert.Equal(task.TaskId, artifact.Metadata.TaskId);
        Assert.Equal(task.ProjectId, artifact.Metadata.ProjectId);
        Assert.Equal(task.OwnerCoreId, artifact.Metadata.OwnerCoreId);
        Assert.Equal(task.OwnerGeneration, artifact.Metadata.OwnerGeneration);
        Assert.Equal(task.LeaseId, artifact.Metadata.LeaseId);
        Assert.Equal(task.FencingToken, artifact.Metadata.FencingToken);
        Assert.Equal(task.CreatedAtEpochMs, artifact.Metadata.CreatedAtEpochMs);
        Assert.InRange(artifact.Payload.Length, 1,
            AgentResultArtifactPolicy.MaximumArtifactBytes);

        var json = Encoding.UTF8.GetString(artifact.Payload);
        Assert.DoesNotContain(canary, json, StringComparison.Ordinal);
        using var document = JsonDocument.Parse(json);
        Assert.Equal(new[]
        {
            "contractVersion", "schemaId", "schemaVersion", "redactionStatus",
            "source", "platform", "processArchitecture", "osArchitecture",
            "runtimeFamily", "runtimeVersion"
        }, document.RootElement.EnumerateObject().Select(property => property.Name));
        Assert.Equal("local-safe-runtime", document.RootElement.GetProperty("source").GetString());
        Assert.Equal("windows", document.RootElement.GetProperty("platform").GetString());
        Assert.Equal("x64", document.RootElement.GetProperty("processArchitecture").GetString());
        Assert.Equal("arm64", document.RootElement.GetProperty("osArchitecture").GetString());
        Assert.Equal("dotnet", document.RootElement.GetProperty("runtimeFamily").GetString());
        Assert.Equal("8.0.12", document.RootElement.GetProperty("runtimeVersion").GetString());
    }

    [Fact]
    public async Task RepeatedExecutionIsIdempotentAndProducesOneArtifact()
    {
        var directory = Path.Combine(
            Path.GetTempPath(), "glafira-diagnostics-idempotency-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new FileAgentResultArtifactStore(directory);
            var handler = Handler(store);
            await using var executor = Executor(handler);
            var task = Envelope("task-diag-repeat");

            var first = await executor.ExecuteAsync(task, CancellationToken.None);
            var second = await executor.ExecuteAsync(task, CancellationToken.None);

            Assert.Equal("SUCCEEDED", first.Outcome);
            Assert.Equal(first, second);
            var artifactIds = await store.ListPendingArtifactIdsAsync(CancellationToken.None);
            Assert.Equal(new[]
            {
                first.ResultRef![AgentDiagnosticsSnapshotTaskHandler.ArtifactResultReferencePrefix.Length..]
            }, artifactIds);
            Assert.Single(Directory.GetFiles(directory, "*.artifact"));
        }
        finally
        {
            if (Directory.Exists(directory))
                Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task MissingOrAdditionalCapabilityScopeIsRejectedBeforeCollection()
    {
        var source = new CountingSource(SafeDiagnostics);
        var store = new CapturingStore();
        var handler = new AgentDiagnosticsSnapshotTaskHandler(store, source);
        var state = ReadyState([
            AgentDiagnosticsSnapshotTaskHandler.RequiredCapability,
            "agent.other.read"
        ]);
        await using var executor = Executor(handler, state);

        var missing = await executor.ExecuteAsync(
            Envelope("task-diag-no-cap") with { RequiredCapabilities = new HashSet<string>() },
            CancellationToken.None);
        var additional = await executor.ExecuteAsync(
            Envelope("task-diag-extra-cap") with
            {
                RequiredCapabilities = new HashSet<string>
                {
                    AgentDiagnosticsSnapshotTaskHandler.RequiredCapability,
                    "agent.other.read"
                }
            },
            CancellationToken.None);

        Assert.Equal("DIAGNOSTICS_CAPABILITY_SCOPE_INVALID", missing.ErrorCode);
        Assert.Equal("DIAGNOSTICS_CAPABILITY_SCOPE_INVALID", additional.ErrorCode);
        Assert.Equal(0, source.Calls);
        Assert.Empty(store.Writes);
    }

    [Fact]
    public async Task UnapprovedCapabilityIsRejectedByExecutorBeforeHandler()
    {
        var source = new CountingSource(SafeDiagnostics);
        var handler = new AgentDiagnosticsSnapshotTaskHandler(new CapturingStore(), source);
        await using var executor = Executor(handler, ReadyState(["agent.other.read"]));

        var result = await executor.ExecuteAsync(Envelope("task-diag-unapproved"), CancellationToken.None);

        Assert.Equal("TASK_CAPABILITY_NOT_APPROVED", result.ErrorCode);
        Assert.Equal(0, source.Calls);
    }

    [Fact]
    public async Task UnknownRequestReferenceOrFieldsDigestIsRejected()
    {
        var source = new CountingSource(SafeDiagnostics);
        var handler = new AgentDiagnosticsSnapshotTaskHandler(new CapturingStore(), source);
        await using var executor = Executor(handler);
        var changedRequestDigest = AgentResultArtifactPolicy.ComputeSha256(
            Encoding.UTF8.GetBytes(
                "{\"schemaVersion\":1,\"source\":\"local-safe-runtime\",\"unknown\":true}"));

        var unknownSource = await executor.ExecuteAsync(
            Envelope("task-diag-source") with { PayloadRef = "agent://diagnostics/snapshot/v1/registry" },
            CancellationToken.None);
        var unknownField = await executor.ExecuteAsync(
            Envelope("task-diag-field") with { PayloadSha256 = changedRequestDigest },
            CancellationToken.None);

        Assert.Equal("DIAGNOSTICS_REQUEST_UNSUPPORTED", unknownSource.ErrorCode);
        Assert.Equal("DIAGNOSTICS_REQUEST_UNSUPPORTED", unknownField.ErrorCode);
        Assert.Equal(0, source.Calls);
    }

    [Theory]
    [InlineData("LocalWrite")]
    [InlineData("ExternalSideEffect")]
    public async Task NonReadOnlyEffectClassesRemainFailClosed(string effectRisk)
    {
        var source = new CountingSource(SafeDiagnostics);
        var handler = new AgentDiagnosticsSnapshotTaskHandler(new CapturingStore(), source);
        await using var executor = Executor(handler);

        var result = await executor.ExecuteAsync(
            Envelope("task-diag-effect") with { EffectRisk = effectRisk },
            CancellationToken.None);

        Assert.Equal("NON_READ_ONLY_TASK_DISABLED", result.ErrorCode);
        Assert.Equal(0, source.Calls);
    }

    [Fact]
    public async Task UnregisteredTaskTypeRemainsFailClosed()
    {
        await using var executor = new AgentTaskExecutor(
            new AgentTaskHandlerRegistry([]), ReadyState());

        var result = await executor.ExecuteAsync(
            Envelope("task-diag-unknown") with { TaskType = "diagnostics.anything" },
            CancellationToken.None);

        Assert.Equal("NO_AGENT_HANDLER", result.ErrorCode);
    }

    [Fact]
    public async Task CallerCancellationStopsCollectionWithoutArtifact()
    {
        var store = new CapturingStore();
        var handler = new AgentDiagnosticsSnapshotTaskHandler(
            store,
            new DelegateSource(async token =>
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, token);
                return SafeDiagnostics;
            }));
        await using var executor = Executor(handler);
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(30));

        var result = await executor.ExecuteAsync(
            Envelope("task-diag-cancel"), cancellation.Token);

        Assert.Equal("CANCELLED", result.Outcome);
        Assert.Equal("TASK_CANCELLED", result.ErrorCode);
        Assert.Empty(store.Writes);
    }

    [Fact]
    public async Task CollectionTimeoutFailsClosedWithoutArtifact()
    {
        var store = new CapturingStore();
        var handler = new AgentDiagnosticsSnapshotTaskHandler(
            store,
            new DelegateSource(async token =>
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, token);
                return SafeDiagnostics;
            }),
            TimeSpan.FromMilliseconds(30));
        await using var executor = Executor(handler);

        var result = await executor.ExecuteAsync(
            Envelope("task-diag-timeout"), CancellationToken.None);

        Assert.Equal("FAILED", result.Outcome);
        Assert.Equal("DIAGNOSTICS_TIMEOUT", result.ErrorCode);
        Assert.Empty(store.Writes);
    }

    [Fact]
    public async Task UnsupportedPlatformFailsClosedWithoutArtifact()
    {
        var source = new CountingSource(SafeDiagnostics with
        {
            Platform = AgentDiagnosticsPlatform.Unsupported
        });
        var store = new CapturingStore();
        await using var executor = Executor(
            new AgentDiagnosticsSnapshotTaskHandler(store, source));

        var result = await executor.ExecuteAsync(
            Envelope("task-diag-platform"), CancellationToken.None);

        Assert.Equal("DIAGNOSTICS_SOURCE_UNSUPPORTED", result.ErrorCode);
        Assert.Empty(store.Writes);
    }

    [Fact]
    public async Task ArtifactStoreFailureCannotBecomeSuccessOrLeakDetails()
    {
        var handler = new AgentDiagnosticsSnapshotTaskHandler(
            new ThrowingStore(), new CountingSource(SafeDiagnostics));
        await using var executor = Executor(handler);

        var result = await executor.ExecuteAsync(
            Envelope("task-diag-disk"), CancellationToken.None);

        Assert.Equal("FAILED", result.Outcome);
        Assert.Equal("HANDLER_FAILURE", result.ErrorCode);
        Assert.DoesNotContain("private", result.ErrorCode, StringComparison.OrdinalIgnoreCase);
    }

    private static AgentDiagnosticsSnapshotTaskHandler Handler(IAgentResultArtifactStore store) =>
        new(store, new CountingSource(SafeDiagnostics));

    private static AgentTaskEnvelope Envelope(string taskId) => new(
        taskId,
        "project-diag",
        "windows-core-0001",
        7,
        "idempotency-" + taskId,
        AgentDiagnosticsSnapshotTaskHandler.SupportedTaskType,
        AgentDiagnosticsSnapshotTaskHandler.RequestPayloadReference,
        AgentDiagnosticsSnapshotTaskHandler.RequestPayloadSha256,
        new HashSet<string> { AgentDiagnosticsSnapshotTaskHandler.RequiredCapability },
        "approval-diag",
        CreatedAtEpochMs: 1_800_000_000_000,
        ExpiresAtEpochMs: 1_800_000_300_000,
        LeaseId: "lease-diag-0001",
        FencingToken: 19,
        EffectRisk: "ReadOnly");

    private static AgentRuntimeState ReadyState(IEnumerable<string>? capabilities = null)
    {
        var state = new AgentRuntimeState();
        state.SetOwnerProfile(
            capabilities ?? [AgentDiagnosticsSnapshotTaskHandler.RequiredCapability],
            maxParallelTasks: 1);
        state.SetStatus(AgentServiceStatus.Ready);
        return state;
    }

    private static AgentTaskExecutor Executor(
        IAgentTaskHandler handler,
        AgentRuntimeState? state = null) =>
        new(new AgentTaskHandlerRegistry([handler]), state ?? ReadyState());

    private sealed class CountingSource(AgentSafeRuntimeDiagnostics diagnostics)
        : IAgentSafeDiagnosticsSource
    {
        private int _calls;
        public int Calls => Volatile.Read(ref _calls);

        public ValueTask<AgentSafeRuntimeDiagnostics> CollectAsync(
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref _calls);
            return ValueTask.FromResult(diagnostics);
        }
    }

    private sealed class DelegateSource(
        Func<CancellationToken, Task<AgentSafeRuntimeDiagnostics>> collect)
        : IAgentSafeDiagnosticsSource
    {
        public async ValueTask<AgentSafeRuntimeDiagnostics> CollectAsync(
            CancellationToken cancellationToken) =>
            await collect(cancellationToken);
    }

    private sealed class CapturingStore : IAgentResultArtifactStore
    {
        public List<AgentResultArtifact> Writes { get; } = [];

        public Task WriteAsync(
            AgentResultArtifactMetadata metadata,
            ReadOnlyMemory<byte> payload,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            AgentResultArtifactPolicy.Validate(metadata, payload.Span);
            Writes.Add(new AgentResultArtifact(metadata, payload.ToArray()));
            return Task.CompletedTask;
        }

        public Task<AgentResultArtifact?> ReadAsync(
            string artifactId, CancellationToken cancellationToken) =>
            Task.FromResult<AgentResultArtifact?>(null);

        public Task<IReadOnlyList<string>> ListPendingArtifactIdsAsync(
            CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<string>>([]);

        public Task<bool> DeleteAsync(
            string artifactId, CancellationToken cancellationToken) =>
            Task.FromResult(false);
    }

    private sealed class ThrowingStore : IAgentResultArtifactStore
    {
        public Task WriteAsync(
            AgentResultArtifactMetadata metadata,
            ReadOnlyMemory<byte> payload,
            CancellationToken cancellationToken) =>
            throw new IOException("private disk path must not escape");

        public Task<AgentResultArtifact?> ReadAsync(
            string artifactId, CancellationToken cancellationToken) =>
            Task.FromResult<AgentResultArtifact?>(null);

        public Task<IReadOnlyList<string>> ListPendingArtifactIdsAsync(
            CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<string>>([]);

        public Task<bool> DeleteAsync(
            string artifactId, CancellationToken cancellationToken) =>
            Task.FromResult(false);
    }
}
