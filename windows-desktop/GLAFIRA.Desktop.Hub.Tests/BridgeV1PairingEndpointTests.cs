using System.Security.Cryptography;
using System.Text.Json;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1PairingEndpointTests : IDisposable
{
    private const string HubId = "desktop-hub-01";
    private const string TlsPin = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static readonly Uri LocalEndpoint = new("https://192.168.10.5:8443/bridge/v1");
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-pair-endpoint-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly MutableTimeProvider _clock = new(DateTimeOffset.FromUnixTimeSeconds(1_800_000_000));
    private readonly TestDataProtector _protector = new();

    [Fact]
    public async Task ReturnsCanonicalCredentialBundleAndRegistersItOnHub()
    {
        var store = CreateStore();
        var hubStore = CreateHubStore();
        var endpoint = CreateEndpoint(store, hubStore);
        var ticket = store.Create(
            ["desktop.task.result", "desktop.task.claim"], TimeSpan.FromMinutes(2));

        var response = await endpoint.RedeemAsync(Request(ticket), LocalEndpoint);

        Assert.Equal("ENROLLED", response.Status);
        Assert.Null(response.ErrorCode);
        Assert.Equal("agent-01", response.AgentId);
        Assert.Equal(HubId, response.DesktopHubId);
        Assert.Equal(LocalEndpoint.AbsoluteUri, response.Endpoint);
        Assert.Equal(TlsPin, response.TlsCertificateSha256);
        Assert.Equal(["desktop.task.claim", "desktop.task.result"], response.Scopes);
        Assert.Equal(_clock.GetUtcNow().ToUnixTimeMilliseconds(), response.EnrolledAtEpochMs);
        Assert.Equal(_clock.GetUtcNow().AddDays(30).ToUnixTimeMilliseconds(), response.ExpiresAtEpochMs);
        var secret = Convert.FromBase64String(Assert.IsType<string>(response.SharedSecretBase64));
        Assert.Equal(32, secret.Length);

        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        var credential = await resolver.ResolveAsync("agent-01", default);
        Assert.NotNull(credential);
        Assert.Equal(secret, credential.HmacSecret);
        CryptographicOperations.ZeroMemory(secret);
        CryptographicOperations.ZeroMemory(credential.HmacSecret);

        var pending = Assert.Single(hubStore.GetAgents(_clock.GetUtcNow()));
        Assert.Equal("agent-01", pending.AgentId);
        Assert.False(pending.OwnerApproved);
        Assert.False(pending.IsOnline);
        Assert.Empty(pending.Capabilities);
        Assert.Null(hubStore.ClaimNext("agent-01", _clock.GetUtcNow().AddSeconds(1), TimeSpan.FromMinutes(1)));

        Assert.True(hubStore.ConfigureAgentApproval("agent-01", ["web.research"], 1));
        var approved = Assert.Single(hubStore.GetAgents(_clock.GetUtcNow()));
        Assert.True(approved.OwnerApproved);
        Assert.False(approved.IsOnline);
        Assert.True(hubStore.Heartbeat("agent-01", ["web.research"], 1, _clock.GetUtcNow().AddSeconds(1)));
        Assert.True(Assert.Single(hubStore.GetAgents(_clock.GetUtcNow().AddSeconds(2))).IsOnline);

        Assert.True(hubStore.RevokeAgent("agent-01", _clock.GetUtcNow().AddSeconds(3)));
        Assert.Null(await resolver.ResolveAsync("agent-01", default));
        var revoked = Assert.Single(hubStore.GetAgents(_clock.GetUtcNow().AddSeconds(4)));
        Assert.False(revoked.OwnerApproved);
        Assert.False(revoked.IsOnline);
    }

    [Fact]
    public async Task WrongCodeAndReplayHaveTheSameSecretFreeFailureShape()
    {
        var store = CreateStore();
        var hubStore = CreateHubStore();
        var endpoint = CreateEndpoint(store, hubStore);
        var ticket = store.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));
        var wrongCode = ticket.PairingCode[..42] + (ticket.PairingCode[42] == 'a' ? "b" : "a");

        var wrong = await endpoint.RedeemAsync(
            Request(ticket) with { PairingCode = wrongCode }, LocalEndpoint);
        var accepted = await endpoint.RedeemAsync(Request(ticket), LocalEndpoint);
        var replay = await endpoint.RedeemAsync(Request(ticket), LocalEndpoint);

        Assert.Equal("ENROLLED", accepted.Status);
        Assert.Equal("FAILED", wrong.Status);
        Assert.Equal("PAIRING_REJECTED", wrong.ErrorCode);
        var jsonOptions = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        var json = JsonSerializer.Serialize(wrong, jsonOptions);
        Assert.Equal(json, JsonSerializer.Serialize(replay, jsonOptions));
        Assert.DoesNotContain(ticket.TicketId, json, StringComparison.Ordinal);
        Assert.DoesNotContain(ticket.PairingCode, json, StringComparison.Ordinal);
        Assert.DoesNotContain("AlreadyConsumed", json, StringComparison.Ordinal);
        Assert.DoesNotContain("sharedSecretBase64\":\"", json, StringComparison.Ordinal);
    }

    [Fact]
    public async Task InvalidEnvelopeOrLocalEndpointDoesNotConsumeTicket()
    {
        var store = CreateStore();
        var hubStore = CreateHubStore();
        var endpoint = CreateEndpoint(store, hubStore);
        var ticket = store.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));

        Assert.Equal("FAILED", (await endpoint.RedeemAsync(
            Request(ticket) with { Version = 2 }, LocalEndpoint)).Status);
        Assert.Equal("FAILED", (await endpoint.RedeemAsync(
            Request(ticket) with { AgentId = "agent invalid" }, LocalEndpoint)).Status);
        Assert.Equal("FAILED", (await endpoint.RedeemAsync(
            Request(ticket), new Uri("http://192.168.10.5:8443/bridge/v1"))).Status);

        Assert.Equal("ENROLLED", (await endpoint.RedeemAsync(Request(ticket), LocalEndpoint)).Status);
    }

    [Fact]
    public async Task ConcurrentRedeemersReceiveOnlyOneCredentialBundle()
    {
        var firstStore = CreateStore();
        var secondStore = CreateStore();
        var hubStore = CreateHubStore();
        var firstEndpoint = CreateEndpoint(firstStore, hubStore);
        var secondEndpoint = CreateEndpoint(secondStore, hubStore);
        var ticket = firstStore.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));

        var responses = await Task.WhenAll(
            firstEndpoint.RedeemAsync(Request(ticket), LocalEndpoint).AsTask(),
            secondEndpoint.RedeemAsync(Request(ticket), LocalEndpoint).AsTask());

        Assert.Single(responses, response => response.Status == "ENROLLED");
        var rejected = Assert.Single(responses, response => response.Status == "FAILED");
        Assert.Null(rejected.SharedSecretBase64);
        Assert.Empty(rejected.Scopes);
    }

    private BridgeV1PairingTicketStore CreateStore() =>
        new(_databasePath, _clock, _protector);

    private DesktopHubStore CreateHubStore() =>
        new(_databasePath, _protector);

    private BridgeV1PairingEndpoint CreateEndpoint(
        BridgeV1PairingTicketStore store,
        DesktopHubStore hubStore) =>
        new(store, hubStore, HubId, TlsPin, TimeSpan.FromDays(30), _clock);

    private static BridgeV1PairingRequest Request(BridgeV1PairingTicket ticket) =>
        new(1, ticket.TicketId, ticket.PairingCode, "agent-01");

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var path in new[] { _databasePath, _databasePath + "-wal", _databasePath + "-shm" })
        {
            try { if (File.Exists(path)) File.Delete(path); }
            catch (IOException) { }
        }
    }

    private sealed class TestDataProtector : IHubDataProtector
    {
        public byte[] Protect(ReadOnlySpan<byte> plaintext, ReadOnlySpan<byte> entropy) => Transform(plaintext);
        public byte[] Unprotect(ReadOnlySpan<byte> ciphertext, ReadOnlySpan<byte> entropy) => Transform(ciphertext);

        private static byte[] Transform(ReadOnlySpan<byte> input)
        {
            var output = input.ToArray();
            for (var index = 0; index < output.Length; index++) output[index] ^= 0x5A;
            return output;
        }
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private readonly DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
    }
}
