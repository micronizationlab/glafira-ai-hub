using System.Security.Cryptography;
using System.Runtime.Versioning;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Windows-user-bound credential resolver for the Desktop Hub.
/// Secrets are protected with DPAPI CurrentUser before they enter SQLite.
/// Run the Hub under a stable dedicated Windows identity; changing that identity
/// intentionally makes existing credentials unreadable (fail closed).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class DpapiSqliteHubCredentialResolver : IHubCredentialResolver
{
    private readonly string _connectionString;
    private readonly IHubDataProtector _dataProtector;
    private readonly TimeProvider _timeProvider;

    public DpapiSqliteHubCredentialResolver(
        string databasePath,
        IHubDataProtector? dataProtector = null,
        TimeProvider? timeProvider = null)
    {
        if (dataProtector is null && !OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Desktop Hub DPAPI credential storage is Windows-only.");

        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        var fullPath = Path.GetFullPath(databasePath);
        var directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrEmpty(directory))
            Directory.CreateDirectory(directory);

        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = fullPath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared,
            Pooling = true
        }.ToString();
        _dataProtector = dataProtector ?? new WindowsDpapiHubDataProtector();
        _timeProvider = timeProvider ?? TimeProvider.System;

        Initialize();
    }

    public async ValueTask StoreCredentialAsync(
        string deviceId,
        ReadOnlyMemory<byte> secret,
        IEnumerable<string> allowedScopes,
        CancellationToken cancellationToken = default)
    {
        ValidateDeviceId(deviceId);
        if (secret.Length != 32)
            throw new ArgumentOutOfRangeException(nameof(secret), "Bridge v1 HMAC credentials must contain exactly 32 bytes.");

        var scopes = NormalizeScopes(allowedScopes);
        var secretCopy = secret.ToArray();
        byte[] protectedSecret;
        try
        {
            protectedSecret = HubCredentialCryptography.Protect(_dataProtector, secretCopy);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(secretCopy);
        }

        var scopesJson = JsonSerializer.Serialize(scopes);
        try
        {
            await using var connection = new SqliteConnection(_connectionString);
            await connection.OpenAsync(cancellationToken);
            await using var command = connection.CreateCommand();
            command.CommandText = """
                INSERT INTO HubCredentials(device_id, protected_secret, scopes_json, enabled, updated_at_ms)
                VALUES($device, $secret, $scopes, 1, $updated)
                ON CONFLICT(device_id) DO UPDATE SET
                    protected_secret = excluded.protected_secret,
                    scopes_json = excluded.scopes_json,
                    enabled = 1,
                    updated_at_ms = excluded.updated_at_ms;
                """;
            command.Parameters.AddWithValue("$device", deviceId);
            command.Parameters.Add("$secret", SqliteType.Blob).Value = protectedSecret;
            command.Parameters.AddWithValue("$scopes", scopesJson);
            command.Parameters.AddWithValue("$updated", _timeProvider.GetUtcNow().ToUnixTimeMilliseconds());
            await command.ExecuteNonQueryAsync(cancellationToken);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedSecret);
        }
    }

    public async ValueTask<HubRequestCredential?> ResolveAsync(
        string deviceId,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(deviceId) || deviceId.Length > 128)
            return null;

        await using var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT credentials.protected_secret, credentials.scopes_json
            FROM HubCredentials AS credentials
            LEFT JOIN HubAgentEnrollments AS enrollment
              ON enrollment.agent_id = credentials.device_id
            WHERE credentials.device_id = $device
              AND credentials.enabled = 1
              AND (enrollment.agent_id IS NULL OR
                   (enrollment.revoked_at_ms IS NULL AND enrollment.expires_at_ms > $now));
            """;
        command.Parameters.AddWithValue("$device", deviceId);
        command.Parameters.AddWithValue("$now", _timeProvider.GetUtcNow().ToUnixTimeMilliseconds());

        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
            return null;

        var protectedSecret = (byte[])reader["protected_secret"];
        var scopesJson = reader.GetString(1);
        try
        {
            var secret = HubCredentialCryptography.Unprotect(_dataProtector, protectedSecret);
            if (secret.Length != 32)
            {
                CryptographicOperations.ZeroMemory(secret);
                return null;
            }

            var scopes = JsonSerializer.Deserialize<string[]>(scopesJson);
            if (scopes is null)
            {
                CryptographicOperations.ZeroMemory(secret);
                return null;
            }

            string[] normalizedScopes;
            try
            {
                normalizedScopes = NormalizeScopes(scopes);
            }
            catch (ArgumentException)
            {
                CryptographicOperations.ZeroMemory(secret);
                return null;
            }

            if (!normalizedScopes.SequenceEqual(scopes, StringComparer.Ordinal))
            {
                CryptographicOperations.ZeroMemory(secret);
                return null;
            }

            return new HubRequestCredential(deviceId, secret, new HashSet<string>(normalizedScopes, StringComparer.Ordinal));
        }
        catch (CryptographicException)
        {
            // A different Windows user, corrupted ciphertext or wrong entropy is a hard deny.
            return null;
        }
        catch (JsonException)
        {
            return null;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedSecret);
        }
    }

    public async ValueTask<bool> RevokeAsync(
        string deviceId,
        CancellationToken cancellationToken = default)
    {
        ValidateDeviceId(deviceId);
        await using var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = """
            UPDATE HubCredentials
            SET protected_secret = X'', scopes_json = '[]', enabled = 0,
                updated_at_ms = $updated
            WHERE device_id = $device AND enabled = 1;
            """;
        command.Parameters.AddWithValue("$device", deviceId);
        command.Parameters.AddWithValue("$updated", _timeProvider.GetUtcNow().ToUnixTimeMilliseconds());
        var revoked = await command.ExecuteNonQueryAsync(cancellationToken) == 1;
        if (revoked)
        {
            await using var enrollment = connection.CreateCommand();
            enrollment.Transaction = (SqliteTransaction)transaction;
            enrollment.CommandText = """
                UPDATE HubAgentEnrollments
                SET revoked_at_ms = $revoked
                WHERE agent_id = $device AND revoked_at_ms IS NULL;
                """;
            enrollment.Parameters.AddWithValue("$device", deviceId);
            enrollment.Parameters.AddWithValue("$revoked", _timeProvider.GetUtcNow().ToUnixTimeMilliseconds());
            await enrollment.ExecuteNonQueryAsync(cancellationToken);
        }
        await transaction.CommitAsync(cancellationToken);
        return revoked;
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
    }

    private static string[] NormalizeScopes(IEnumerable<string> allowedScopes)
    {
        ArgumentNullException.ThrowIfNull(allowedScopes);
        var scopes = allowedScopes.ToArray();
        if (scopes.Length is < 1 or > 32 ||
            scopes.Any(scope => string.IsNullOrWhiteSpace(scope) || scope.Length > 128 ||
                !scope.All(ch => char.IsAsciiLetterOrDigit(ch) || ch is '.' or '_' or ':' or '-')) ||
            scopes.Distinct(StringComparer.Ordinal).Count() != scopes.Length)
            throw new ArgumentException("Scopes must be 1 to 32 unique, bounded ASCII scope identifiers.", nameof(allowedScopes));

        return scopes.Order(StringComparer.Ordinal).ToArray();
    }

    private static void ValidateDeviceId(string deviceId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        if (deviceId.Length > 128 ||
            !deviceId.All(ch => char.IsAsciiLetterOrDigit(ch) || ch is '-' or '_'))
            throw new ArgumentException("Device ID must be a bounded ASCII identifier.", nameof(deviceId));
    }
}

internal static class HubCredentialCryptography
{
    private static readonly byte[] Entropy =
        Encoding.UTF8.GetBytes("GLAFIRA.Desktop.Hub.CredentialStore.v1");

    public static byte[] Protect(IHubDataProtector protector, ReadOnlySpan<byte> plaintext) =>
        protector.Protect(plaintext, Entropy);

    public static byte[] Unprotect(IHubDataProtector protector, ReadOnlySpan<byte> ciphertext) =>
        protector.Unprotect(ciphertext, Entropy);
}
