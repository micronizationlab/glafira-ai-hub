using System.Text;
using System.Text.Json;
using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class DesktopHubWorkspaceStoreTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-workspace-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly DesktopHubStore _store;

    public DesktopHubWorkspaceStoreTests() =>
        _store = new DesktopHubStore(_databasePath, new TestDataProtector());

    [Fact]
    public void OwnerSnapshotIsVersionedReadableAndEncryptedAtRest()
    {
        var now = DateTimeOffset.UtcNow;
        var version = _store.ReplaceWorkspaceProject(Project("private-name", "private-message"), now);

        var project = Assert.IsType<HubWorkspaceProjectSnapshot>(
            _store.GetWorkspaceProject("project-0001"));
        var conversation = Assert.IsType<HubWorkspaceConversationSnapshot>(
            _store.GetWorkspaceConversation("project-0001", "conversation-0001"));

        Assert.Equal(1, version);
        Assert.Equal("private-name", project.Name);
        Assert.Equal("private-title", Assert.Single(project.Conversations).Title);
        Assert.Equal("private-message", Assert.Single(conversation.Messages).Text);
        using var connection = new SqliteConnection($"Data Source={_databasePath}");
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT snapshot_ciphertext FROM WorkspaceProjects
            UNION ALL
            SELECT snapshot_ciphertext FROM WorkspaceConversations;
            """;
        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            var stored = Encoding.UTF8.GetString((byte[])reader[0]);
            Assert.DoesNotContain("private-name", stored, StringComparison.Ordinal);
            Assert.DoesNotContain("private-title", stored, StringComparison.Ordinal);
            Assert.DoesNotContain("private-message", stored, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void AppendUsesCompareAndSwapAndDurableIdempotency()
    {
        var now = DateTimeOffset.UtcNow;
        _store.ReplaceWorkspaceProject(Project("Project", "first"), now);

        var committed = _store.AppendWorkspaceMessage(
            "project-0001", "conversation-0001", "append-idem-0001", 1, "second", now.AddSeconds(1));
        var duplicate = _store.AppendWorkspaceMessage(
            "project-0001", "conversation-0001", "append-idem-0001", 1, "second", now.AddSeconds(2));
        var stale = _store.AppendWorkspaceMessage(
            "project-0001", "conversation-0001", "append-idem-0002", 1, "stale", now.AddSeconds(3));
        var mismatchedRetry = _store.AppendWorkspaceMessage(
            "project-0001", "conversation-0001", "append-idem-0001", 1, "changed", now.AddSeconds(4));

        Assert.Equal(new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.Committed, 2), committed);
        Assert.Equal(new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.Duplicate, 2), duplicate);
        Assert.Equal(new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.VersionConflict, 2), stale);
        Assert.Equal(HubWorkspaceAppendStatus.IdempotencyConflict, mismatchedRetry.Status);
        var messages = _store.GetWorkspaceConversation("project-0001", "conversation-0001")!.Messages;
        Assert.Equal(new[] { "first", "second" }, messages.Select(message => message.Text));
    }

    [Fact]
    public async Task BridgeWorkspaceOperationsReturnAuthoritativeVersionsWithoutAgentRegistration()
    {
        var now = DateTimeOffset.UtcNow;
        _store.ReplaceWorkspaceProject(Project("Project", "first"), now);
        var dispatcher = new BridgeV1TaskOperationDispatcher(_store, new NoPayloadResolver());

        var project = await dispatcher.DispatchAsync(Request(
            BridgeV1Operation.PROJECT_READ, "project-request-01", "project-idem-0001", []), now);
        var conversation = await dispatcher.DispatchAsync(Request(
            BridgeV1Operation.CONVERSATION_READ, "conversation-read-01", "conversation-read-idem-01",
            new Dictionary<string, string> { ["conversationId"] = "conversation-0001" }), now);
        var append = await dispatcher.DispatchAsync(Request(
            BridgeV1Operation.CONVERSATION_APPEND, "conversation-append-01", "conversation-append-idem-01",
            new Dictionary<string, string>
            {
                ["conversationId"] = "conversation-0001",
                ["messageText"] = "second",
                ["baseRemoteVersion"] = "1"
            }), now.AddSeconds(1));
        var conflict = await dispatcher.DispatchAsync(Request(
            BridgeV1Operation.CONVERSATION_APPEND, "conversation-append-02", "conversation-append-idem-02",
            new Dictionary<string, string>
            {
                ["conversationId"] = "conversation-0001",
                ["messageText"] = "stale",
                ["baseRemoteVersion"] = "1"
            }), now.AddSeconds(2));

        Assert.Equal("ACCEPTED", project.Status);
        Assert.Equal("Project", project.Result["name"]);
        Assert.Single(JsonSerializer.Deserialize<HubWorkspaceConversationSummary[]>(project.Result["conversations"])!);
        Assert.Equal("ACCEPTED", conversation.Status);
        Assert.Equal("1", conversation.Result["version"]);
        Assert.Equal("ACCEPTED", append.Status);
        Assert.Equal("2", append.Result["version"]);
        Assert.Equal("false", append.Result["duplicate"]);
        Assert.Equal("FAILED", conflict.Status);
        Assert.Equal("WORKSPACE_VERSION_CONFLICT", conflict.ErrorCode);
        Assert.Equal("2", conflict.Result["currentVersion"]);
    }

    [Fact]
    public void ReplacingProjectInvalidatesOldAppendReceiptsAndIncrementsProjectVersion()
    {
        var now = DateTimeOffset.UtcNow;
        Assert.Equal(1L, _store.ReplaceWorkspaceProject(Project("One", "first"), now));
        Assert.Equal(HubWorkspaceAppendStatus.Committed, _store.AppendWorkspaceMessage(
            "project-0001", "conversation-0001", "append-idem-0001", 1, "second", now).Status);

        Assert.Equal(3L, _store.ReplaceWorkspaceProject(Project("Two", "replacement"), now.AddSeconds(1)));
        var afterReplacement = _store.AppendWorkspaceMessage(
            "project-0001", "conversation-0001", "append-idem-0001", 3, "new-generation", now.AddSeconds(2));

        Assert.Equal(HubWorkspaceAppendStatus.Committed, afterReplacement.Status);
        Assert.Equal(4L, afterReplacement.Version.GetValueOrDefault());
        Assert.Equal("Two", _store.GetWorkspaceProject("project-0001")!.Name);
    }

    private static HubWorkspaceProjectInput Project(string name, string message) => new(
        "project-0001",
        name,
        [new HubWorkspaceConversationInput(
            "conversation-0001",
            "private-title",
            [new HubWorkspaceMessage("message-0001", "user", message, 1_730_000_000_000)])]);

    private static BridgeV1SignedRequest Request(
        BridgeV1Operation operation,
        string requestId,
        string idempotencyKey,
        IReadOnlyDictionary<string, string> payload) => new(
            1,
            requestId,
            idempotencyKey,
            "device-0001",
            "project-0001",
            operation,
            1_730_000_000_000,
            1_730_000_300_000,
            payload,
            new string('0', 64));

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var path in new[] { _databasePath, _databasePath + "-wal", _databasePath + "-shm" })
        {
            try { if (File.Exists(path)) File.Delete(path); }
            catch (IOException) { }
        }
    }

    private sealed class NoPayloadResolver : IHubTaskPayloadResolver
    {
        public ValueTask<byte[]?> ResolveAsync(string payloadRef, CancellationToken cancellationToken) =>
            ValueTask.FromResult<byte[]?>(null);
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
}
