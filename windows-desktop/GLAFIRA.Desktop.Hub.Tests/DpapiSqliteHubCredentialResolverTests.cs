using System.Security.Cryptography;
using System.Runtime.Versioning;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

[SupportedOSPlatform("windows")]
public sealed class DpapiSqliteHubCredentialResolverTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-credentials-" + Guid.NewGuid().ToString("N") + ".db");

    private static byte[] Secret(byte marker = 0x4A) => Enumerable.Repeat(marker, 32).ToArray();

    [Fact]
    public async Task StoresOnlyProtectedSecretAndResolvesApprovedScopes()
    {
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath);
        var secret = Secret();

        await resolver.StoreCredentialAsync("agent-01", secret, ["hub.read", "hub.task.claim"]);

        var credential = await resolver.ResolveAsync("agent-01", default);

        Assert.NotNull(credential);
        Assert.Equal(secret, credential.HmacSecret);
        Assert.Contains("hub.read", credential.AllowedScopes);
        Assert.Contains("hub.task.claim", credential.AllowedScopes);

        await using var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = _databasePath }.ToString());
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT protected_secret FROM HubCredentials WHERE device_id = 'agent-01';";
        var storedValue = await command.ExecuteScalarAsync();
        var stored = Assert.IsType<byte[]>(storedValue);
        Assert.NotEqual(Convert.ToHexString(secret), Convert.ToHexString(stored));
    }

    [Fact]
    public async Task RotationReplacesSecretAndScopes()
    {
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath);
        await resolver.StoreCredentialAsync("agent-01", Secret(0x11), ["hub.read"]);
        await resolver.StoreCredentialAsync("agent-01", Secret(0x22), ["hub.task.claim"]);

        var credential = await resolver.ResolveAsync("agent-01", default);

        Assert.NotNull(credential);
        Assert.Equal(Secret(0x22), credential.HmacSecret);
        Assert.Equal(new[] { "hub.task.claim" }, credential.AllowedScopes.Order(StringComparer.Ordinal));
    }

    [Fact]
    public async Task RevokedCredentialCannotBeResolved()
    {
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath);
        await resolver.StoreCredentialAsync("agent-01", Secret(), ["hub.read"]);

        Assert.True(await resolver.RevokeAsync("agent-01"));
        Assert.Null(await resolver.ResolveAsync("agent-01", default));
        Assert.False(await resolver.RevokeAsync("agent-01"));
    }

    [Fact]
    public async Task UnknownDeviceReturnsNoCredential()
    {
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath);

        Assert.Null(await resolver.ResolveAsync("unknown-agent", default));
    }

    [Fact]
    public async Task DuplicateScopesAreRejectedBeforeStorage()
    {
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath);

        await Assert.ThrowsAsync<ArgumentException>(async () =>
            await resolver.StoreCredentialAsync("agent-01", Secret(), ["hub.read", "hub.read"]));

        Assert.Null(await resolver.ResolveAsync("agent-01", default));
    }

    [Fact]
    public async Task ShortSecretIsRejected()
    {
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath);

        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(async () =>
            await resolver.StoreCredentialAsync("agent-01", new byte[16], ["hub.read"]));
    }

    [Fact]
    public async Task NonThirtyTwoByteSecretIsRejected()
    {
        var resolver = new DpapiSqliteHubCredentialResolver(_databasePath);

        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(async () =>
            await resolver.StoreCredentialAsync("agent-01", new byte[33], ["hub.read"]));
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
}
