using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Durable, process-independent nonce replay protection for Desktop Hub admission.
/// A nonce is unique per device until its signed request window expires.
/// </summary>
public sealed class SqliteHubReplayGuard
    : IHubReplayGuard
{
    private readonly string _connectionString;
    private readonly TimeProvider _timeProvider;

    public SqliteHubReplayGuard(string databasePath, TimeProvider? timeProvider = null)
    {
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
        _timeProvider = timeProvider ?? TimeProvider.System;
        Initialize();
    }

    public async ValueTask<bool> TryClaimAsync(
        string deviceId,
        string nonce,
        DateTimeOffset expiresAt,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        ArgumentException.ThrowIfNullOrWhiteSpace(nonce);
        if (deviceId.Length > 128 || nonce.Length is < 16 or > 128)
            return false;

        var now = _timeProvider.GetUtcNow();
        if (expiresAt <= now)
            return false;

        await using var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await ExecuteAsync(connection, "PRAGMA busy_timeout = 5000;", cancellationToken);

        using var transaction = connection.BeginTransaction(deferred: false);
        await using (var cleanup = connection.CreateCommand())
        {
            cleanup.Transaction = transaction;
            cleanup.CommandText = "DELETE FROM ReplayClaims WHERE expires_at_ms <= $now;";
            cleanup.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            await cleanup.ExecuteNonQueryAsync(cancellationToken);
        }

        int inserted;
        await using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT OR IGNORE INTO ReplayClaims(device_id, nonce, expires_at_ms)
                VALUES($device, $nonce, $expires);
                """;
            insert.Parameters.AddWithValue("$device", deviceId);
            insert.Parameters.AddWithValue("$nonce", nonce);
            insert.Parameters.AddWithValue("$expires", expiresAt.ToUnixTimeMilliseconds());
            inserted = await insert.ExecuteNonQueryAsync(cancellationToken);
        }

        await transaction.CommitAsync(cancellationToken);
        return inserted == 1;
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
            CREATE TABLE IF NOT EXISTS ReplayClaims(
                device_id TEXT NOT NULL,
                nonce TEXT NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                PRIMARY KEY(device_id, nonce)
            );
            CREATE INDEX IF NOT EXISTS IX_ReplayClaims_Expiry
                ON ReplayClaims(expires_at_ms);
            """;
        command.ExecuteNonQuery();
    }

    private static async Task ExecuteAsync(
        SqliteConnection connection,
        string sql,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = sql;
        await command.ExecuteNonQueryAsync(cancellationToken);
    }
}
