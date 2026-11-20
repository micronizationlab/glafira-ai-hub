using System.Security.Cryptography;
using System.Text;
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
    public async Task AdmitsGoldenVectorAndClaimsSignedIdempotencyKey()
    {
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var request = Sign(GoldenRequest(signature: new string('0', 64)));

        var result = await authenticator.AuthenticateAsync(
            request, DateTimeOffset.FromUnixTimeMilliseconds(request.IssuedAtEpochMs));

        Assert.True(result.Accepted);
        Assert.Equal(1, replay.Claims);
        Assert.Equal(
            Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("idem-0001"))).ToLowerInvariant(),
            replay.LastNonce);
        Assert.Equal(64, replay.LastNonce!.Length);
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
    public async Task RejectsReplayedIdempotencyKey()
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
