using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record HubWorkspaceMessage(
    string MessageId,
    string Role,
    string Text,
    long CreatedAtEpochMs);

public sealed record HubWorkspaceConversationInput(
    string ConversationId,
    string Title,
    IReadOnlyList<HubWorkspaceMessage> Messages);

public sealed record HubWorkspaceProjectInput(
    string ProjectId,
    string Name,
    IReadOnlyList<HubWorkspaceConversationInput> Conversations);

public sealed record HubWorkspaceConversationSummary(
    string ConversationId,
    string Title,
    long Version,
    long UpdatedAtEpochMs);

public sealed record HubWorkspaceProjectSnapshot(
    string ProjectId,
    string Name,
    long Version,
    long UpdatedAtEpochMs,
    IReadOnlyList<HubWorkspaceConversationSummary> Conversations);

public sealed record HubWorkspaceConversationSnapshot(
    string ProjectId,
    string ConversationId,
    string Title,
    long Version,
    long UpdatedAtEpochMs,
    IReadOnlyList<HubWorkspaceMessage> Messages);

public enum HubWorkspaceAppendStatus
{
    Committed,
    Duplicate,
    ProjectNotFound,
    ConversationNotFound,
    VersionConflict,
    IdempotencyConflict
}

public sealed record HubWorkspaceAppendResult(
    HubWorkspaceAppendStatus Status,
    long? Version = null);

public sealed partial class DesktopHubStore
{
    private const int MaxWorkspaceConversationPlaintextBytes = 512_000;
    private sealed record ProtectedProject(string Name);
    private sealed record ProtectedConversation(string Title, HubWorkspaceMessage[] Messages);

    /// <summary>
    /// Replaces one owner-authorized project snapshot atomically. This API is intentionally
    /// not reachable through Bridge: mobile clients can read or append, but cannot create
    /// projects or replace authoritative history.
    /// </summary>
    public long ReplaceWorkspaceProject(HubWorkspaceProjectInput project, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(project);
        ValidateWorkspaceIdentifier(project.ProjectId, nameof(project.ProjectId));
        ValidateWorkspaceLabel(project.Name, 160, nameof(project.Name));
        ArgumentNullException.ThrowIfNull(project.Conversations);
        if (project.Conversations.Count > 1_000 ||
            project.Conversations.Select(item => item.ConversationId).Distinct(StringComparer.Ordinal).Count() !=
            project.Conversations.Count)
            throw new ArgumentException("Workspace conversations must be unique and bounded.", nameof(project));
        foreach (var conversation in project.Conversations) ValidateWorkspaceConversation(conversation);

        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        long version;
        var conversationVersions = new Dictionary<string, long>(StringComparer.Ordinal);
        using (var read = connection.CreateCommand())
        {
            read.Transaction = transaction;
            read.CommandText = "SELECT version FROM WorkspaceProjects WHERE project_id = $project;";
            read.Parameters.AddWithValue("$project", project.ProjectId);
            version = read.ExecuteScalar() is long current ? checked(current + 1) : 1;
        }
        using (var readConversations = connection.CreateCommand())
        {
            readConversations.Transaction = transaction;
            readConversations.CommandText = """
                SELECT conversation_id, version FROM WorkspaceConversations
                WHERE project_id = $project;
                """;
            readConversations.Parameters.AddWithValue("$project", project.ProjectId);
            using var rows = readConversations.ExecuteReader();
            while (rows.Read()) conversationVersions.Add(rows.GetString(0), rows.GetInt64(1));
        }

        var projectPlaintext = JsonSerializer.SerializeToUtf8Bytes(new ProtectedProject(project.Name));
        var protectedProject = ProtectWorkspace("project:" + project.ProjectId, projectPlaintext);
        CryptographicOperations.ZeroMemory(projectPlaintext);
        try
        {
            using var upsert = connection.CreateCommand();
            upsert.Transaction = transaction;
            upsert.CommandText = """
                INSERT INTO WorkspaceProjects(project_id, version, snapshot_ciphertext, updated_at_ms)
                VALUES($project, $version, $snapshot, $updated)
                ON CONFLICT(project_id) DO UPDATE SET
                    version = excluded.version,
                    snapshot_ciphertext = excluded.snapshot_ciphertext,
                    updated_at_ms = excluded.updated_at_ms;
                """;
            upsert.Parameters.AddWithValue("$project", project.ProjectId);
            upsert.Parameters.AddWithValue("$version", version);
            upsert.Parameters.AddWithValue("$snapshot", protectedProject);
            upsert.Parameters.AddWithValue("$updated", now.ToUnixTimeMilliseconds());
            upsert.ExecuteNonQuery();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedProject);
        }

        using (var deleteReceipts = connection.CreateCommand())
        {
            deleteReceipts.Transaction = transaction;
            deleteReceipts.CommandText = "DELETE FROM WorkspaceAppendReceipts WHERE project_id = $project;";
            deleteReceipts.Parameters.AddWithValue("$project", project.ProjectId);
            deleteReceipts.ExecuteNonQuery();
        }
        using (var delete = connection.CreateCommand())
        {
            delete.Transaction = transaction;
            delete.CommandText = "DELETE FROM WorkspaceConversations WHERE project_id = $project;";
            delete.Parameters.AddWithValue("$project", project.ProjectId);
            delete.ExecuteNonQuery();
        }
        foreach (var conversation in project.Conversations)
        {
            var conversationVersion = conversationVersions.TryGetValue(
                conversation.ConversationId, out var previousVersion)
                ? checked(previousVersion + 1)
                : 1;
            InsertWorkspaceConversation(
                connection, transaction, project.ProjectId, conversation, conversationVersion, now);
        }
        transaction.Commit();
        return version;
    }

    public HubWorkspaceProjectSnapshot? GetWorkspaceProject(string projectId)
    {
        ValidateWorkspaceIdentifier(projectId, nameof(projectId));
        using var connection = Open();
        long version;
        long updatedAt;
        byte[] ciphertext;
        using (var project = connection.CreateCommand())
        {
            project.CommandText = """
                SELECT version, snapshot_ciphertext, updated_at_ms
                FROM WorkspaceProjects WHERE project_id = $project;
                """;
            project.Parameters.AddWithValue("$project", projectId);
            using var reader = project.ExecuteReader();
            if (!reader.Read()) return null;
            version = reader.GetInt64(0);
            ciphertext = (byte[])reader[1];
            updatedAt = reader.GetInt64(2);
        }
        var name = DecodeWorkspace<ProtectedProject>("project:" + projectId, ciphertext).Name;
        using var conversations = connection.CreateCommand();
        conversations.CommandText = """
            SELECT conversation_id, version, snapshot_ciphertext, updated_at_ms
            FROM WorkspaceConversations
            WHERE project_id = $project
            ORDER BY conversation_id COLLATE BINARY;
            """;
        conversations.Parameters.AddWithValue("$project", projectId);
        using var rows = conversations.ExecuteReader();
        var summaries = new List<HubWorkspaceConversationSummary>();
        while (rows.Read())
        {
            var conversationId = rows.GetString(0);
            var snapshot = DecodeWorkspace<ProtectedConversation>(
                $"conversation:{projectId}:{conversationId}", (byte[])rows[2]);
            summaries.Add(new HubWorkspaceConversationSummary(
                conversationId, snapshot.Title, rows.GetInt64(1), rows.GetInt64(3)));
        }
        return new HubWorkspaceProjectSnapshot(projectId, name, version, updatedAt, summaries);
    }

    public HubWorkspaceConversationSnapshot? GetWorkspaceConversation(
        string projectId,
        string conversationId)
    {
        ValidateWorkspaceIdentifier(projectId, nameof(projectId));
        ValidateWorkspaceIdentifier(conversationId, nameof(conversationId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT version, snapshot_ciphertext, updated_at_ms
            FROM WorkspaceConversations
            WHERE project_id = $project AND conversation_id = $conversation;
            """;
        command.Parameters.AddWithValue("$project", projectId);
        command.Parameters.AddWithValue("$conversation", conversationId);
        using var reader = command.ExecuteReader();
        if (!reader.Read()) return null;
        var snapshot = DecodeWorkspace<ProtectedConversation>(
            $"conversation:{projectId}:{conversationId}", (byte[])reader[1]);
        return new HubWorkspaceConversationSnapshot(
            projectId, conversationId, snapshot.Title, reader.GetInt64(0), reader.GetInt64(2), snapshot.Messages);
    }

    public HubWorkspaceAppendResult AppendWorkspaceMessage(
        string projectId,
        string conversationId,
        string idempotencyKey,
        long baseVersion,
        string messageText,
        DateTimeOffset now)
    {
        ValidateWorkspaceIdentifier(projectId, nameof(projectId));
        ValidateWorkspaceIdentifier(conversationId, nameof(conversationId));
        ValidateWorkspaceIdentifier(idempotencyKey, nameof(idempotencyKey));
        ValidateWorkspaceLabel(messageText, 16_384, nameof(messageText));
        if (baseVersion <= 0) throw new ArgumentOutOfRangeException(nameof(baseVersion));
        var messageBytes = Encoding.UTF8.GetBytes(messageText);
        var messageDigestBytes = SHA256.HashData(messageBytes);
        var messageDigest = Convert.ToHexString(messageDigestBytes).ToLowerInvariant();
        CryptographicOperations.ZeroMemory(messageBytes);
        CryptographicOperations.ZeroMemory(messageDigestBytes);

        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using (var receipt = connection.CreateCommand())
        {
            receipt.Transaction = transaction;
            receipt.CommandText = """
                SELECT conversation_id, base_version, resulting_version, message_sha256
                FROM WorkspaceAppendReceipts
                WHERE project_id = $project AND idempotency_key = $idempotency;
                """;
            receipt.Parameters.AddWithValue("$project", projectId);
            receipt.Parameters.AddWithValue("$idempotency", idempotencyKey);
            using var reader = receipt.ExecuteReader();
            if (reader.Read())
            {
                var duplicate = StringComparer.Ordinal.Equals(reader.GetString(0), conversationId) &&
                    reader.GetInt64(1) == baseVersion &&
                    StringComparer.Ordinal.Equals(reader.GetString(3), messageDigest);
                var result = duplicate
                    ? new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.Duplicate, reader.GetInt64(2))
                    : new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.IdempotencyConflict);
                return result;
            }
        }

        using (var project = connection.CreateCommand())
        {
            project.Transaction = transaction;
            project.CommandText = "SELECT COUNT(*) FROM WorkspaceProjects WHERE project_id = $project;";
            project.Parameters.AddWithValue("$project", projectId);
            if (Convert.ToInt32(project.ExecuteScalar()) != 1)
            {
                return new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.ProjectNotFound);
            }
        }

        long currentVersion;
        byte[] ciphertext;
        using (var conversation = connection.CreateCommand())
        {
            conversation.Transaction = transaction;
            conversation.CommandText = """
                SELECT version, snapshot_ciphertext FROM WorkspaceConversations
                WHERE project_id = $project AND conversation_id = $conversation;
                """;
            conversation.Parameters.AddWithValue("$project", projectId);
            conversation.Parameters.AddWithValue("$conversation", conversationId);
            using var reader = conversation.ExecuteReader();
            if (!reader.Read())
            {
                return new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.ConversationNotFound);
            }
            currentVersion = reader.GetInt64(0);
            ciphertext = (byte[])reader[1];
        }
        if (currentVersion != baseVersion)
        {
            return new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.VersionConflict, currentVersion);
        }

        var existing = DecodeWorkspace<ProtectedConversation>(
            $"conversation:{projectId}:{conversationId}", ciphertext);
        if (existing.Messages.Length >= 10_000)
            throw new InvalidOperationException("WORKSPACE_CONVERSATION_MESSAGE_LIMIT");
        var nextVersion = checked(currentVersion + 1);
        var updated = existing with
        {
            Messages = [.. existing.Messages, new HubWorkspaceMessage(
                idempotencyKey, "user", messageText, now.ToUnixTimeMilliseconds())]
        };
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(updated);
        if (plaintext.Length > MaxWorkspaceConversationPlaintextBytes)
        {
            CryptographicOperations.ZeroMemory(plaintext);
            throw new InvalidOperationException("WORKSPACE_CONVERSATION_MESSAGE_LIMIT");
        }
        var protectedSnapshot = ProtectWorkspace($"conversation:{projectId}:{conversationId}", plaintext);
        CryptographicOperations.ZeroMemory(plaintext);
        try
        {
            using var update = connection.CreateCommand();
            update.Transaction = transaction;
            update.CommandText = """
                UPDATE WorkspaceConversations
                SET version = $next, snapshot_ciphertext = $snapshot, updated_at_ms = $updated
                WHERE project_id = $project AND conversation_id = $conversation AND version = $base;
                """;
            update.Parameters.AddWithValue("$next", nextVersion);
            update.Parameters.AddWithValue("$snapshot", protectedSnapshot);
            update.Parameters.AddWithValue("$updated", now.ToUnixTimeMilliseconds());
            update.Parameters.AddWithValue("$project", projectId);
            update.Parameters.AddWithValue("$conversation", conversationId);
            update.Parameters.AddWithValue("$base", baseVersion);
            if (update.ExecuteNonQuery() != 1)
                throw new InvalidOperationException("WORKSPACE_CAS_LOST");
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedSnapshot);
        }

        using (var projectVersion = connection.CreateCommand())
        {
            projectVersion.Transaction = transaction;
            projectVersion.CommandText = """
                UPDATE WorkspaceProjects SET version = version + 1, updated_at_ms = $updated
                WHERE project_id = $project;
                """;
            projectVersion.Parameters.AddWithValue("$updated", now.ToUnixTimeMilliseconds());
            projectVersion.Parameters.AddWithValue("$project", projectId);
            projectVersion.ExecuteNonQuery();
        }
        using (var receipt = connection.CreateCommand())
        {
            receipt.Transaction = transaction;
            receipt.CommandText = """
                INSERT INTO WorkspaceAppendReceipts(
                    project_id, idempotency_key, conversation_id, base_version,
                    resulting_version, message_sha256, committed_at_ms)
                VALUES($project, $idempotency, $conversation, $base, $result, $digest, $committed);
                """;
            receipt.Parameters.AddWithValue("$project", projectId);
            receipt.Parameters.AddWithValue("$idempotency", idempotencyKey);
            receipt.Parameters.AddWithValue("$conversation", conversationId);
            receipt.Parameters.AddWithValue("$base", baseVersion);
            receipt.Parameters.AddWithValue("$result", nextVersion);
            receipt.Parameters.AddWithValue("$digest", messageDigest);
            receipt.Parameters.AddWithValue("$committed", now.ToUnixTimeMilliseconds());
            receipt.ExecuteNonQuery();
        }
        transaction.Commit();
        return new HubWorkspaceAppendResult(HubWorkspaceAppendStatus.Committed, nextVersion);
    }

    private void InsertWorkspaceConversation(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string projectId,
        HubWorkspaceConversationInput conversation,
        long version,
        DateTimeOffset now)
    {
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(
            new ProtectedConversation(conversation.Title, conversation.Messages.ToArray()));
        var protectedSnapshot = ProtectWorkspace(
            $"conversation:{projectId}:{conversation.ConversationId}", plaintext);
        CryptographicOperations.ZeroMemory(plaintext);
        try
        {
            using var insert = connection.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO WorkspaceConversations(
                    project_id, conversation_id, version, snapshot_ciphertext, updated_at_ms)
                VALUES($project, $conversation, $version, $snapshot, $updated);
                """;
            insert.Parameters.AddWithValue("$project", projectId);
            insert.Parameters.AddWithValue("$conversation", conversation.ConversationId);
            insert.Parameters.AddWithValue("$version", version);
            insert.Parameters.AddWithValue("$snapshot", protectedSnapshot);
            insert.Parameters.AddWithValue("$updated", now.ToUnixTimeMilliseconds());
            insert.ExecuteNonQuery();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedSnapshot);
        }
    }

    private byte[] ProtectWorkspace(string scope, byte[] plaintext)
    {
        var entropy = Encoding.UTF8.GetBytes("glafira.desktop-hub.workspace.v1:" + scope);
        try { return _dataProtector.Protect(plaintext, entropy); }
        finally { CryptographicOperations.ZeroMemory(entropy); }
    }

    private T DecodeWorkspace<T>(string scope, byte[] ciphertext)
    {
        var entropy = Encoding.UTF8.GetBytes("glafira.desktop-hub.workspace.v1:" + scope);
        byte[] plaintext;
        try { plaintext = _dataProtector.Unprotect(ciphertext, entropy); }
        finally { CryptographicOperations.ZeroMemory(entropy); }
        try
        {
            return JsonSerializer.Deserialize<T>(plaintext)
                ?? throw new CryptographicException("Workspace snapshot is empty.");
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }

    private static void ValidateWorkspaceConversation(HubWorkspaceConversationInput conversation)
    {
        ArgumentNullException.ThrowIfNull(conversation);
        ValidateWorkspaceIdentifier(conversation.ConversationId, nameof(conversation.ConversationId));
        ValidateWorkspaceLabel(conversation.Title, 160, nameof(conversation.Title));
        ArgumentNullException.ThrowIfNull(conversation.Messages);
        if (conversation.Messages.Count > 10_000 ||
            conversation.Messages.Select(item => item.MessageId).Distinct(StringComparer.Ordinal).Count() !=
            conversation.Messages.Count)
            throw new ArgumentException("Workspace messages must be unique and bounded.", nameof(conversation));
        foreach (var message in conversation.Messages)
        {
            ValidateWorkspaceIdentifier(message.MessageId, nameof(message.MessageId));
            if (message.Role is not ("user" or "assistant" or "system"))
                throw new ArgumentException("Workspace message role is invalid.", nameof(conversation));
            ValidateWorkspaceLabel(message.Text, 16_384, nameof(message.Text));
            if (message.CreatedAtEpochMs <= 0)
                throw new ArgumentException("Workspace message timestamp is invalid.", nameof(conversation));
        }
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(
            new ProtectedConversation(conversation.Title, conversation.Messages.ToArray()));
        try
        {
            if (plaintext.Length > MaxWorkspaceConversationPlaintextBytes)
                throw new ArgumentException("Workspace conversation exceeds its encoded size limit.", nameof(conversation));
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }

    private static void ValidateWorkspaceIdentifier(string value, string name)
    {
        ValidateIdentifier(value, name);
        if (value.Length < 3 || value.Any(character =>
                !(char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.')))
            throw new ArgumentException("Workspace identifier contains unsupported characters.", name);
    }

    private static void ValidateWorkspaceLabel(string value, int maximum, string name)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value, name);
        if (value.Length > maximum || value.Any(character =>
                char.IsControl(character) && character is not ('\r' or '\n' or '\t')))
            throw new ArgumentException("Workspace text is invalid or exceeds its limit.", name);
    }
}
