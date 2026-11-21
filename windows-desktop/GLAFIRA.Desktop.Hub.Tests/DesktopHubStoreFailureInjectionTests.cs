using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class DesktopHubStoreFailureInjectionTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-hub-failure-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly DesktopHubStore _store;

    public DesktopHubStoreFailureInjectionTests() => _store = new DesktopHubStore(_databasePath);

    [Fact]
    public void ResultJournalFailureRollsBackTaskResultAndLeaseCompletion()
    {
        var now = DateTimeOffset.UtcNow;
        _store.RegisterAgent(new HubAgentRegistration(
            "agent-result-fault", "device-result-fault", ["web.research"], 1, true), now);
        _store.Enqueue(new HubTaskRequest(
            "result-fault-task",
            "result-fault-project",
            "result-fault-idempotency",
            "web.research",
            "payload://result-fault-task",
            new string('a', 64),
            ["web.research"],
            [],
            HubTaskEffectRisk.ReadOnly,
            null,
            5), now);

        var lease = _store.ClaimNext(
            "agent-result-fault", now.AddSeconds(1), TimeSpan.FromMinutes(1));
        Assert.NotNull(lease);

        using (var connection = new SqliteConnection($"Data Source={_databasePath};Foreign Keys=True;"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = """
                CREATE TRIGGER inject_terminal_event_failure
                BEFORE INSERT ON TaskEvents
                WHEN NEW.event_code = 'TASK_SUCCEEDED'
                BEGIN
                    SELECT RAISE(ABORT, 'injected-result-ack-failure');
                END;
                """;
            command.ExecuteNonQuery();
        }

        var failure = Assert.Throws<SqliteException>(() => _store.Complete(
            "agent-result-fault",
            "result-fault-task",
            lease!.LeaseId,
            lease.FencingToken,
            succeeded: true,
            now.AddSeconds(2),
            result: "result must not commit"));
        Assert.Contains("injected-result-ack-failure", failure.Message);

        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("result-fault-task"));
        Assert.Null(_store.GetTaskResult("result-fault-task"));
        Assert.DoesNotContain(
            _store.GetTaskHistory("result-fault-task"),
            item => item.EventCode == "TASK_SUCCEEDED");

        using (var connection = new SqliteConnection($"Data Source={_databasePath};Foreign Keys=True;"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "DROP TRIGGER inject_terminal_event_failure;";
            command.ExecuteNonQuery();
        }

        // The lease must remain valid after the failed terminal commit; a progress ACK
        // proves the Hub did not partially delete or replace it.
        Assert.True(_store.ReportProgress(
            "agent-result-fault",
            "result-fault-task",
            lease.LeaseId,
            lease.FencingToken,
            80,
            "lease remains current",
            now.AddSeconds(3)));
        Assert.Null(_store.GetTaskResult("result-fault-task"));
        Assert.DoesNotContain(
            _store.GetTaskHistory("result-fault-task"),
            item => item.EventCode == "TASK_SUCCEEDED");

        var reopened = new DesktopHubStore(_databasePath);
        Assert.Equal(HubTaskState.Leased, reopened.GetTaskState("result-fault-task"));
        Assert.Null(reopened.GetTaskResult("result-fault-task"));
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var file in Directory.GetFiles(
            Path.GetDirectoryName(_databasePath)!, Path.GetFileName(_databasePath) + "*"))
            File.Delete(file);
    }
}
