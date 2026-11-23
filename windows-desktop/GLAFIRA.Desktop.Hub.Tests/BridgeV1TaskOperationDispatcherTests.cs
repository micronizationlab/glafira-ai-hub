using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Desktop.Core.Ownership;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1TaskOperationDispatcherTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-bridge-dispatch-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly DesktopHubStore _store;
    private readonly byte[] _payload;

    public BridgeV1TaskOperationDispatcherTests()
    {
        _store = new DesktopHubStore(_databasePath);
        _payload = JsonSerializer.SerializeToUtf8Bytes(new
        {
            parentTaskId = "parent-0001",
            subtaskId = "subtask-0001",
            title = "Исследование",
            instruction = "Подготовить краткий проверяемый обзор.",
            executor = "RESEARCHER",
            requiredCapabilities = new[] { "web.research" },
            createdAtEpochMs = DateTimeOffset.UtcNow.AddSeconds(-1).ToUnixTimeMilliseconds(),
            expiresAtEpochMs = DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeMilliseconds(),
            priority = 5
        });
    }

    [Fact]
    public async Task HeartbeatReturnsOnlyOwnerApprovedProfileAndRefreshesLiveness()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research", "document.analysis"], 3);

        var response = await CreateDispatcher(_payload).DispatchAsync(HeartbeatRequest(), now.AddSeconds(1));

        Assert.Equal("ACCEPTED", response.Status);
        Assert.Equal("agent-0001", response.Result["agentId"]);
        Assert.Equal("true", response.Result["ownerApproved"]);
        Assert.Equal("3", response.Result["maxParallelTasks"]);
        Assert.Equal(
            ["document.analysis", "web.research"],
            JsonSerializer.Deserialize<string[]>(response.Result["capabilities"]));
        var agent = Assert.Single(_store.GetAgents(now.AddSeconds(2)));
        Assert.True(agent.OwnerApproved);
        Assert.True(agent.IsOnline);
    }

    [Fact]
    public async Task OwnershipTransferReadIsCorrelatedProjectScopedAndDoesNotRequireAgentAuthority()
    {
        var now = new DateTimeOffset(2026, 11, 21, 18, 0, 0, TimeSpan.Zero);
        _store.Enqueue(new HubTaskRequest(
            "ownership-task-0001", "project-0001", "windows-core-0001", 1,
            "ownership-idem-0001", "web.research", "payload://ownership-task-0001",
            new string('a', 64), ["web.research"], [], HubTaskEffectRisk.ReadOnly, null), now);
        using var signingKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKey = signingKey.ExportSubjectPublicKeyInfo();
        var issuer = new DesktopHubOwnershipTransferIssuer(
            _store, "windows-core-0001", 1, publicKey,
            Convert.ToHexString(SHA256.HashData(publicKey)).ToLowerInvariant(),
            payload => CoreOwnershipTransferCryptography.Sign(signingKey, payload));
        var envelope = issuer.Issue(
            "ownership-task-0001", "android-core-0001", now.AddSeconds(1), TimeSpan.FromMinutes(5));
        var request = Request(
            BridgeV1Operation.OWNERSHIP_TRANSFER_READ,
            new Dictionary<string, string> { ["transferId"] = envelope.Payload.TransferId });
        var dispatcher = CreateDispatcher(_payload);

        var response = await dispatcher.DispatchAsync(request, now.AddSeconds(2));

        Assert.Equal(1, response.ProtocolVersion);
        Assert.Equal(request.RequestId, response.RequestId);
        Assert.Equal("ACCEPTED", response.Status);
        Assert.Equal("true", response.Result["available"]);
        Assert.Equal(envelope.ToWireJson(), response.Result["envelope"]);

        var otherProject = await dispatcher.DispatchAsync(request with
        {
            RequestId = "request-owner-other-01",
            ProjectId = "project-other-01"
        }, now.AddSeconds(3));
        Assert.Equal("false", otherProject.Result["available"]);
        Assert.Single(otherProject.Result);
    }

    [Fact]
    public async Task OwnershipDiscoveryIsBoundedToExactProjectAndStillValidTransfers()
    {
        var now = new DateTimeOffset(2026, 11, 22, 12, 0, 0, TimeSpan.Zero);
        _store.Enqueue(new HubTaskRequest(
            "ownership-list-task-01", "project-0001", "windows-core-0001", 1,
            "ownership-list-idem-01", "web.research", "payload://ownership-list-task-01",
            new string('a', 64), ["web.research"], [], HubTaskEffectRisk.ReadOnly, null), now);
        _store.Enqueue(new HubTaskRequest(
            "ownership-list-task-02", "project-other-01", "windows-core-0001", 1,
            "ownership-list-idem-02", "web.research", "payload://ownership-list-task-02",
            new string('b', 64), ["web.research"], [], HubTaskEffectRisk.ReadOnly, null), now);
        _store.Enqueue(new HubTaskRequest(
            "ownership-list-task-03", "project-0001", "windows-core-0001", 1,
            "ownership-list-idem-03", "web.research", "payload://ownership-list-task-03",
            new string('c', 64), ["web.research"], [], HubTaskEffectRisk.ReadOnly, null), now);
        using var signingKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKey = signingKey.ExportSubjectPublicKeyInfo();
        var issuer = new DesktopHubOwnershipTransferIssuer(
            _store, "windows-core-0001", 1, publicKey,
            Convert.ToHexString(SHA256.HashData(publicKey)).ToLowerInvariant(),
            payload => CoreOwnershipTransferCryptography.Sign(signingKey, payload));
        var first = issuer.Issue(
            "ownership-list-task-01", "ios-core-0001", now.AddSeconds(1), TimeSpan.FromMinutes(5));
        _ = issuer.Issue(
            "ownership-list-task-02", "ios-core-0001", now.AddSeconds(2), TimeSpan.FromMinutes(5));
        _ = issuer.Issue(
            "ownership-list-task-03", "ios-core-0001", now.AddSeconds(3), TimeSpan.FromSeconds(1));
        var request = Request(
            BridgeV1Operation.OWNERSHIP_TRANSFER_DISCOVER,
            new Dictionary<string, string>());

        var response = await CreateDispatcher(_payload).DispatchAsync(request, now.AddSeconds(5));

        Assert.Equal("ACCEPTED", response.Status);
        Assert.Null(response.ErrorCode);
        Assert.Equal(request.RequestId, response.RequestId);
        Assert.Equal(
            new[] { first.Payload.TransferId },
            JsonSerializer.Deserialize<string[]>(response.Result["transferIds"]));
        Assert.Single(response.Result);
    }

    [Fact]
    public async Task UnapprovedAgentHeartbeatDoesNotRefreshLivenessOrRevealProfile()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterPendingAgent("agent-0001", "device-0001", now);

        var response = await CreateDispatcher(_payload).DispatchAsync(HeartbeatRequest(), now.AddSeconds(1));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("AGENT_NOT_OWNER_APPROVED", response.ErrorCode);
        Assert.Empty(response.Result);
        var agent = Assert.Single(_store.GetAgents(now.AddSeconds(2)));
        Assert.False(agent.OwnerApproved);
        Assert.False(agent.IsOnline);
    }

    [Fact]
    public async Task ClaimReturnsOnlyOwnerRegisteredCapabilitiesAndServerIssuedFence()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();

        var dispatcher = CreateDispatcher(_payload);
        var response = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        Assert.Equal("ACCEPTED", response.Status);
        Assert.Equal("true", response.Result["available"]);
        Assert.Equal("task-0001", response.Result["taskId"]);
        Assert.Equal("windows-core-0001", response.Result["ownerCoreId"]);
        Assert.Equal("1", response.Result["ownerGeneration"]);
        Assert.Equal("web.research", response.Result["taskType"]);
        Assert.Equal("idem-task-01", response.Result["idempotencyKey"]);
        Assert.Equal("payload://task-0001", response.Result["payloadRef"]);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(_payload)).ToLowerInvariant(), response.Result["payloadSha256"]);
        Assert.Equal("ReadOnly", response.Result["effectRisk"]);
        Assert.Equal(string.Empty, response.Result["approvalRef"]);
        Assert.True(long.Parse(response.Result["leaseExpiresAtEpochMs"]) > now.ToUnixTimeMilliseconds());
        Assert.Equal("RESEARCHER", response.Result["executor"]);
        Assert.Equal("web.research", Assert.Single(JsonSerializer.Deserialize<string[]>(response.Result["requiredCapabilities"])!));
        Assert.False(string.IsNullOrWhiteSpace(response.Result["leaseId"]));
        Assert.True(long.Parse(response.Result["fencingToken"]) > 0);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("task-0001"));
    }

    [Fact]
    public async Task ClaimRequiresAnExplicitTaskHandlerAllowlist()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var payload = new Dictionary<string, string>
        {
            ["capabilities"] = JsonSerializer.Serialize(new[] { "web.research" }),
            ["maxParallelTasks"] = "1"
        };

        var response = await CreateDispatcher(_payload).DispatchAsync(
            Request(BridgeV1Operation.DESKTOP_TASK_CLAIM, payload), now.AddSeconds(1));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("AGENT_TASK_TYPE_REQUIRED", response.ErrorCode);
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("task-0001"));
    }

    [Fact]
    public async Task ClaimDoesNotLeaseTaskTypesTheAgentCannotExecute()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();

        var response = await CreateDispatcher(_payload).DispatchAsync(
            ClaimRequest(["web.research"], 1, ["analysis.document"]), now.AddSeconds(1));

        Assert.Equal("ACCEPTED", response.Status);
        Assert.Equal("false", response.Result["available"]);
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("task-0001"));
    }

    [Fact]
    public async Task DeviceCannotEscalateCapabilitiesOrConcurrencyInClaimPayload()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();

        var response = await CreateDispatcher(_payload).DispatchAsync(
            ClaimRequest(["shell.execute"], 2), now.AddSeconds(1));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("AGENT_CAPABILITY_MISMATCH", response.ErrorCode);
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("task-0001"));
    }

    [Fact]
    public async Task CurrentFencedResultIsStoredEncryptedAndDuplicateDeliveryIsIdempotent()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var leaseId = claim.Result["leaseId"];
        var fencingToken = long.Parse(claim.Result["fencingToken"]);

        var completed = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", leaseId, fencingToken, "private result payload"), now.AddSeconds(2));
        var duplicate = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", leaseId, fencingToken, "private result payload"), now.AddSeconds(3));

        Assert.Equal("SUCCEEDED", completed.Status);
        Assert.Equal("SUCCEEDED", duplicate.Status);
        Assert.Equal(HubTaskState.Succeeded, _store.GetTaskState("task-0001"));
        Assert.Equal("private result payload", _store.GetTaskResult("task-0001")!.Result);

        // Release pooled SQLite handles before inspecting the database file bytes.
        SqliteConnection.ClearAllPools();
        var databaseFiles = Directory.GetFiles(Path.GetDirectoryName(_databasePath)!, Path.GetFileName(_databasePath) + "*");
        Assert.DoesNotContain(databaseFiles, file =>
            Encoding.UTF8.GetString(File.ReadAllBytes(file)).Contains("private result payload", StringComparison.Ordinal));
    }

    [Fact]
    public async Task RunningProgressReturnsTheServerRenewedLeaseExpiry()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        var progress = await dispatcher.DispatchAsync(
            ResultRequest("RUNNING", claim.Result["leaseId"], long.Parse(claim.Result["fencingToken"]), "null"),
            now.AddSeconds(2));

        Assert.Equal("SUCCEEDED", progress.Status);
        Assert.True(long.Parse(progress.Result["leaseExpiresAtEpochMs"]) >
            now.AddSeconds(2).ToUnixTimeMilliseconds());
    }

    [Fact]
    public async Task StaleFencingTokenCannotCompleteCurrentLease()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        var response = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", claim.Result["leaseId"], long.Parse(claim.Result["fencingToken"]) + 1, "stale"),
            now.AddSeconds(2));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("LEASE_FENCING_REJECTED", response.ErrorCode);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("task-0001"));
        Assert.Null(_store.GetTaskResult("task-0001"));
    }

    [Fact]
    public async Task StaleOwnerGenerationCannotCompleteCurrentLease()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        var response = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", claim.Result["leaseId"],
                long.Parse(claim.Result["fencingToken"]), "stale owner", ownerGeneration: 2),
            now.AddSeconds(2));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("OWNER_GENERATION_REJECTED", response.ErrorCode);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("task-0001"));
        Assert.Null(_store.GetTaskResult("task-0001"));
        Assert.DoesNotContain(_store.GetTaskHistory("task-0001"),
            item => item.EventCode is "TASK_SUCCEEDED" or "TASK_FAILED");
    }

    [Fact]
    public async Task PayloadHashMismatchFailsClosedAndQuarantinesClaimedTask()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var altered = Encoding.UTF8.GetBytes("{\"instruction\":\"tampered\"}");

        var response = await CreateDispatcher(altered).DispatchAsync(
            ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("TASK_PAYLOAD_HASH_MISMATCH", response.ErrorCode);
        Assert.Equal(HubTaskState.Failed, _store.GetTaskState("task-0001"));
        Assert.Equal("TASK_PAYLOAD_HASH_MISMATCH", _store.GetTaskResult("task-0001")!.ErrorCode);
    }

    [Theory]
    [InlineData("none")]
    [InlineData("")]
    [InlineData("null")]
    [InlineData("Готово: \"проверено\"")]
    public async Task JsonEncodedResultPreservesLiteralValuesAndAckMatchesCommittedRequest(string text)
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var encoded = JsonSerializer.Serialize(text);
        var request = ResultRequest("SUCCEEDED", claim.Result["leaseId"], long.Parse(claim.Result["fencingToken"]), encoded);
        var fields = new Dictionary<string, string>(request.Payload) { ["resultEncoding"] = "json" };
        request = request with { Payload = fields };
        var completed = await dispatcher.DispatchAsync(request, now.AddSeconds(2));
        var duplicate = await dispatcher.DispatchAsync(request, now.AddSeconds(3));

        Assert.Equal("SUCCEEDED", completed.Status);
        Assert.Equal("SUCCEEDED", duplicate.Status);
        Assert.Equal(text, _store.GetTaskResult("task-0001")!.Result);
        Assert.Equal("task-0001", completed.Result["taskId"]);
        Assert.Equal(claim.Result["leaseId"], completed.Result["leaseId"]);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(encoded))).ToLowerInvariant(),
            completed.Result["resultSha256"]);
    }

    [Fact]
    public async Task ArtifactIsBoundToActiveLeaseEncryptedAndAcknowledged()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var artifactBytes = Encoding.UTF8.GetBytes("{\"status\":\"healthy\",\"secrets\":\"redacted\"}");
        var response = await dispatcher.DispatchAsync(
            ArtifactRequest(artifactBytes, claim, now.AddSeconds(1)), now.AddSeconds(2));

        Assert.Equal("SUCCEEDED", response.Status);
        Assert.Equal("true", response.Result["accepted"]);
        Assert.Equal("false", response.Result["duplicate"]);
        Assert.Equal("artifact:diagnostics:0001", response.Result["artifactId"]);
        Assert.Equal("task-0001", response.Result["taskId"]);
        Assert.Equal(claim.Result["leaseId"], response.Result["leaseId"]);
        Assert.Equal(claim.Result["fencingToken"], response.Result["fencingToken"]);

        var restartedStore = new DesktopHubStore(_databasePath);
        var stored = Assert.IsType<HubTaskArtifact>(restartedStore.GetTaskArtifact("artifact:diagnostics:0001"));
        Assert.Equal("glafira.agent.diagnostics.snapshot", stored.Metadata.SchemaId);
        Assert.Equal("REDACTED", stored.Metadata.RedactionStatus);
        Assert.Equal(artifactBytes, stored.Payload);

        using var connection = new SqliteConnection("Data Source=" + _databasePath);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT payload_ciphertext FROM TaskArtifacts WHERE artifact_id = $artifact;";
        command.Parameters.AddWithValue("$artifact", "artifact:diagnostics:0001");
        var ciphertext = Assert.IsType<byte[]>(command.ExecuteScalar());
        Assert.NotEqual(artifactBytes, ciphertext);
        Assert.DoesNotContain(Convert.ToHexString(artifactBytes), Convert.ToHexString(ciphertext));
        CryptographicOperations.ZeroMemory(stored.Payload);
        CryptographicOperations.ZeroMemory(artifactBytes);
        CryptographicOperations.ZeroMemory(ciphertext);
    }

    [Fact]
    public async Task ExactArtifactRetryAfterTaskCompletionReturnsDurableDuplicateAck()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var artifactBytes = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var artifactRequest = ArtifactRequest(artifactBytes, claim, now.AddSeconds(1));

        var first = await dispatcher.DispatchAsync(artifactRequest, now.AddSeconds(2));
        var completion = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", claim.Result["leaseId"], long.Parse(claim.Result["fencingToken"]), "done"),
            now.AddSeconds(3));
        var retry = await dispatcher.DispatchAsync(artifactRequest with { RequestId = "request-retry-0001" }, now.AddSeconds(4));

        Assert.Equal("SUCCEEDED", first.Status);
        Assert.Equal("SUCCEEDED", completion.Status);
        Assert.Equal("SUCCEEDED", retry.Status);
        Assert.Equal("true", retry.Result["accepted"]);
        Assert.Equal("true", retry.Result["duplicate"]);
        Assert.Equal(first.Result["sha256"], retry.Result["sha256"]);
        CryptographicOperations.ZeroMemory(artifactBytes);
    }

    [Fact]
    public async Task ArtifactBackedResultRequiresExactArtifactBeforeTerminalCompletion()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var artifactBytes = Encoding.UTF8.GetBytes("{\"status\":\"healthy\",\"secrets\":\"redacted\"}");
        var digest = Convert.ToHexString(SHA256.HashData(artifactBytes)).ToLowerInvariant();
        var resultRequest = AgentArtifactResultRequest(
            claim.Result["leaseId"], long.Parse(claim.Result["fencingToken"]), digest);

        var missing = await dispatcher.DispatchAsync(resultRequest, now.AddSeconds(2));

        Assert.Equal("FAILED", missing.Status);
        Assert.Equal("TASK_ARTIFACT_REQUIRED", missing.ErrorCode);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("task-0001"));
        Assert.Null(_store.GetTaskResult("task-0001"));

        var artifact = await dispatcher.DispatchAsync(
            ArtifactRequest(artifactBytes, claim, now.AddSeconds(2)), now.AddSeconds(3));
        var completed = await dispatcher.DispatchAsync(resultRequest, now.AddSeconds(4));
        var duplicate = await dispatcher.DispatchAsync(
            resultRequest with { RequestId = "request-result-retry-0001" }, now.AddSeconds(5));

        Assert.Equal("SUCCEEDED", artifact.Status);
        Assert.Equal("SUCCEEDED", completed.Status);
        Assert.Equal("SUCCEEDED", duplicate.Status);
        Assert.Equal(HubTaskState.Succeeded, _store.GetTaskState("task-0001"));
        Assert.NotNull(_store.GetTaskResult("task-0001"));
        CryptographicOperations.ZeroMemory(artifactBytes);
    }

    [Fact]
    public async Task ArtifactBackedResultRejectsMismatchedDigestWithoutCompletingTask()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var artifactBytes = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");

        var artifact = await dispatcher.DispatchAsync(
            ArtifactRequest(artifactBytes, claim, now.AddSeconds(1)), now.AddSeconds(2));
        var response = await dispatcher.DispatchAsync(
            AgentArtifactResultRequest(
                claim.Result["leaseId"], long.Parse(claim.Result["fencingToken"]), new string('0', 64)),
            now.AddSeconds(3));

        Assert.Equal("SUCCEEDED", artifact.Status);
        Assert.Equal("FAILED", response.Status);
        Assert.Equal("TASK_ARTIFACT_REQUIRED", response.ErrorCode);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("task-0001"));
        Assert.Null(_store.GetTaskResult("task-0001"));
        CryptographicOperations.ZeroMemory(artifactBytes);
    }

    [Fact]
    public async Task ArtifactIdConflictCannotOverwriteCommittedPayload()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var original = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var replacement = Encoding.UTF8.GetBytes("{\"status\":\"tampered\"}");

        var first = await dispatcher.DispatchAsync(
            ArtifactRequest(original, claim, now.AddSeconds(1)), now.AddSeconds(2));
        var conflict = await dispatcher.DispatchAsync(
            ArtifactRequest(replacement, claim, now.AddSeconds(1)), now.AddSeconds(3));

        Assert.Equal("SUCCEEDED", first.Status);
        Assert.Equal("FAILED", conflict.Status);
        Assert.Equal("ARTIFACT_ID_CONFLICT", conflict.ErrorCode);
        var stored = Assert.IsType<HubTaskArtifact>(_store.GetTaskArtifact("artifact:diagnostics:0001"));
        Assert.Equal(original, stored.Payload);
        CryptographicOperations.ZeroMemory(stored.Payload);
        CryptographicOperations.ZeroMemory(original);
        CryptographicOperations.ZeroMemory(replacement);
    }

    [Fact]
    public async Task ArtifactRejectsWrongOwnerFenceAndDoesNotPersist()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var artifactBytes = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var request = ArtifactRequest(artifactBytes, claim, now.AddSeconds(1));
        var altered = new Dictionary<string, string>(request.Payload)
        {
            ["ownerGeneration"] = "2",
            ["fencingToken"] = (long.Parse(claim.Result["fencingToken"]) + 1).ToString()
        };

        var response = await dispatcher.DispatchAsync(request with { Payload = altered }, now.AddSeconds(2));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("LEASE_FENCING_REJECTED", response.ErrorCode);
        Assert.Null(_store.GetTaskArtifact("artifact:diagnostics:0001"));
        CryptographicOperations.ZeroMemory(artifactBytes);
    }

    [Fact]
    public async Task ArtifactRejectsDigestMismatchAndNonObjectJson()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var arrayBytes = Encoding.UTF8.GetBytes("[\"not-an-object\"]");
        var nonObject = ArtifactRequest(arrayBytes, claim, now.AddSeconds(1));
        var validBytes = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var digestRequest = ArtifactRequest(validBytes, claim, now.AddSeconds(1));
        var digestFields = new Dictionary<string, string>(digestRequest.Payload)
        {
            ["sha256"] = new string('0', 64)
        };

        var nonObjectResponse = await dispatcher.DispatchAsync(nonObject, now.AddSeconds(2));
        var digestResponse = await dispatcher.DispatchAsync(
            digestRequest with { Payload = digestFields }, now.AddSeconds(3));

        Assert.Equal("INVALID_TASK_ARTIFACT", nonObjectResponse.ErrorCode);
        Assert.Equal("INVALID_TASK_ARTIFACT", digestResponse.ErrorCode);
        Assert.Null(_store.GetTaskArtifact("artifact:diagnostics:0001"));
        CryptographicOperations.ZeroMemory(arrayBytes);
        CryptographicOperations.ZeroMemory(validBytes);
    }

    private BridgeV1TaskOperationDispatcher CreateDispatcher(byte[] payload) =>
        new(_store, new FakePayloadResolver(payload));

    private void RegisterAgent(DateTimeOffset now, IReadOnlyCollection<string> capabilities, int parallel) =>
        _store.RegisterAgent(new HubAgentRegistration(
            "agent-0001", "device-0001", capabilities, parallel, true), now);

    private void EnqueueTask()
    {
        _store.Enqueue(new HubTaskRequest(
            "task-0001", "project-0001", "windows-core-0001", 1,
            "idem-task-01", "web.research",
            "payload://task-0001", Convert.ToHexString(SHA256.HashData(_payload)).ToLowerInvariant(),
            ["web.research"], [], HubTaskEffectRisk.ReadOnly, null, 5), DateTimeOffset.UtcNow);
    }

    private static BridgeV1SignedRequest HeartbeatRequest() =>
        Request(BridgeV1Operation.DESKTOP_AGENT_HEARTBEAT, new Dictionary<string, string>());

    private static BridgeV1SignedRequest ClaimRequest(
        IReadOnlyCollection<string> capabilities, int parallel,
        IReadOnlyCollection<string>? taskTypes = null) =>
        Request(BridgeV1Operation.DESKTOP_TASK_CLAIM, new Dictionary<string, string>
        {
            ["capabilities"] = JsonSerializer.Serialize(capabilities),
            ["maxParallelTasks"] = parallel.ToString(),
            ["taskTypes"] = JsonSerializer.Serialize(taskTypes ?? ["web.research"])
        });

    private static BridgeV1SignedRequest ResultRequest(
        string state, string leaseId, long fencingToken, string result,
        string ownerCoreId = "windows-core-0001", long ownerGeneration = 1) =>
        Request(BridgeV1Operation.DESKTOP_TASK_RESULT, new Dictionary<string, string>
        {
            ["taskId"] = "task-0001",
            ["ownerCoreId"] = ownerCoreId,
            ["ownerGeneration"] = ownerGeneration.ToString(),
            ["leaseId"] = leaseId,
            ["fencingToken"] = fencingToken.ToString(),
            ["state"] = state,
            ["progressPercent"] = state == "SUCCEEDED" ? "100" : "0",
            ["progressMessage"] = "completed",
            ["result"] = result,
            ["errorCode"] = "none"
        });

    private static BridgeV1SignedRequest AgentArtifactResultRequest(
        string leaseId, long fencingToken, string artifactDigest)
    {
        var result = JsonSerializer.Serialize(new
        {
            TaskId = "task-0001",
            Outcome = "SUCCEEDED",
            ResultRef = "agent-artifact:artifact:diagnostics:0001",
            ResultSha256 = artifactDigest,
            ErrorCode = (string?)null
        });
        var encoded = JsonSerializer.Serialize(result);
        var request = ResultRequest("SUCCEEDED", leaseId, fencingToken, encoded);
        var fields = new Dictionary<string, string>(request.Payload)
        {
            ["resultEncoding"] = "json"
        };
        return request with { Payload = fields };
    }

    private static BridgeV1SignedRequest ArtifactRequest(
        byte[] artifactBytes,
        BridgeV1DispatchResponse claim,
        DateTimeOffset createdAt) =>
        Request(BridgeV1Operation.DESKTOP_TASK_ARTIFACT, new Dictionary<string, string>
        {
            ["contractVersion"] = "1",
            ["artifactId"] = "artifact:diagnostics:0001",
            ["taskId"] = "task-0001",
            ["ownerCoreId"] = claim.Result["ownerCoreId"],
            ["ownerGeneration"] = claim.Result["ownerGeneration"],
            ["leaseId"] = claim.Result["leaseId"],
            ["fencingToken"] = claim.Result["fencingToken"],
            ["schemaId"] = "glafira.agent.diagnostics.snapshot",
            ["schemaVersion"] = "1",
            ["contentType"] = "application/json",
            ["byteLength"] = artifactBytes.Length.ToString(),
            ["sha256"] = Convert.ToHexString(SHA256.HashData(artifactBytes)).ToLowerInvariant(),
            ["redactionStatus"] = "REDACTED",
            ["createdAtEpochMs"] = createdAt.ToUnixTimeMilliseconds().ToString(),
            ["payloadBase64"] = Convert.ToBase64String(artifactBytes)
        });

    private static BridgeV1SignedRequest Request(
        BridgeV1Operation operation, IReadOnlyDictionary<string, string> payload) =>
        new(1, "request-0001", "idem-0001", "device-0001", "project-0001",
            operation, 1_730_000_000_000, 1_730_000_060_000, payload, new string('0', 64));

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var file in Directory.GetFiles(
            Path.GetDirectoryName(_databasePath)!, Path.GetFileName(_databasePath) + "*"))
            File.Delete(file);
        CryptographicOperations.ZeroMemory(_payload);
    }

    private sealed class FakePayloadResolver(byte[] payload) : IHubTaskPayloadResolver
    {
        public ValueTask<byte[]?> ResolveAsync(string payloadRef, CancellationToken cancellationToken) =>
            ValueTask.FromResult<byte[]?>(payload.ToArray());
    }
}
