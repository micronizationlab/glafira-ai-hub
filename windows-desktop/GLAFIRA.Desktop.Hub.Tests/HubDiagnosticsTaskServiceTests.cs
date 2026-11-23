using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class HubDiagnosticsTaskServiceTests : IDisposable
{
    private readonly string _root = Path.Combine(
        Path.GetTempPath(), "glafira-diagnostics-task-" + Guid.NewGuid().ToString("N"));
    private readonly string _databasePath;
    private readonly string _payloadRoot;
    private readonly PassthroughDataProtector _protector = new();

    public HubDiagnosticsTaskServiceTests()
    {
        _databasePath = Path.Combine(_root, "hub.db");
        _payloadRoot = Path.Combine(_root, "payloads");
    }

    [Fact]
    public async Task QueuesPinnedReadOnlyContractThatProductionResolverCanServeAndAgentCanClaim()
    {
        var now = new DateTimeOffset(2026, 11, 22, 14, 0, 0, TimeSpan.Zero);
        var store = new DesktopHubStore(_databasePath, _protector);
        var payloadStore = new HubTaskPayloadStore(_payloadRoot, _protector);
        var service = new HubDiagnosticsTaskService(
            store, payloadStore, "windows-core-0001");

        var submission = await service.QueueAsync("project-diagnostics", now);
        var resolved = await payloadStore.ResolveAsync(submission.PayloadRef, CancellationToken.None);

        var payload = Assert.IsType<byte[]>(resolved);
        try
        {
            Assert.Equal(HubDiagnosticsTaskContract.RequestPayloadReference, submission.PayloadRef);
            Assert.Equal(HubDiagnosticsTaskContract.RequestPayloadSha256, submission.PayloadSha256);
            Assert.Equal(HubDiagnosticsTaskContract.CanonicalRequestJson, Encoding.UTF8.GetString(payload));
            Assert.Equal(
                submission.PayloadSha256,
                Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant());
        }
        finally
        {
            CryptographicOperations.ZeroMemory(payload);
        }

        store.RegisterAgent(new HubAgentRegistration(
            "agent-diagnostics", "device-diagnostics",
            [HubDiagnosticsTaskContract.RequiredCapability], 1, true), now);
        Assert.Null(store.ClaimNext(
            "agent-diagnostics", now.AddSeconds(1), TimeSpan.FromMinutes(1), ["other.task"]));

        var lease = Assert.IsType<HubTaskLease>(store.ClaimNext(
            "agent-diagnostics", now.AddSeconds(2), TimeSpan.FromMinutes(1),
            [HubDiagnosticsTaskContract.TaskType]));
        Assert.Equal(submission.TaskId, lease.TaskId);
        Assert.Equal(HubDiagnosticsTaskContract.TaskType, lease.TaskType);
        Assert.Equal(submission.PayloadRef, lease.PayloadRef);
        Assert.Equal(submission.PayloadSha256, lease.PayloadSha256);
        Assert.Equal(HubTaskEffectRisk.ReadOnly, lease.EffectRisk);
        Assert.Null(lease.ApprovalRef);
        Assert.Equal("windows-core-0001", lease.OwnerCoreId);
        Assert.Equal(1, lease.OwnerGeneration);
    }

    [Fact]
    public async Task OwnerProjectionExposesVerifiedMetadataButNeverArtifactPayload()
    {
        var now = new DateTimeOffset(2026, 11, 22, 14, 30, 0, TimeSpan.Zero);
        var store = new DesktopHubStore(_databasePath, _protector);
        var payloadStore = new HubTaskPayloadStore(_payloadRoot, _protector);
        var service = new HubDiagnosticsTaskService(
            store, payloadStore, "windows-core-0001");
        var submission = await service.QueueAsync("project-diagnostics", now);
        store.RegisterAgent(new HubAgentRegistration(
            "agent-diagnostics", "device-diagnostics",
            [HubDiagnosticsTaskContract.RequiredCapability], 1, true), now);
        var lease = Assert.IsType<HubTaskLease>(store.ClaimNext(
            "agent-diagnostics", now.AddSeconds(1), TimeSpan.FromMinutes(5),
            [HubDiagnosticsTaskContract.TaskType]));

        var artifactPayload = Encoding.UTF8.GetBytes(
            "{\"status\":\"healthy\",\"hiddenCanary\":\"must-not-escape\"}");
        var artifactId = "diagnostics-artifact-0001";
        var artifactDigest = Convert.ToHexString(SHA256.HashData(artifactPayload)).ToLowerInvariant();
        try
        {
            var metadata = new HubTaskArtifactMetadata(
                1, artifactId, submission.TaskId, lease.OwnerCoreId, lease.OwnerGeneration,
                lease.LeaseId, lease.FencingToken, "glafira.agent.diagnostics.snapshot", 1,
                "application/json", artifactPayload.Length, artifactDigest, "REDACTED",
                now.AddSeconds(2));
            Assert.Equal(
                HubTaskArtifactAcceptance.Accepted,
                store.AcceptTaskArtifact(
                    "agent-diagnostics", "project-diagnostics", metadata,
                    artifactPayload, now.AddSeconds(2)));

            var terminalResult = JsonSerializer.Serialize(new
            {
                TaskId = submission.TaskId,
                Outcome = "SUCCEEDED",
                ResultRef = "agent-artifact:" + artifactId,
                ResultSha256 = artifactDigest,
                ErrorCode = (string?)null
            });
            Assert.True(store.Complete(
                "agent-diagnostics", submission.TaskId, lease.LeaseId,
                lease.FencingToken, true, now.AddSeconds(3), terminalResult));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(artifactPayload);
        }

        var projection = Assert.IsType<HubTaskOwnerResultSnapshot>(
            store.GetTaskOwnerResult(submission.TaskId));
        Assert.Equal(HubTaskState.Succeeded, projection.State);
        Assert.True(projection.TerminalResultAvailable);
        Assert.True(projection.ArtifactAvailable);
        Assert.Equal("agent-artifact:" + artifactId, projection.ResultReference);
        Assert.Equal(artifactDigest, projection.ResultSha256);
        Assert.Equal("glafira.agent.diagnostics.snapshot", projection.ArtifactSchemaId);
        Assert.Equal(1, projection.ArtifactSchemaVersion);
        Assert.Equal("REDACTED", projection.ArtifactRedactionStatus);

        var ownerVisibleJson = JsonSerializer.Serialize(projection);
        Assert.DoesNotContain("must-not-escape", ownerVisibleJson, StringComparison.Ordinal);
        Assert.DoesNotContain("healthy", ownerVisibleJson, StringComparison.Ordinal);
    }

    [Fact]
    public async Task QueuedTaskProjectionContainsNoResultOrArtifactIdentity()
    {
        var now = new DateTimeOffset(2026, 11, 22, 15, 0, 0, TimeSpan.Zero);
        var store = new DesktopHubStore(_databasePath, _protector);
        var service = new HubDiagnosticsTaskService(
            store, new HubTaskPayloadStore(_payloadRoot, _protector), "windows-core-0001");
        var submission = await service.QueueAsync("project-diagnostics", now);

        var projection = Assert.IsType<HubTaskOwnerResultSnapshot>(
            store.GetTaskOwnerResult(submission.TaskId));

        Assert.Equal(HubTaskState.Queued, projection.State);
        Assert.False(projection.TerminalResultAvailable);
        Assert.False(projection.ArtifactAvailable);
        Assert.Null(projection.ResultReference);
        Assert.Null(projection.ResultSha256);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        try
        {
            if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
        }
        catch (IOException)
        {
        }
    }

    private sealed class PassthroughDataProtector : IHubDataProtector
    {
        public byte[] Protect(ReadOnlySpan<byte> plaintext, ReadOnlySpan<byte> entropy) =>
            plaintext.ToArray();

        public byte[] Unprotect(ReadOnlySpan<byte> ciphertext, ReadOnlySpan<byte> entropy) =>
            ciphertext.ToArray();
    }
}
