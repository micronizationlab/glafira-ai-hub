using System.Security.Cryptography;
using System.Text;
using GLAFIRA.Contracts.V1.Runtime;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1RequestAuthenticatorTests
{
    private static readonly byte[] Secret = Enumerable.Range(0, 32).Select(value => (byte)value).ToArray();

    [Fact]
    public void CanonicalizationAndHmacMatchSharedKotlinSwiftUnicodeGoldenVector()
    {
        var request = GoldenRequest(signature: new string('0', 64));
        var canonical = BridgeV1RequestAuthenticator.BuildCanonicalRequest(request);
        var signature = HMACSHA256.HashData(Secret, Encoding.UTF8.GetBytes(canonical));

        Assert.Equal(
            "1:112:request-00019:idem-000111:device-000112:project-000119:CONVERSATION_APPEND13:173000000000013:173000030000014:conversationId9:chat-000111:messageText9:Привет 🌍",
            canonical);
        Assert.Equal(
            "bb9e7f042e94f5910e877b4f660f20807a33c83cabe9afa47c3820413465cf91",
            Convert.ToHexString(signature).ToLowerInvariant());
    }

    [Fact]
    public async Task AdmitsGoldenVectorAndClaimsRequestIdAsTransportNonce()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var request = Sign(GoldenRequest(signature: new string('0', 64)));

        var result = await authenticator.AuthenticateAsync(
            request, DateTimeOffset.FromUnixTimeMilliseconds(request.IssuedAtEpochMs));

        Assert.True(result.Accepted);
        Assert.Equal(1, replay.Claims);
        Assert.Equal(
            Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("request-0001"))).ToLowerInvariant(),
            replay.LastNonce);
        Assert.Equal(64, replay.LastNonce!.Length);
    }

    [Fact]
    public async Task AdmitsAgentHeartbeatOnlyWithDesktopTaskClaimScopeAndEmptyPayload()
    {
        var now = DateTimeOffset.UtcNow;
        var replay = new FakeReplayGuard();
        var authenticator = new BridgeV1RequestAuthenticator(
            new FakeCredentialResolver(new HubRequestCredential(
                "device-0001", Secret, new HashSet<string> { "desktop.task.claim" })), replay);
        var request = Sign(new BridgeV1SignedRequest(
            1, "request-heartbeat-01", "idem-heartbeat-01", "device-0001", "default",
            BridgeV1Operation.DESKTOP_AGENT_HEARTBEAT,
            now.AddSeconds(-1).ToUnixTimeMilliseconds(),
            now.AddMinutes(1).ToUnixTimeMilliseconds(),
            new Dictionary<string, string>(),
            new string('0', 64)));

        var accepted = await authenticator.AuthenticateAsync(request, now);

        Assert.True(accepted.Accepted);
        Assert.Equal(1, replay.Claims);
    }

    [Fact]
    public async Task OwnershipTransferReadRequiresDedicatedScopeAndExactTransferId()
    {
        var now = DateTimeOffset.UtcNow;
        var request = new BridgeV1SignedRequest(
            1, "request-owner-read-01", "idem-owner-read-01", "device-0001", "project-0001",
            BridgeV1Operation.OWNERSHIP_TRANSFER_READ,
            now.AddSeconds(-1).ToUnixTimeMilliseconds(), now.AddMinutes(1).ToUnixTimeMilliseconds(),
            new Dictionary<string, string> { ["transferId"] = "transfer-owner-0001" },
            new string('0', 64));
        var acceptedReplay = new FakeReplayGuard();
        var accepted = new BridgeV1RequestAuthenticator(
            new FakeCredentialResolver(new HubRequestCredential(
                "device-0001", Secret, new HashSet<string> { "ownership.read" })), acceptedReplay);

        Assert.True((await accepted.AuthenticateAsync(Sign(request), now)).Accepted);

        var deniedReplay = new FakeReplayGuard();
        var denied = new BridgeV1RequestAuthenticator(
            new FakeCredentialResolver(new HubRequestCredential(
                "device-0001", Secret, new HashSet<string> { "project.read" })), deniedReplay);
        var denial = await denied.AuthenticateAsync(Sign(request with
        {
            RequestId = "request-owner-read-02"
        }), now);
        Assert.Equal(BridgeV1AdmissionFailure.ScopeDenied, denial.Failure);
        Assert.Equal(0, deniedReplay.Claims);
    }

    [Fact]
    public async Task OwnershipTransferDiscoveryRequiresReadScopeAndEmptyPayload()
    {
        var now = DateTimeOffset.UtcNow;
        var request = new BridgeV1SignedRequest(
            1, "request-owner-list-01", "idem-owner-list-01", "device-0001", "project-0001",
            BridgeV1Operation.OWNERSHIP_TRANSFER_DISCOVER,
            now.AddSeconds(-1).ToUnixTimeMilliseconds(), now.AddMinutes(1).ToUnixTimeMilliseconds(),
            new Dictionary<string, string>(), new string('0', 64));
        var acceptedReplay = new FakeReplayGuard();
        var accepted = new BridgeV1RequestAuthenticator(
            new FakeCredentialResolver(new HubRequestCredential(
                "device-0001", Secret, new HashSet<string> { "ownership.read" })), acceptedReplay);

        Assert.True((await accepted.AuthenticateAsync(Sign(request), now)).Accepted);

        var unexpectedPayload = await accepted.AuthenticateAsync(Sign(request with
        {
            RequestId = "request-owner-list-02",
            Payload = new Dictionary<string, string> { ["transferId"] = "transfer-owner-0001" }
        }), now);
        Assert.Equal(BridgeV1AdmissionFailure.InvalidEnvelope, unexpectedPayload.Failure);

        var deniedReplay = new FakeReplayGuard();
        var denied = new BridgeV1RequestAuthenticator(
            new FakeCredentialResolver(new HubRequestCredential(
                "device-0001", Secret, new HashSet<string> { "project.read" })), deniedReplay);
        var denial = await denied.AuthenticateAsync(Sign(request with
        {
            RequestId = "request-owner-list-03"
        }), now);
        Assert.Equal(BridgeV1AdmissionFailure.ScopeDenied, denial.Failure);
        Assert.Equal(0, deniedReplay.Claims);
    }

    [Fact]
    public async Task RejectsPayloadTamperingBeforeReplayClaim()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var signed = Sign(GoldenRequest(signature: new string('0', 64)));
        var changed = signed with
        {
            Payload = new Dictionary<string, string>(signed.Payload)
            {
                ["messageText"] = "Changed"
            }
        };

        var result = await authenticator.AuthenticateAsync(
            changed, DateTimeOffset.FromUnixTimeMilliseconds(changed.IssuedAtEpochMs));

        Assert.Equal(BridgeV1AdmissionFailure.SignatureInvalid, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Fact]
    public async Task RejectsScopeEscalationBeforeReplayClaim()
    {
        var replay = new FakeReplayGuard();
        var authenticator = new BridgeV1RequestAuthenticator(
            new FakeCredentialResolver(new HubRequestCredential(
                "device-0001", Secret, new HashSet<string> { "project.read" })), replay);
        var request = Sign(GoldenRequest(signature: new string('0', 64)));

        var result = await authenticator.AuthenticateAsync(
            request, DateTimeOffset.FromUnixTimeMilliseconds(request.IssuedAtEpochMs));

        Assert.Equal(BridgeV1AdmissionFailure.ScopeDenied, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Fact]
    public async Task RejectsExpiredRequest()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var request = Sign(GoldenRequest(signature: new string('0', 64)));
        var now = DateTimeOffset.FromUnixTimeMilliseconds(request.ExpiresAtEpochMs + 1);

        var result = await authenticator.AuthenticateAsync(request, now);

        Assert.Equal(BridgeV1AdmissionFailure.RequestExpired, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Fact]
    public async Task RejectsExactTransportRequestReplay()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var request = Sign(GoldenRequest(signature: new string('0', 64)));
        var now = DateTimeOffset.FromUnixTimeMilliseconds(request.IssuedAtEpochMs);

        Assert.True((await authenticator.AuthenticateAsync(request, now)).Accepted);
        Assert.Equal(BridgeV1AdmissionFailure.ReplayDetected,
            (await authenticator.AuthenticateAsync(request, now)).Failure);
    }

    [Fact]
    public async Task AllowsFreshTransportRequestForSameIdempotentOperationAfterLostAck()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var first = Sign(GoldenRequest(signature: new string('0', 64)));
        var retry = Sign(first with { RequestId = "request-0002" });
        var now = DateTimeOffset.FromUnixTimeMilliseconds(first.IssuedAtEpochMs);

        Assert.Equal(first.IdempotencyKey, retry.IdempotencyKey);
        Assert.NotEqual(first.RequestId, retry.RequestId);
        Assert.True((await authenticator.AuthenticateAsync(first, now)).Accepted);
        Assert.True((await authenticator.AuthenticateAsync(retry, now)).Accepted);
        Assert.Equal(2, replay.Claims);
    }

    [Fact]
    public async Task ShortBridgeIdempotencyKeyWorksWithDurableSqliteReplayGuard()
    {
        var databasePath = Path.Combine(
            Path.GetTempPath(), "glafira-bridge-v1-" + Guid.NewGuid().ToString("N") + ".db");
        try
        {
            var now = DateTimeOffset.UtcNow;
            var request = Sign(GoldenRequest(signature: new string('0', 64)) with
            {
                IssuedAtEpochMs = now.AddSeconds(-1).ToUnixTimeMilliseconds(),
                ExpiresAtEpochMs = now.AddMinutes(4).ToUnixTimeMilliseconds()
            });
            var authenticator = new BridgeV1RequestAuthenticator(
                new FakeCredentialResolver(new HubRequestCredential(
                    "device-0001", Secret, new HashSet<string> { "conversation.append" })),
                new SqliteHubReplayGuard(databasePath));

            Assert.True((await authenticator.AuthenticateAsync(request, now)).Accepted);
            var retry = Sign(request with { RequestId = "request-0002" });
            Assert.True((await authenticator.AuthenticateAsync(retry, now)).Accepted);
            Assert.Equal(BridgeV1AdmissionFailure.ReplayDetected,
                (await authenticator.AuthenticateAsync(request, now)).Failure);
        }
        finally
        {
            SqliteConnection.ClearAllPools();
            foreach (var file in new[] { databasePath, databasePath + "-wal", databasePath + "-shm" })
            {
                if (File.Exists(file)) File.Delete(file);
            }
        }
    }

    [Fact]
    public async Task RejectsUnknownPayloadFields()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var request = GoldenRequest(signature: new string('0', 64)) with
        {
            Payload = new Dictionary<string, string>
            {
                ["conversationId"] = "chat-0001",
                ["messageText"] = "Привет 🌍",
                ["shellCommand"] = "whoami"
            }
        };

        var result = await authenticator.AuthenticateAsync(
            request, DateTimeOffset.FromUnixTimeMilliseconds(request.IssuedAtEpochMs));

        Assert.Equal(BridgeV1AdmissionFailure.InvalidEnvelope, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Fact]
    public async Task AcceptsFencedDesktopTaskResultWithCurrentLeaseIdentity()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateTaskResultAuthenticator(replay);
        var request = Sign(TaskResultRequest(ValidTaskResultPayload()));

        var result = await authenticator.AuthenticateAsync(
            request, DateTimeOffset.FromUnixTimeMilliseconds(request.IssuedAtEpochMs));

        Assert.True(result.Accepted);
        Assert.Equal(1, replay.Claims);
    }

    [Fact]
    public async Task RejectsDesktopTaskResultWithoutLeaseIdentityBeforeReplayClaim()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateTaskResultAuthenticator(replay);
        var payload = ValidTaskResultPayload();
        payload.Remove("leaseId");

        var result = await authenticator.AuthenticateAsync(
            Sign(TaskResultRequest(payload)),
            DateTimeOffset.UtcNow);

        Assert.Equal(BridgeV1AdmissionFailure.InvalidEnvelope, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Theory]
    [InlineData("0")]
    [InlineData("-1")]
    [InlineData("not-a-number")]
    public async Task RejectsNonPositiveOrMalformedDesktopTaskFenceBeforeReplayClaim(string fence)
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateTaskResultAuthenticator(replay);
        var payload = ValidTaskResultPayload();
        payload["fencingToken"] = fence;

        var result = await authenticator.AuthenticateAsync(
            Sign(TaskResultRequest(payload)),
            DateTimeOffset.UtcNow);

        Assert.Equal(BridgeV1AdmissionFailure.InvalidEnvelope, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Theory]
    [InlineData("windows-core-0001", "0")]
    [InlineData("windows-core-0001", "not-a-number")]
    [InlineData("bad owner", "1")]
    [InlineData("windows-ядро", "1")]
    public async Task RejectsMalformedTaskResultOwnershipBeforeReplayClaim(
        string ownerCoreId, string ownerGeneration)
    {
        var replay = new FakeReplayGuard();
        var payload = ValidTaskResultPayload();
        payload["ownerCoreId"] = ownerCoreId;
        payload["ownerGeneration"] = ownerGeneration;

        var result = await CreateTaskResultAuthenticator(replay).AuthenticateAsync(
            Sign(TaskResultRequest(payload)), DateTimeOffset.UtcNow);

        Assert.Equal(BridgeV1AdmissionFailure.InvalidEnvelope, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Fact]
    public async Task ArtifactUploadRequiresTaskResultScopeAndAcceptsBoundedSignedEnvelope()
    {
        var now = DateTimeOffset.UtcNow;
        var replay = new FakeReplayGuard();
        var authenticator = CreateTaskResultAuthenticator(replay);
        var request = Sign(TaskArtifactRequest(ValidTaskArtifactPayload(now), now));

        var accepted = await authenticator.AuthenticateAsync(request, now);

        Assert.True(accepted.Accepted);
        Assert.Equal(1, replay.Claims);

        var wrongScopeReplay = new FakeReplayGuard();
        var wrongScope = new BridgeV1RequestAuthenticator(
            new FakeCredentialResolver(new HubRequestCredential(
                "device-0001", Secret, new HashSet<string> { "desktop.task.claim" })), wrongScopeReplay);
        var denied = await wrongScope.AuthenticateAsync(
            Sign(request with { RequestId = "request-task-artifact-02" }), now);
        Assert.Equal(BridgeV1AdmissionFailure.ScopeDenied, denied.Failure);
        Assert.Equal(0, wrongScopeReplay.Claims);
    }

    [Fact]
    public async Task ArtifactEnvelopeRejectsOversizeOrMissingBindingBeforeReplayClaim()
    {
        var now = DateTimeOffset.UtcNow;
        var replay = new FakeReplayGuard();
        var payload = ValidTaskArtifactPayload(now);
        payload["payloadBase64"] = new string('A', 44_001);
        var oversize = await CreateTaskResultAuthenticator(replay).AuthenticateAsync(
            Sign(TaskArtifactRequest(payload, now)), now);
        Assert.Equal(BridgeV1AdmissionFailure.InvalidEnvelope, oversize.Failure);
        Assert.Equal(0, replay.Claims);

        payload = ValidTaskArtifactPayload(now);
        payload.Remove("leaseId");
        var missing = await CreateTaskResultAuthenticator(replay).AuthenticateAsync(
            Sign(TaskArtifactRequest(payload, now) with { RequestId = "request-task-artifact-03" }), now);
        Assert.Equal(BridgeV1AdmissionFailure.InvalidEnvelope, missing.Failure);
        Assert.Equal(0, replay.Claims);
    }

    private static BridgeV1RequestAuthenticator CreateTaskResultAuthenticator(FakeReplayGuard replay) =>
        new(new FakeCredentialResolver(new HubRequestCredential(
            "device-0001", Secret, new HashSet<string> { "desktop.task.result" })), replay);

    private static Dictionary<string, string> ValidTaskResultPayload()
    {
        var result = "done";
        var payload = new Dictionary<string, string>
        {
        ["taskId"] = "task-0001",
        ["ownerCoreId"] = "windows-core-0001",
        ["ownerGeneration"] = "1",
        ["leaseId"] = "lease-0001",
        ["fencingToken"] = "42",
        ["state"] = "SUCCEEDED",
        ["progressPercent"] = "100",
        ["progressMessage"] = "completed",
        ["result"] = result,
        ["errorCode"] = "none"
        };
        payload["canonicalResult"] = CanonicalTaskContractJson.Serialize(new TaskResultEnvelopeV1(
            1, "task-0001", "agent-0001", "lease-0001", 42, 1, "SUCCEEDED",
            DateTimeOffset.UtcNow, "hub-task-result:task-0001",
            Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(result))).ToLowerInvariant(),
            null, false, false));
        return payload;
    }

    private static Dictionary<string, string> ValidTaskArtifactPayload(DateTimeOffset now)
    {
        var bytes = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        return new Dictionary<string, string>
        {
            ["contractVersion"] = "1",
            ["artifactId"] = "artifact:diagnostics:0001",
            ["taskId"] = "task_1",
            ["ownerCoreId"] = "windows-core-0001",
            ["ownerGeneration"] = "1",
            ["leaseId"] = "lease-0001",
            ["fencingToken"] = "42",
            ["schemaId"] = "glafira.agent.diagnostics.snapshot",
            ["schemaVersion"] = "1",
            ["contentType"] = "application/json",
            ["byteLength"] = bytes.Length.ToString(),
            ["sha256"] = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(),
            ["redactionStatus"] = "REDACTED",
            ["createdAtEpochMs"] = now.ToUnixTimeMilliseconds().ToString(),
            ["payloadBase64"] = Convert.ToBase64String(bytes)
        };
    }

    private static BridgeV1SignedRequest TaskResultRequest(IReadOnlyDictionary<string, string> payload)
    {
        var now = DateTimeOffset.UtcNow;
        return new BridgeV1SignedRequest(
            1,
            "request-task-result-01",
            "idem-task-result-01",
            "device-0001",
            "default",
            BridgeV1Operation.DESKTOP_TASK_RESULT,
            now.AddSeconds(-1).ToUnixTimeMilliseconds(),
            now.AddMinutes(1).ToUnixTimeMilliseconds(),
            payload,
            new string('0', 64));
    }

    private static BridgeV1SignedRequest TaskArtifactRequest(
        IReadOnlyDictionary<string, string> payload,
        DateTimeOffset now) =>
        new(
            1,
            "request-task-artifact-01",
            "idem-task-artifact-01",
            "device-0001",
            "default",
            BridgeV1Operation.DESKTOP_TASK_ARTIFACT,
            now.AddSeconds(-1).ToUnixTimeMilliseconds(),
            now.AddMinutes(1).ToUnixTimeMilliseconds(),
            payload,
            new string('0', 64));

    private static BridgeV1RequestAuthenticator CreateAuthenticator(FakeReplayGuard replay) =>
        new(new FakeCredentialResolver(new HubRequestCredential(
            "device-0001", Secret, new HashSet<string> { "conversation.append" })), replay);

    private static BridgeV1SignedRequest GoldenRequest(string signature) =>
        new(
            1,
            "request-0001",
            "idem-0001",
            "device-0001",
            "project-0001",
            BridgeV1Operation.CONVERSATION_APPEND,
            1_730_000_000_000,
            1_730_000_300_000,
            new Dictionary<string, string>
            {
                ["messageText"] = "Привет 🌍",
                ["conversationId"] = "chat-0001"
            },
            signature);

    private static BridgeV1SignedRequest Sign(BridgeV1SignedRequest request)
    {
        var canonical = BridgeV1RequestAuthenticator.BuildCanonicalRequest(request);
        var signature = HMACSHA256.HashData(Secret, Encoding.UTF8.GetBytes(canonical));
        return request with { SignatureHex = Convert.ToHexString(signature).ToLowerInvariant() };
    }

    private sealed class FakeCredentialResolver(HubRequestCredential? credential) : IHubCredentialResolver
    {
        public ValueTask<HubRequestCredential?> ResolveAsync(string deviceId, CancellationToken cancellationToken) =>
            ValueTask.FromResult(credential?.DeviceId == deviceId ? credential : null);
    }

    private sealed class FakeReplayGuard : IHubReplayGuard
    {
        private readonly HashSet<string> _claimed = [];
        public int Claims { get; private set; }
        public string? LastNonce { get; private set; }

        public ValueTask<bool> TryClaimAsync(
            string deviceId, string nonce, DateTimeOffset expiresAt, CancellationToken cancellationToken)
        {
            Claims++;
            LastNonce = nonce;
            return ValueTask.FromResult(_claimed.Add($"{deviceId}:{nonce}"));
        }
    }
}
