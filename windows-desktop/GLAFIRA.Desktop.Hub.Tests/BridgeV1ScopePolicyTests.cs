using GLAFIRA.Desktop.Hub.Core;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1ScopePolicyTests
{
    [Fact]
    public void EveryBridgeOperationMapsToASupportedEnrollmentScope()
    {
        foreach (var operation in Enum.GetValues<BridgeV1Operation>())
            Assert.Contains(BridgeV1ScopePolicy.RequiredFor(operation), BridgeV1ScopePolicy.SupportedScopes);

        Assert.Equal(
            new[]
            {
                "approval.request",
                "conversation.append",
                "conversation.read",
                "desktop.task.claim",
                "desktop.task.result",
                "ownership.read",
                "project.read"
            },
            BridgeV1ScopePolicy.SupportedScopes.Order(StringComparer.Ordinal));
    }

    [Fact]
    public async Task UnknownScopeIsRejectedBeforeCredentialStorage()
    {
        var databasePath = Path.Combine(
            Path.GetTempPath(), "glafira-scope-policy-" + Guid.NewGuid().ToString("N") + ".db");
        try
        {
            var resolver = new DpapiSqliteHubCredentialResolver(databasePath);

            await Assert.ThrowsAsync<ArgumentException>(async () =>
                await resolver.StoreCredentialAsync(
                    "agent-01", new byte[32], ["desktop.task.delete"]));

            Assert.Null(await resolver.ResolveAsync("agent-01", default));
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            foreach (var path in new[] { databasePath, databasePath + "-wal", databasePath + "-shm" })
            {
                try { if (File.Exists(path)) File.Delete(path); }
                catch (IOException) { }
            }
        }
    }

    [Fact]
    public async Task PersistedUnknownScopeFailsClosedOnCredentialRead()
    {
        var databasePath = Path.Combine(
            Path.GetTempPath(), "glafira-scope-policy-" + Guid.NewGuid().ToString("N") + ".db");
        try
        {
            var resolver = new DpapiSqliteHubCredentialResolver(databasePath);
            await resolver.StoreCredentialAsync("agent-01", new byte[32], ["project.read"]);

            await using (var connection = new Microsoft.Data.Sqlite.SqliteConnection(
                new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder { DataSource = databasePath }.ToString()))
            {
                await connection.OpenAsync();
                await using var command = connection.CreateCommand();
                command.CommandText = "UPDATE HubCredentials SET scopes_json = '[\"desktop.task.delete\"]' WHERE device_id = 'agent-01';";
                Assert.Equal(1, await command.ExecuteNonQueryAsync());
            }

            Assert.Null(await resolver.ResolveAsync("agent-01", default));
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            foreach (var path in new[] { databasePath, databasePath + "-wal", databasePath + "-shm" })
            {
                try { if (File.Exists(path)) File.Delete(path); }
                catch (IOException) { }
            }
        }
    }
}
