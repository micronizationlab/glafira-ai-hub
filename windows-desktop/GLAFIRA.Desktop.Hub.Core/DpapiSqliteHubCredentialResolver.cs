using System.Security.Cryptography;
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
public sealed class DpapiSqliteHubCredentialResolver : IHubCredentialResolver
{
    private static readonly byte[] Entropy =
        Encoding.UTF8.GetBytes("GLAFIRA.Desktop.Hub.CredentialStore.v1");

    private readonly string _connectionString;

    public DpapiSqliteHubCredentialResolver(string databasePath)
    {
        if (!OperatingSystem.IsWindows())
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

        Initialize();
    }

    public async ValueTask StoreCredentialAsync(
        string deviceId,
        ReadOnlyMemory<byte> secret,
        IEnumerable<string> allowedScopes,
        CancellationToken cancellationToken = default)
    {
        ValidateDeviceId(deviceId);
        if (secret.Length < 32 || secret.Length > 128)
            throw new ArgumentOutOfRangeException(nameof(secret), "Credential secret must contain 32 to 128 bytes.");

        var scopes = NormalizeScopes(allowedScopes);
        var protectedSecret = ProtectedData.Protect(
            secret.ToArray(),
            Entropy,
            DataProtectionScope.CurrentUser);
        var scopesJson = JsonSerializer.Serialize(scopes);

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
        command.Parameters.AddWithValue("$updated", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        await command.ExecuteNonQueryAsync(cancellationToken);
        CryptographicOperations.ZeroMemory(protectedSecret);
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
            SELECT protected_secret, scopes_json
            FROM HubCredentials
            WHERE device_id = $device AND enabled = 1;
            """;
        command.Parameters.AddWithValue("$device", deviceId);

        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
            return null;

        var protectedSecret = (byte[])reader["protected_secret"];
        var scopesJson = reader.GetString(1);
        try
        {
            var secret = ProtectedData.Unprotect(
                protectedSecret,
                Entropy,
                DataProtectionScope.CurrentUser);
            if (secret.Length < 32)
            {
                CryptographicOperations.ZeroMemory(secret);
                return null;
            }

            var scopes = JsonSerializer.Deserialize<string[]>(scopesJson);
            if (scopes is null || scopes.Length == 0 ||
                scopes.Distinct(StringComparer.Ordinal).Count() != scopes.Length)
            {
                CryptographicOperations.ZeroMemory(secret);
                return null;
            }

            return new HubRequestCredential(deviceId, secret, new HashSet<string>(scopes, StringComparer.Ordinal));
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
        await using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE HubCredentials
            SET protected_secret = X'', scopes_json = '[]', enabled = 0,
                updated_at_ms = $updated
            WHERE device_id = $device;
            """;
        command.Parameters.AddWithValue("$device", deviceId);
        command.Parameters.AddWithValue("$updated", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        return await command.ExecuteNonQueryAsync(cancellationToken) == 1;
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
