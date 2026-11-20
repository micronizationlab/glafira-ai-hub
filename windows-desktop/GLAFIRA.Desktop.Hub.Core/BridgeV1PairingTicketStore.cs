using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record BridgeV1PairingTicket(
    string TicketId,
    string PairingCode,
    DateTimeOffset ExpiresAt,
    IReadOnlySet<string> Scopes,
    string? AgentId = null);

public sealed record BridgeV1RePairingTicketResult(
    bool RevokedExistingEnrollment,
    BridgeV1PairingTicket Ticket);

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

public sealed record BridgeV1AgentEnrollmentCredential(
    string AgentId,
    string DesktopHubId,
    Uri Endpoint,
    string TlsCertificateSha256,
    string SharedSecretBase64,
    IReadOnlySet<string> Scopes,
    DateTimeOffset EnrolledAt,
    DateTimeOffset ExpiresAt);

public enum BridgeV1AgentEnrollmentFailure
{
    None,
    Rejected,
    Expired,
    AlreadyConsumed,
    AgentAlreadyEnrolled
}

public sealed record BridgeV1AgentEnrollmentResult(
    bool Accepted,
    BridgeV1AgentEnrollmentFailure Failure,
    BridgeV1AgentEnrollmentCredential? Credential)
{
    public static BridgeV1AgentEnrollmentResult Reject(BridgeV1AgentEnrollmentFailure failure) =>
        new(false, failure, null);
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
    private static readonly TimeSpan MinimumCredentialLifetime = TimeSpan.FromHours(1);
    private static readonly TimeSpan MaximumCredentialLifetime = TimeSpan.FromDays(365);
    private readonly string _connectionString;
    private readonly TimeProvider _timeProvider;
    private readonly IHubDataProtector? _dataProtector;

    public BridgeV1PairingTicketStore(
        string databasePath,
        TimeProvider? timeProvider = null,
        IHubDataProtector? dataProtector = null)
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
        _dataProtector = dataProtector;
        Initialize();
    }

    public BridgeV1PairingTicket Create(
        IEnumerable<string> requestedScopes,
        TimeSpan lifetime) => CreateInternal(null, requestedScopes, lifetime);

    public BridgeV1PairingTicket CreateForAgent(
        string agentId,
        IEnumerable<string> requestedScopes,
        TimeSpan lifetime)
    {
        ValidateIdentity(agentId, nameof(agentId));
        return CreateInternal(agentId, requestedScopes, lifetime);
    }

    private BridgeV1PairingTicket CreateInternal(
        string? agentId,
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
                    ticket_id, agent_id, code_sha256, scopes_json, created_at_ms, expires_at_ms, consumed_at_ms)
                VALUES($ticket, $agent, $digest, $scopes, $created, $expires, NULL);
                """;
            command.Parameters.AddWithValue("$ticket", ticketId);
            command.Parameters.AddWithValue("$agent", (object?)agentId ?? DBNull.Value);
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
            new HashSet<string>(scopes, StringComparer.Ordinal), agentId);
    }

    public async ValueTask<BridgeV1RePairingTicketResult> RevokeAndCreateForAgentAsync(
        string agentId,
        IEnumerable<string> requestedScopes,
        TimeSpan lifetime,
        CancellationToken cancellationToken = default)
    {
        ValidateIdentity(agentId, nameof(agentId));
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
            await using var connection = new SqliteConnection(_connectionString);
            await connection.OpenAsync(cancellationToken);
            using var transaction = connection.BeginTransaction(deferred: false);
            var nowMs = now.ToUnixTimeMilliseconds();

            await using var credential = connection.CreateCommand();
            credential.Transaction = transaction;
            credential.CommandText = """
                UPDATE HubCredentials
                SET protected_secret = X'', scopes_json = '[]', enabled = 0, updated_at_ms = $now
                WHERE device_id = $agent AND enabled = 1;
                """;
            credential.Parameters.AddWithValue("$agent", agentId);
            credential.Parameters.AddWithValue("$now", nowMs);
            var revokedCredential = await credential.ExecuteNonQueryAsync(cancellationToken) == 1;

            await using var enrollment = connection.CreateCommand();
            enrollment.Transaction = transaction;
            enrollment.CommandText = """
                UPDATE HubAgentEnrollments
                SET revoked_at_ms = $now
                WHERE agent_id = $agent AND revoked_at_ms IS NULL;
                """;
            enrollment.Parameters.AddWithValue("$agent", agentId);
            enrollment.Parameters.AddWithValue("$now", nowMs);
            var revokedEnrollment = await enrollment.ExecuteNonQueryAsync(cancellationToken) == 1;

            await using var ticket = connection.CreateCommand();
            ticket.Transaction = transaction;
            ticket.CommandText = """
                INSERT INTO HubPairingTickets(
                    ticket_id, agent_id, code_sha256, scopes_json, created_at_ms, expires_at_ms, consumed_at_ms)
                VALUES($ticket, $agent, $digest, $scopes, $created, $expires, NULL);
                """;
            ticket.Parameters.AddWithValue("$ticket", ticketId);
            ticket.Parameters.AddWithValue("$agent", agentId);
            ticket.Parameters.Add("$digest", SqliteType.Blob).Value = digest;
            ticket.Parameters.AddWithValue("$scopes", JsonSerializer.Serialize(scopes));
            ticket.Parameters.AddWithValue("$created", nowMs);
            ticket.Parameters.AddWithValue("$expires", expiresAt.ToUnixTimeMilliseconds());
            await ticket.ExecuteNonQueryAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);

            return new BridgeV1RePairingTicketResult(
                revokedCredential || revokedEnrollment,
                new BridgeV1PairingTicket(
                    ticketId, pairingCode, expiresAt,
                    new HashSet<string>(scopes, StringComparer.Ordinal), agentId));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(digest);
        }
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
                      AND agent_id IS NULL
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

    /// <summary>
    /// Atomically consumes an owner-approved ticket and creates the Hub side of an
    /// Agent enrollment. The 256-bit shared secret is returned once and stored only
    /// as user-bound protected ciphertext. A lost response cannot be replayed: the
    /// owner must revoke the orphaned enrollment and create a new ticket.
    /// </summary>
    public async ValueTask<BridgeV1AgentEnrollmentResult> EnrollAgentAsync(
        string ticketId,
        string pairingCode,
        string agentId,
        string desktopHubId,
        Uri endpoint,
        string tlsCertificateSha256,
        TimeSpan credentialLifetime,
        CancellationToken cancellationToken = default)
    {
        ValidateIdentity(agentId, nameof(agentId));
        ValidateIdentity(desktopHubId, nameof(desktopHubId));
        ValidateEndpoint(endpoint);
        ValidateTlsPin(tlsCertificateSha256);
        if (credentialLifetime < MinimumCredentialLifetime || credentialLifetime > MaximumCredentialLifetime)
            throw new ArgumentOutOfRangeException(nameof(credentialLifetime),
                "Agent credentials must live for one hour to 365 days.");
        if (!IsTicketId(ticketId) || !IsPairingCode(pairingCode))
            return BridgeV1AgentEnrollmentResult.Reject(BridgeV1AgentEnrollmentFailure.Rejected);

        var now = _timeProvider.GetUtcNow();
        var nowMs = now.ToUnixTimeMilliseconds();
        var expiresAt = now.Add(credentialLifetime);
        var digest = Digest(ticketId, pairingCode);
        byte[]? secret = null;
        byte[]? protectedSecret = null;
        try
        {
            await using var connection = new SqliteConnection(_connectionString);
            await connection.OpenAsync(cancellationToken);
            using var transaction = connection.BeginTransaction(deferred: false);

            await using (var consume = connection.CreateCommand())
            {
                consume.Transaction = transaction;
                consume.CommandText = """
                    UPDATE HubPairingTickets
                    SET consumed_at_ms = $now
                    WHERE ticket_id = $ticket
                      AND consumed_at_ms IS NULL
                      AND expires_at_ms > $now
                      AND (agent_id IS NULL OR agent_id = $agent)
                      AND code_sha256 = $digest;
                    """;
                consume.Parameters.AddWithValue("$now", nowMs);
                consume.Parameters.AddWithValue("$ticket", ticketId);
                consume.Parameters.AddWithValue("$agent", agentId);
                consume.Parameters.Add("$digest", SqliteType.Blob).Value = digest;
                if (await consume.ExecuteNonQueryAsync(cancellationToken) != 1)
                    return BridgeV1AgentEnrollmentResult.Reject(
                        await ClassifyEnrollmentFailureAsync(connection, transaction, ticketId, nowMs, cancellationToken));
            }

            var scopes = await ReadScopesAsync(connection, transaction, ticketId, cancellationToken);
            await using (var active = connection.CreateCommand())
            {
                active.Transaction = transaction;
                active.CommandText = """
                    SELECT 1
                    FROM HubAgentEnrollments
                    WHERE agent_id = $agent
                      AND revoked_at_ms IS NULL
                      AND expires_at_ms > $now;
                    """;
                active.Parameters.AddWithValue("$agent", agentId);
                active.Parameters.AddWithValue("$now", nowMs);
                if (await active.ExecuteScalarAsync(cancellationToken) is not null)
                    return BridgeV1AgentEnrollmentResult.Reject(
                        BridgeV1AgentEnrollmentFailure.AgentAlreadyEnrolled);
            }

            secret = RandomNumberGenerator.GetBytes(32);
            var protector = _dataProtector ?? new WindowsDpapiHubDataProtector();
            protectedSecret = HubCredentialCryptography.Protect(protector, secret);
            var scopesJson = JsonSerializer.Serialize(scopes);

            await using (var credential = connection.CreateCommand())
            {
                credential.Transaction = transaction;
                credential.CommandText = """
                    INSERT INTO HubCredentials(device_id, protected_secret, scopes_json, enabled, updated_at_ms)
                    VALUES($agent, $secret, $scopes, 1, $now)
                    ON CONFLICT(device_id) DO UPDATE SET
                        protected_secret = excluded.protected_secret,
                        scopes_json = excluded.scopes_json,
                        enabled = 1,
                        updated_at_ms = excluded.updated_at_ms;
                    """;
                credential.Parameters.AddWithValue("$agent", agentId);
                credential.Parameters.Add("$secret", SqliteType.Blob).Value = protectedSecret;
                credential.Parameters.AddWithValue("$scopes", scopesJson);
                credential.Parameters.AddWithValue("$now", nowMs);
                await credential.ExecuteNonQueryAsync(cancellationToken);
            }

            await using (var enrollment = connection.CreateCommand())
            {
                enrollment.Transaction = transaction;
                enrollment.CommandText = """
                    INSERT INTO HubAgentEnrollments(
                        agent_id, desktop_hub_id, endpoint, tls_certificate_sha256,
                        enrolled_at_ms, expires_at_ms, revoked_at_ms)
                    VALUES($agent, $hub, $endpoint, $pin, $enrolled, $expires, NULL)
                    ON CONFLICT(agent_id) DO UPDATE SET
                        desktop_hub_id = excluded.desktop_hub_id,
                        endpoint = excluded.endpoint,
                        tls_certificate_sha256 = excluded.tls_certificate_sha256,
                        enrolled_at_ms = excluded.enrolled_at_ms,
                        expires_at_ms = excluded.expires_at_ms,
                        revoked_at_ms = NULL;
                    """;
                enrollment.Parameters.AddWithValue("$agent", agentId);
                enrollment.Parameters.AddWithValue("$hub", desktopHubId);
                enrollment.Parameters.AddWithValue("$endpoint", endpoint.AbsoluteUri);
                enrollment.Parameters.AddWithValue("$pin", tlsCertificateSha256);
                enrollment.Parameters.AddWithValue("$enrolled", nowMs);
                enrollment.Parameters.AddWithValue("$expires", expiresAt.ToUnixTimeMilliseconds());
                await enrollment.ExecuteNonQueryAsync(cancellationToken);
            }

            await transaction.CommitAsync(cancellationToken);
            return new BridgeV1AgentEnrollmentResult(
                true,
                BridgeV1AgentEnrollmentFailure.None,
                new BridgeV1AgentEnrollmentCredential(
                    agentId,
                    desktopHubId,
                    endpoint,
                    tlsCertificateSha256,
                    Convert.ToBase64String(secret),
                    new HashSet<string>(scopes, StringComparer.Ordinal),
                    now,
                    expiresAt));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(digest);
            if (secret is not null) CryptographicOperations.ZeroMemory(secret);
            if (protectedSecret is not null) CryptographicOperations.ZeroMemory(protectedSecret);
        }
    }

    private static async Task<string[]> ReadScopesAsync(
        SqliteConnection connection,
        string ticketId,
        CancellationToken cancellationToken)
        => await ReadScopesAsync(connection, null, ticketId, cancellationToken);

    private static async Task<string[]> ReadScopesAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        string ticketId,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
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

    private static async ValueTask<BridgeV1AgentEnrollmentFailure> ClassifyEnrollmentFailureAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string ticketId,
        long nowMs,
        CancellationToken cancellationToken)
    {
        await using var status = connection.CreateCommand();
        status.Transaction = transaction;
        status.CommandText = """
            SELECT expires_at_ms, consumed_at_ms
            FROM HubPairingTickets
            WHERE ticket_id = $ticket;
            """;
        status.Parameters.AddWithValue("$ticket", ticketId);
        await using var reader = await status.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken)) return BridgeV1AgentEnrollmentFailure.Rejected;
        if (!reader.IsDBNull(1)) return BridgeV1AgentEnrollmentFailure.AlreadyConsumed;
        return reader.GetInt64(0) <= nowMs
            ? BridgeV1AgentEnrollmentFailure.Expired
            : BridgeV1AgentEnrollmentFailure.Rejected;
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
                agent_id TEXT NULL,
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
            CREATE TABLE IF NOT EXISTS HubCredentials(
                device_id TEXT NOT NULL PRIMARY KEY,
                protected_secret BLOB NOT NULL,
                scopes_json TEXT NOT NULL,
                enabled INTEGER NOT NULL CHECK(enabled IN (0, 1)),
                updated_at_ms INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS IX_HubCredentials_Enabled
                ON HubCredentials(enabled, device_id);
            CREATE TABLE IF NOT EXISTS HubAgentEnrollments(
                agent_id TEXT NOT NULL PRIMARY KEY,
                desktop_hub_id TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                tls_certificate_sha256 TEXT NOT NULL,
                enrolled_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                revoked_at_ms INTEGER NULL,
                CHECK(expires_at_ms > enrolled_at_ms),
                CHECK(revoked_at_ms IS NULL OR revoked_at_ms >= enrolled_at_ms)
            );
            CREATE INDEX IF NOT EXISTS IX_HubAgentEnrollments_Expiry
                ON HubAgentEnrollments(expires_at_ms, revoked_at_ms);
            """;
        command.ExecuteNonQuery();

        using var columns = connection.CreateCommand();
        columns.CommandText = "PRAGMA table_info(HubPairingTickets);";
        using var reader = columns.ExecuteReader();
        var hasAgentId = false;
        while (reader.Read())
            hasAgentId |= StringComparer.Ordinal.Equals(reader.GetString(1), "agent_id");
        reader.Close();
        if (!hasAgentId)
        {
            using var migration = connection.CreateCommand();
            migration.CommandText = "ALTER TABLE HubPairingTickets ADD COLUMN agent_id TEXT NULL;";
            migration.ExecuteNonQuery();
        }
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

    private static void ValidateIdentity(string value, string parameterName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value, parameterName);
        if (value.Length > 128 ||
            !value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_'))
            throw new ArgumentException("Identity must be a bounded ASCII identifier.", parameterName);
    }

    private static void ValidateEndpoint(Uri endpoint)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        if (!endpoint.IsAbsoluteUri || endpoint.Scheme != Uri.UriSchemeHttps ||
            string.IsNullOrWhiteSpace(endpoint.Host) || !string.IsNullOrEmpty(endpoint.UserInfo) ||
            !string.IsNullOrEmpty(endpoint.Query) || !string.IsNullOrEmpty(endpoint.Fragment))
            throw new ArgumentException("Enrollment requires a credential-free HTTPS endpoint.", nameof(endpoint));
    }

    private static void ValidateTlsPin(string value)
    {
        if (value is not { Length: 64 } || value.Any(character =>
                character is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new ArgumentException(
                "TLS certificate SHA-256 pin must be 64 lowercase hexadecimal characters.",
                nameof(value));
    }
}
