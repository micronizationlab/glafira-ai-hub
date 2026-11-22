using System.Security.Cryptography;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1AgentEnrollmentTests : IDisposable
{
    private const string HubId = "desktop-hub-01";
    private const string OwnerCoreId = "desktop-core-0001";
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
        Assert.Equal(ticket.TicketId, enrollment.EnrollmentId);
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
            "agent-01", OwnerCoreId, ["desktop.task.result"], TimeSpan.FromMinutes(2));

        Assert.True(replacement.RevokedExistingEnrollment);
        Assert.Equal("agent-01", replacement.Ticket.AgentId);
        Assert.Null(await resolver.ResolveAsync("agent-01", default));
        Assert.False(hubStore.IsAgentOwnerApproved("agent-01"));
        Assert.Equal(OwnerCoreId, ReadLatestRevocationRequesterDeviceId());
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
                "agent-01", OwnerCoreId,
                ["desktop.task.claim", "desktop.task.claim"], TimeSpan.FromMinutes(2)));

        Assert.NotNull(await resolver.ResolveAsync("agent-01", default));
    }

    [Fact]
    public async Task InvalidOwnerCoreIdentityCannotStartRePairingOrRevokeCredential()
    {
        var store = CreateStore();
        var ticket = store.CreateForAgent(
            "agent-01", ["desktop.task.claim"], TimeSpan.FromMinutes(1));
        Assert.True((await EnrollAsync(store, ticket, "agent-01")).Accepted);
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);

        await Assert.ThrowsAsync<ArgumentException>(async () =>
            await store.RevokeAndCreateForAgentAsync(
                "agent-01", "bad owner", ["desktop.task.result"], TimeSpan.FromMinutes(2)));

        Assert.NotNull(await resolver.ResolveAsync("agent-01", default));
        Assert.Null(ReadLatestRevocationRequesterDeviceId());
    }

    [Fact]
    public async Task ExactGenerationRevocationSettlesLeasesAndIsDurablyIdempotent()
    {
        var hubStore = new DesktopHubStore(_databasePath, _protector);
        var pairingStore = CreateStore();
        var ticket = pairingStore.CreateForAgent(
            "agent-01", ["desktop.task.claim", "desktop.task.result"], TimeSpan.FromMinutes(1));
        var enrollment = Assert.IsType<BridgeV1AgentEnrollmentCredential>(
            (await EnrollAsync(pairingStore, ticket, "agent-01")).Credential);
        var now = _clock.GetUtcNow();
        hubStore.RegisterPendingAgent("agent-01", "agent-01", now);
        Assert.True(hubStore.ConfigureAgentApproval("agent-01", ["research"], 2));
        Assert.True(hubStore.Heartbeat("agent-01", ["research"], 2, now));
        hubStore.Enqueue(CreateTask("read-only", HubTaskEffectRisk.ReadOnly), now);
        hubStore.Enqueue(CreateTask("external", HubTaskEffectRisk.ExternalSideEffect), now);
        var readLease = Assert.IsType<HubTaskLease>(
            hubStore.ClaimNext("agent-01", now, TimeSpan.FromMinutes(1)));
        var externalLease = Assert.IsType<HubTaskLease>(
            hubStore.ClaimNext("agent-01", now, TimeSpan.FromMinutes(1)));
        var request = new AgentRevocationRequest(
            1, "revoke-0001", enrollment.EnrollmentId, "agent-01", "owner-device-01",
            now.AddSeconds(1), AgentRevocationReason.OWNER_REQUEST);
        _clock.Advance(TimeSpan.FromSeconds(1));

        var decision = pairingStore.RevokeAgent(request);
        var repeated = pairingStore.RevokeAgent(request);

        Assert.Equal(AgentRevocationStatus.REVOKED, decision.Status);
        Assert.Equal(decision, repeated);
        Assert.Equal(Math.Max(readLease.FencingToken, externalLease.FencingToken),
            decision.LastAcceptedFencingToken);
        Assert.Equal(AgentActiveLeaseDisposition.OUTCOME_UNKNOWN, decision.ActiveLeaseDisposition);
        Assert.True(decision.RequiresOwnerReconciliation);
        Assert.Equal(HubTaskState.Queued, hubStore.GetTaskState("read-only"));
        Assert.Equal(HubTaskState.OutcomeUnknown, hubStore.GetTaskState("external"));
        Assert.False(hubStore.IsAgentOwnerApproved("agent-01"));
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        Assert.Null(await resolver.ResolveAsync("agent-01", default));
        var readRevocation = Assert.Single(hubStore.GetTaskHistory("read-only"),
            item => item.EventCode == "AGENT_REVOKED_READ_ONLY_REQUEUED");
        var externalRevocation = Assert.Single(hubStore.GetTaskHistory("external"),
            item => item.EventCode == "AGENT_REVOKED_OUTCOME_UNKNOWN");
        Assert.Equal("windows-core-0001", readRevocation.OwnerCoreId);
        Assert.Equal(1, readRevocation.OwnerGeneration);
        Assert.Equal(readRevocation.OwnerCoreId, externalRevocation.OwnerCoreId);
        Assert.Equal(readRevocation.OwnerGeneration, externalRevocation.OwnerGeneration);
    }

    [Fact]
    public async Task RevokedGenerationCannotRevokeReplacementCredential()
    {
        var hubStore = new DesktopHubStore(_databasePath, _protector);
        var pairingStore = CreateStore();
        var originalTicket = pairingStore.CreateForAgent(
            "agent-01", ["desktop.task.claim"], TimeSpan.FromMinutes(1));
        var original = Assert.IsType<BridgeV1AgentEnrollmentCredential>(
            (await EnrollAsync(pairingStore, originalTicket, "agent-01")).Credential);
        hubStore.RegisterPendingAgent("agent-01", "agent-01", _clock.GetUtcNow());
        var initialRevocation = pairingStore.RevokeAgent(new AgentRevocationRequest(
            1, "revoke-original", original.EnrollmentId, "agent-01", "owner-device-01",
            _clock.GetUtcNow(), AgentRevocationReason.OWNER_REQUEST));
        var replacementTicket = pairingStore.CreateForAgent(
            "agent-01", ["desktop.task.result"], TimeSpan.FromMinutes(1));
        var replacement = Assert.IsType<BridgeV1AgentEnrollmentCredential>(
            (await EnrollAsync(pairingStore, replacementTicket, "agent-01")).Credential);

        var staleDecision = pairingStore.RevokeAgent(new AgentRevocationRequest(
            1, "revoke-stale", original.EnrollmentId, "agent-01", "owner-device-01",
            _clock.GetUtcNow(), AgentRevocationReason.OWNER_REQUEST));

        Assert.Equal(AgentRevocationStatus.REVOKED, initialRevocation.Status);
        Assert.NotEqual(original.EnrollmentId, replacement.EnrollmentId);
        Assert.NotEqual(original.SharedSecretBase64, replacement.SharedSecretBase64);
        Assert.Equal(AgentRevocationStatus.CONFLICT, staleDecision.Status);
        Assert.Null(staleDecision.RevokedAt);
        Assert.Equal(AgentActiveLeaseDisposition.NONE, staleDecision.ActiveLeaseDisposition);
        Assert.True(staleDecision.RequiresOwnerReconciliation);
        Assert.Equal(replacement.EnrollmentId, pairingStore.GetActiveEnrollmentId("agent-01"));
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath, _protector, _clock);
        var credential = await resolver.ResolveAsync("agent-01", default);
        Assert.NotNull(credential);
        Assert.Equal(Convert.FromBase64String(replacement.SharedSecretBase64), credential.HmacSecret);
        CryptographicOperations.ZeroMemory(credential.HmacSecret);
    }

    private static HubTaskRequest CreateTask(string id, HubTaskEffectRisk risk) => new(
        id, "project-a", "windows-core-0001", 1,
        "idem-" + id, "research.search", "payload://" + id,
        new string('a', 64), ["research"], [], risk,
        risk == HubTaskEffectRisk.ExternalSideEffect ? "owner-approval-1" : null);

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

    private string? ReadLatestRevocationRequesterDeviceId()
    {
        using var connection = OpenConnection();
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT requested_by_device_id FROM HubAgentRevocations
            ORDER BY decided_at_ms DESC, revocation_id DESC LIMIT 1;
            """;
        return command.ExecuteScalar() as string;
    }

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
