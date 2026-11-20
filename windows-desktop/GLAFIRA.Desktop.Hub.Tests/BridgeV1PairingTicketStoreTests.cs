using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1PairingTicketStoreTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-pairing-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly MutableTimeProvider _clock = new(DateTimeOffset.FromUnixTimeSeconds(1_800_000_000));

    [Fact]
    public async Task StoresOnlyCodeDigestAndConsumesTicketExactlyOnce()
    {
        var store = new BridgeV1PairingTicketStore(_databasePath, _clock);
        var ticket = store.Create(["task.result", "task.claim"], TimeSpan.FromMinutes(2));

        await using var connection = OpenConnection();
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT code_sha256, scopes_json FROM HubPairingTickets WHERE ticket_id = $ticket;";
        command.Parameters.AddWithValue("$ticket", ticket.TicketId);
        await using var reader = await command.ExecuteReaderAsync();
        Assert.True(await reader.ReadAsync());
        var digest = Assert.IsType<byte[]>(reader["code_sha256"]);
        Assert.Equal(32, digest.Length);
        Assert.DoesNotContain(ticket.PairingCode, reader.GetString(1));
        await reader.DisposeAsync();
        await connection.CloseAsync();

        var accepted = await store.ConsumeAsync(ticket.TicketId, ticket.PairingCode);
        var replay = await store.ConsumeAsync(ticket.TicketId, ticket.PairingCode);

        Assert.True(accepted.Accepted);
        Assert.Equal(["task.claim", "task.result"], accepted.Scopes.Order(StringComparer.Ordinal));
        Assert.False(replay.Accepted);
        Assert.Equal(BridgeV1PairingTicketFailure.AlreadyConsumed, replay.Failure);
    }

    [Fact]
    public async Task WrongCodeDoesNotConsumeTheTicket()
    {
        var store = new BridgeV1PairingTicketStore(_databasePath, _clock);
        var ticket = store.Create(["task.claim"], TimeSpan.FromMinutes(1));
        var wrong = ticket.PairingCode[..42] + (ticket.PairingCode[42] == 'a' ? "b" : "a");

        var rejected = await store.ConsumeAsync(ticket.TicketId, wrong);
        var accepted = await store.ConsumeAsync(ticket.TicketId, ticket.PairingCode);

        Assert.False(rejected.Accepted);
        Assert.Equal(BridgeV1PairingTicketFailure.Rejected, rejected.Failure);
        Assert.True(accepted.Accepted);
    }

    [Fact]
    public async Task ExpiredTicketCannotBeConsumed()
    {
        var store = new BridgeV1PairingTicketStore(_databasePath, _clock);
        var ticket = store.Create(["task.claim"], TimeSpan.FromSeconds(30));
        _clock.Advance(TimeSpan.FromSeconds(31));

        var result = await store.ConsumeAsync(ticket.TicketId, ticket.PairingCode);

        Assert.False(result.Accepted);
        Assert.Equal(BridgeV1PairingTicketFailure.Expired, result.Failure);
    }

    [Fact]
    public async Task ConcurrentHubProcessesCannotConsumeTheSameTicketTwice()
    {
        var first = new BridgeV1PairingTicketStore(_databasePath, _clock);
        var second = new BridgeV1PairingTicketStore(_databasePath, _clock);
        var ticket = first.Create(["task.claim"], TimeSpan.FromMinutes(1));

        var results = await Task.WhenAll(
            first.ConsumeAsync(ticket.TicketId, ticket.PairingCode).AsTask(),
            second.ConsumeAsync(ticket.TicketId, ticket.PairingCode).AsTask());

        Assert.Single(results, result => result.Accepted);
        Assert.Single(results, result => result.Failure == BridgeV1PairingTicketFailure.AlreadyConsumed);
    }

    [Theory]
    [InlineData(29)]
    [InlineData(601)]
    public void LifetimeOutsideBoundedWindowIsRejected(int seconds)
    {
        var store = new BridgeV1PairingTicketStore(_databasePath, _clock);

        Assert.Throws<ArgumentOutOfRangeException>(() =>
            store.Create(["task.claim"], TimeSpan.FromSeconds(seconds)));
    }

    [Fact]
    public void DuplicateOrMalformedScopesAreRejectedBeforeTicketCreation()
    {
        var store = new BridgeV1PairingTicketStore(_databasePath, _clock);

        Assert.Throws<ArgumentException>(() =>
            store.Create(["task.claim", "task.claim"], TimeSpan.FromMinutes(1)));
        Assert.Throws<ArgumentException>(() =>
            store.Create(["task claim"], TimeSpan.FromMinutes(1)));
    }

    [Fact]
    public async Task ExistingUnboundTicketSchemaMigratesWithoutDataLoss()
    {
        await using (var connection = OpenConnection())
        {
            await connection.OpenAsync();
            await using var command = connection.CreateCommand();
            command.CommandText = """
                CREATE TABLE HubPairingTickets(
                    ticket_id TEXT NOT NULL PRIMARY KEY,
                    code_sha256 BLOB NOT NULL CHECK(length(code_sha256) = 32),
                    scopes_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    consumed_at_ms INTEGER NULL
                );
                """;
            await command.ExecuteNonQueryAsync();
        }

        var store = new BridgeV1PairingTicketStore(_databasePath, _clock);
        var ticket = store.CreateForAgent(
            "agent-01", ["task.claim"], TimeSpan.FromMinutes(1));

        await using var verify = OpenConnection();
        await verify.OpenAsync();
        await using var query = verify.CreateCommand();
        query.CommandText = "SELECT agent_id FROM HubPairingTickets WHERE ticket_id = $ticket;";
        query.Parameters.AddWithValue("$ticket", ticket.TicketId);
        Assert.Equal("agent-01", await query.ExecuteScalarAsync());
    }

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

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan duration) => _now = _now.Add(duration);
    }
}
