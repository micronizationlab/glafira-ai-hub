using System.Security.Cryptography;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1AgentEnrollmentTests : IDisposable
{
    private const string HubId = "desktop-hub-01";
    private const string TlsPin = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static readonly Uri Endpoint = new("https://192.168.10.5:8443/");
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-enrollment-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly MutableTimeProvider _clock = new(DateTimeOffset.FromUnixTimeSeconds(1_800_000_000));
    private readonly TestDataProtector _protector = new();

    [Fact]
    public async Task RedeemsTicketIntoDurableCredentialAtomically()
    {
        var store = CreateStore();
        var ticket = store.Create(
            ["desktop.task.result", "desktop.task.claim"],
            TimeSpan.FromMinutes(2));

        var result = await EnrollAsync(store, ticket, "agent-01");

        Assert.True(result.Accepted);
        var enrollment = Assert.IsType<BridgeV1AgentEnrollmentCredential>(result.Credential);
        Assert.Equal("agent-01", enrollment.AgentId);
        Assert.Equal(HubId, enrollment.DesktopHubId);
        Assert.Equal(Endpoint, enrollment.Endpoint);
        Assert.Equal(TlsPin, enrollment.TlsCertificateSha256);
        Assert.Equal(["desktop.task.claim", "desktop.task.result"], enrollment.Scopes.Order(StringComparer.Ordinal));
        var clearSecret = Convert.FromBase64String(enrollment.SharedSecretBase64);
        Assert.Equal(32, clearSecret.Length);

        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        var resolved = await resolver.ResolveAsync("agent-01", default);
        Assert.NotNull(resolved);
        Assert.Equal(clearSecret, resolved.HmacSecret);
        Assert.Equal(enrollment.Scopes.Order(StringComparer.Ordinal), resolved.AllowedScopes.Order(StringComparer.Ordinal));

        await using var connection = OpenConnection();
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT tickets.consumed_at_ms, credentials.protected_secret,
                   enrollment.desktop_hub_id, enrollment.endpoint,
                   enrollment.tls_certificate_sha256
            FROM HubPairingTickets AS tickets
            JOIN HubCredentials AS credentials ON credentials.device_id = 'agent-01'
            JOIN HubAgentEnrollments AS enrollment ON enrollment.agent_id = credentials.device_id
            WHERE tickets.ticket_id = $ticket;
            """;
        command.Parameters.AddWithValue("$ticket", ticket.TicketId);
        await using var reader = await command.ExecuteReaderAsync();
        Assert.True(await reader.ReadAsync());
        Assert.False(reader.IsDBNull(0));
        Assert.False(clearSecret.SequenceEqual(Assert.IsType<byte[]>(reader["protected_secret"])));
        Assert.Equal(HubId, reader.GetString(2));
        Assert.Equal(Endpoint.AbsoluteUri, reader.GetString(3));
        Assert.Equal(TlsPin, reader.GetString(4));
        CryptographicOperations.ZeroMemory(clearSecret);
        CryptographicOperations.ZeroMemory(resolved.HmacSecret);
    }

    [Fact]
    public async Task ReplayCannotReturnOrRotateTheCredential()
    {
        var store = CreateStore();
        var ticket = store.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));
        var accepted = await EnrollAsync(store, ticket, "agent-01");

        var replay = await EnrollAsync(store, ticket, "agent-01");

        Assert.True(accepted.Accepted);
        Assert.False(replay.Accepted);
        Assert.Equal(BridgeV1AgentEnrollmentFailure.AlreadyConsumed, replay.Failure);
        Assert.Null(replay.Credential);
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        var resolved = await resolver.ResolveAsync("agent-01", default);
        Assert.Equal(
            Convert.FromBase64String(accepted.Credential!.SharedSecretBase64),
            resolved!.HmacSecret);
    }

    [Fact]
    public async Task WrongCodeCreatesNothingAndLeavesTicketUsable()
    {
        var store = CreateStore();
        var ticket = store.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));
        var wrong = ticket.PairingCode[..42] + (ticket.PairingCode[42] == 'a' ? "b" : "a");

        var rejected = await store.EnrollAgentAsync(
            ticket.TicketId, wrong, "agent-01", HubId, Endpoint, TlsPin, TimeSpan.FromDays(30));
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        Assert.Null(await resolver.ResolveAsync("agent-01", default));

        var accepted = await EnrollAsync(store, ticket, "agent-01");
        Assert.False(rejected.Accepted);
        Assert.Equal(BridgeV1AgentEnrollmentFailure.Rejected, rejected.Failure);
        Assert.True(accepted.Accepted);
    }

    [Fact]
    public async Task ConcurrentHubProcessesIssueOnlyOneCredential()
    {
        var first = CreateStore();
        var second = CreateStore();
        var ticket = first.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));

        var results = await Task.WhenAll(
            EnrollAsync(first, ticket, "agent-01").AsTask(),
            EnrollAsync(second, ticket, "agent-01").AsTask());

        Assert.Single(results, result => result.Accepted);
        Assert.Single(results, result => result.Failure == BridgeV1AgentEnrollmentFailure.AlreadyConsumed);
    }

    [Fact]
    public async Task ActiveAgentConflictRollsBackTicketConsumption()
    {
        var store = CreateStore();
        var firstTicket = store.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));
        Assert.True((await EnrollAsync(store, firstTicket, "agent-01")).Accepted);
        var secondTicket = store.Create(["desktop.task.result"], TimeSpan.FromMinutes(1));

        var conflict = await EnrollAsync(store, secondTicket, "agent-01");
        var otherAgent = await EnrollAsync(store, secondTicket, "agent-02");

        Assert.False(conflict.Accepted);
        Assert.Equal(BridgeV1AgentEnrollmentFailure.AgentAlreadyEnrolled, conflict.Failure);
        Assert.True(otherAgent.Accepted);
    }

    [Fact]
    public async Task ExpiredEnrollmentIsDeniedAndCanBeReplacedWithANewTicket()
    {
        var store = CreateStore();
        var firstTicket = store.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));
        var first = await EnrollAsync(store, firstTicket, "agent-01", TimeSpan.FromHours(1));
        _clock.Advance(TimeSpan.FromHours(2));
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        Assert.Null(await resolver.ResolveAsync("agent-01", default));
        var secondTicket = store.Create(["desktop.task.result"], TimeSpan.FromMinutes(1));

        var second = await EnrollAsync(store, secondTicket, "agent-01", TimeSpan.FromDays(30));

        Assert.True(second.Accepted);
        Assert.NotEqual(first.Credential!.SharedSecretBase64, second.Credential!.SharedSecretBase64);
        Assert.NotNull(await resolver.ResolveAsync("agent-01", default));
    }

    [Fact]
    public async Task InvalidMetadataIsRejectedBeforeTicketConsumption()
    {
        var store = CreateStore();
        var ticket = store.Create(["desktop.task.claim"], TimeSpan.FromMinutes(1));

        await Assert.ThrowsAsync<ArgumentException>(async () =>
            await store.EnrollAgentAsync(
                ticket.TicketId,
                ticket.PairingCode,
                "agent-01",
                HubId,
                new Uri("http://192.168.10.5:8443/"),
                TlsPin,
                TimeSpan.FromDays(30)));

        Assert.True((await EnrollAsync(store, ticket, "agent-01")).Accepted);
    }

    [Fact]
    public async Task AgentBoundTicketCannotBeStolenByAnotherAgent()
    {
        var store = CreateStore();
        var ticket = store.CreateForAgent(
            "agent-01", ["desktop.task.claim"], TimeSpan.FromMinutes(1));

        var wrongAgent = await EnrollAsync(store, ticket, "agent-02");
        var intendedAgent = await EnrollAsync(store, ticket, "agent-01");

        Assert.Equal("agent-01", ticket.AgentId);
        Assert.False(wrongAgent.Accepted);
        Assert.Equal(BridgeV1AgentEnrollmentFailure.Rejected, wrongAgent.Failure);
        Assert.True(intendedAgent.Accepted);
    }

    [Fact]
    public async Task HiddenOwnerTicketIsCancelledAndCannotEnroll()
    {
        var store = CreateStore();
        var ticket = store.CreateForAgent(
            "agent-01", ["desktop.task.claim"], TimeSpan.FromMinutes(1));

        Assert.True(store.Cancel(ticket.TicketId));
        Assert.False(store.Cancel(ticket.TicketId));
        var result = await EnrollAsync(store, ticket, "agent-01");

        Assert.False(result.Accepted);
        Assert.Equal(BridgeV1AgentEnrollmentFailure.AlreadyConsumed, result.Failure);
    }

    [Fact]
    public async Task RePairingAtomicallyRevokesOldCredentialAndCreatesBoundTicket()
    {
        var hubStore = new DesktopHubStore(_databasePath, _protector);
        var store = CreateStore();
        var firstTicket = store.CreateForAgent(
            "agent-01", ["desktop.task.claim"], TimeSpan.FromMinutes(1));
        var first = await EnrollAsync(store, firstTicket, "agent-01");
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        Assert.NotNull(await resolver.ResolveAsync("agent-01", default));
        hubStore.RegisterPendingAgent("agent-01", "agent-01", _clock.GetUtcNow());
        Assert.True(hubStore.ConfigureAgentApproval("agent-01", ["code.build"], 1));
        Assert.True(hubStore.IsAgentOwnerApproved("agent-01"));

        var replacement = await store.RevokeAndCreateForAgentAsync(
            "agent-01", ["desktop.task.result"], TimeSpan.FromMinutes(2));

        Assert.True(replacement.RevokedExistingEnrollment);
        Assert.Equal("agent-01", replacement.Ticket.AgentId);
        Assert.Null(await resolver.ResolveAsync("agent-01", default));
        Assert.False(hubStore.IsAgentOwnerApproved("agent-01"));
        var second = await EnrollAsync(store, replacement.Ticket, "agent-01");
        Assert.True(second.Accepted);
        Assert.NotEqual(first.Credential!.SharedSecretBase64, second.Credential!.SharedSecretBase64);
        Assert.NotNull(await resolver.ResolveAsync("agent-01", default));
    }

    [Fact]
    public async Task InvalidRePairingRequestDoesNotRevokeExistingCredential()
    {
        var store = CreateStore();
        var firstTicket = store.CreateForAgent(
            "agent-01", ["desktop.task.claim"], TimeSpan.FromMinutes(1));
        Assert.True((await EnrollAsync(store, firstTicket, "agent-01")).Accepted);
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);

        await Assert.ThrowsAsync<ArgumentException>(async () =>
            await store.RevokeAndCreateForAgentAsync(
                "agent-01", ["desktop.task.claim", "desktop.task.claim"], TimeSpan.FromMinutes(2)));

        Assert.NotNull(await resolver.ResolveAsync("agent-01", default));
    }

    private BridgeV1PairingTicketStore CreateStore() =>
        new(_databasePath, _clock, _protector);

    private static ValueTask<BridgeV1AgentEnrollmentResult> EnrollAsync(
        BridgeV1PairingTicketStore store,
        BridgeV1PairingTicket ticket,
        string agentId,
        TimeSpan? lifetime = null) =>
        store.EnrollAgentAsync(
            ticket.TicketId,
            ticket.PairingCode,
            agentId,
            HubId,
            Endpoint,
            TlsPin,
            lifetime ?? TimeSpan.FromDays(30));

    private SqliteConnection OpenConnection() => new(
        new SqliteConnectionStringBuilder { DataSource = _databasePath }.ToString());

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
            for (var index = 0; index < output.Length; index++) output[index] ^= 0xA5;
            return output;
        }
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan duration) => _now = _now.Add(duration);
    }
}
