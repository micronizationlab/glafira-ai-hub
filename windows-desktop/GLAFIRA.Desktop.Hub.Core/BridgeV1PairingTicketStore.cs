using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record BridgeV1PairingTicket(
    string TicketId,
    string PairingCode,
    DateTimeOffset ExpiresAt,
    IReadOnlySet<string> Scopes);

public enum BridgeV1PairingTicketFailure
{
    None,
    Rejected,
    Expired,
    AlreadyConsumed
}

public sealed record BridgeV1PairingTicketConsumeResult(
    bool Accepted,
    BridgeV1PairingTicketFailure Failure,
    IReadOnlySet<string> Scopes)
{
    public static BridgeV1PairingTicketConsumeResult Reject(BridgeV1PairingTicketFailure failure) =>
        new(false, failure, new HashSet<string>(StringComparer.Ordinal));
}

/// <summary>
/// Durable owner-created, single-use pairing tickets. Only a SHA-256 digest of the
/// 256-bit code is stored. A successful consume is one atomic conditional UPDATE,
/// so two Hub processes cannot enroll from the same ticket.
/// </summary>
public sealed class BridgeV1PairingTicketStore
{
    private static readonly TimeSpan MinimumLifetime = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan MaximumLifetime = TimeSpan.FromMinutes(10);
    private readonly string _connectionString;
    private readonly TimeProvider _timeProvider;

    public BridgeV1PairingTicketStore(string databasePath, TimeProvider? timeProvider = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        var fullPath = Path.GetFullPath(databasePath);
        var directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = fullPath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared,
            Pooling = true
        }.ToString();
        _timeProvider = timeProvider ?? TimeProvider.System;
        Initialize();
    }

    public BridgeV1PairingTicket Create(
        IEnumerable<string> requestedScopes,
        TimeSpan lifetime)
    {
        if (lifetime < MinimumLifetime || lifetime > MaximumLifetime)
            throw new ArgumentOutOfRangeException(nameof(lifetime),
                "Pairing tickets must live for 30 seconds to 10 minutes.");
        var scopes = NormalizeScopes(requestedScopes);
        var now = _timeProvider.GetUtcNow();
        var expiresAt = now.Add(lifetime);
        var ticketId = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        var codeBytes = RandomNumberGenerator.GetBytes(32);
        string pairingCode;
        try { pairingCode = Base64Url(codeBytes); }
        finally { CryptographicOperations.ZeroMemory(codeBytes); }
        var digest = Digest(ticketId, pairingCode);
        try
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = """
                INSERT INTO HubPairingTickets(
                    ticket_id, code_sha256, scopes_json, created_at_ms, expires_at_ms, consumed_at_ms)
                VALUES($ticket, $digest, $scopes, $created, $expires, NULL);
                """;
            command.Parameters.AddWithValue("$ticket", ticketId);
            command.Parameters.Add("$digest", SqliteType.Blob).Value = digest;
            command.Parameters.AddWithValue("$scopes", JsonSerializer.Serialize(scopes));
            command.Parameters.AddWithValue("$created", now.ToUnixTimeMilliseconds());
            command.Parameters.AddWithValue("$expires", expiresAt.ToUnixTimeMilliseconds());
            command.ExecuteNonQuery();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(digest);
        }
        return new BridgeV1PairingTicket(
            ticketId, pairingCode, expiresAt,
            new HashSet<string>(scopes, StringComparer.Ordinal));
    }

    public async ValueTask<BridgeV1PairingTicketConsumeResult> ConsumeAsync(
        string ticketId,
        string pairingCode,
        CancellationToken cancellationToken = default)
    {
        if (!IsTicketId(ticketId) || !IsPairingCode(pairingCode))
            return BridgeV1PairingTicketConsumeResult.Reject(BridgeV1PairingTicketFailure.Rejected);

        var now = _timeProvider.GetUtcNow();
        var nowMs = now.ToUnixTimeMilliseconds();
        var digest = Digest(ticketId, pairingCode);
        try
        {
            await using var connection = new SqliteConnection(_connectionString);
            await connection.OpenAsync(cancellationToken);
            await using (var consume = connection.CreateCommand())
            {
                consume.CommandText = """
                    UPDATE HubPairingTickets
                    SET consumed_at_ms = $now
                    WHERE ticket_id = $ticket
                      AND consumed_at_ms IS NULL
                      AND expires_at_ms > $now
                      AND code_sha256 = $digest;
                    """;
                consume.Parameters.AddWithValue("$now", nowMs);
                consume.Parameters.AddWithValue("$ticket", ticketId);
                consume.Parameters.Add("$digest", SqliteType.Blob).Value = digest;
                if (await consume.ExecuteNonQueryAsync(cancellationToken) == 1)
                {
                    var scopes = await ReadScopesAsync(connection, ticketId, cancellationToken);
                    return new BridgeV1PairingTicketConsumeResult(
                        true, BridgeV1PairingTicketFailure.None,
                        new HashSet<string>(scopes, StringComparer.Ordinal));
                }
            }

            await using var status = connection.CreateCommand();
            status.CommandText = """
                SELECT expires_at_ms, consumed_at_ms
                FROM HubPairingTickets
                WHERE ticket_id = $ticket;
                """;
            status.Parameters.AddWithValue("$ticket", ticketId);
            await using var reader = await status.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
                return BridgeV1PairingTicketConsumeResult.Reject(BridgeV1PairingTicketFailure.Rejected);
            if (!reader.IsDBNull(1))
                return BridgeV1PairingTicketConsumeResult.Reject(BridgeV1PairingTicketFailure.AlreadyConsumed);
            if (reader.GetInt64(0) <= nowMs)
                return BridgeV1PairingTicketConsumeResult.Reject(BridgeV1PairingTicketFailure.Expired);
            return BridgeV1PairingTicketConsumeResult.Reject(BridgeV1PairingTicketFailure.Rejected);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(digest);
        }
    }

    private static async Task<string[]> ReadScopesAsync(
        SqliteConnection connection,
        string ticketId,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT scopes_json FROM HubPairingTickets WHERE ticket_id = $ticket;";
        command.Parameters.AddWithValue("$ticket", ticketId);
        var json = (string?)await command.ExecuteScalarAsync(cancellationToken)
            ?? throw new InvalidDataException("Consumed pairing ticket has no scopes.");
        var scopes = JsonSerializer.Deserialize<string[]>(json)
            ?? throw new InvalidDataException("Consumed pairing ticket scopes are invalid.");
        var normalized = NormalizeScopes(scopes);
        if (!normalized.SequenceEqual(scopes, StringComparer.Ordinal))
            throw new InvalidDataException("Consumed pairing ticket scopes are not canonical.");
        return normalized;
    }

    private void Initialize()
    {
        using var connection = new SqliteConnection(_connectionString);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = FULL;
            PRAGMA busy_timeout = 5000;
            CREATE TABLE IF NOT EXISTS HubPairingTickets(
                ticket_id TEXT NOT NULL PRIMARY KEY,
                code_sha256 BLOB NOT NULL CHECK(length(code_sha256) = 32),
                scopes_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                consumed_at_ms INTEGER NULL,
                CHECK(expires_at_ms > created_at_ms),
                CHECK(consumed_at_ms IS NULL OR consumed_at_ms >= created_at_ms)
            );
            CREATE INDEX IF NOT EXISTS IX_HubPairingTickets_Expiry
                ON HubPairingTickets(expires_at_ms, consumed_at_ms);
            """;
        command.ExecuteNonQuery();
    }

    private static byte[] Digest(string ticketId, string pairingCode)
    {
        var value = Encoding.ASCII.GetBytes($"{ticketId}:{pairingCode}");
        try { return SHA256.HashData(value); }
        finally { CryptographicOperations.ZeroMemory(value); }
    }

    private static bool IsTicketId(string value) => value is { Length: 32 } &&
        value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static bool IsPairingCode(string value)
    {
        if (value is not { Length: 43 } ||
            value.Any(character => !(char.IsAsciiLetterOrDigit(character) || character is '-' or '_')))
            return false;
        byte[] decoded;
        try { decoded = FromBase64Url(value); }
        catch (FormatException) { return false; }
        try { return decoded.Length == 32; }
        finally { CryptographicOperations.ZeroMemory(decoded); }
    }

    private static string Base64Url(byte[] value) =>
        Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromBase64Url(string value) =>
        Convert.FromBase64String(value.Replace('-', '+').Replace('_', '/') + "=");

    private static string[] NormalizeScopes(IEnumerable<string> requestedScopes)
    {
        ArgumentNullException.ThrowIfNull(requestedScopes);
        var scopes = requestedScopes.ToArray();
        if (scopes.Length is < 1 or > 32 ||
            scopes.Any(scope => string.IsNullOrWhiteSpace(scope) || scope.Length > 128 ||
                !scope.All(character => char.IsAsciiLetterOrDigit(character) || character is '.' or '_' or ':' or '-')) ||
            scopes.Distinct(StringComparer.Ordinal).Count() != scopes.Length)
            throw new ArgumentException("Pairing scopes must be 1 to 32 unique bounded ASCII identifiers.", nameof(requestedScopes));
        return scopes.Order(StringComparer.Ordinal).ToArray();
    }
}
