using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Durable, owner-controlled scheduler state for the Windows Desktop Hub.
/// This store is called only after the HTTPS admission layer has authenticated
/// the device, checked scopes/replay protection and enforced owner approval.
/// </summary>
public sealed class DesktopHubStore
{
    private static readonly TimeSpan AgentFreshness = TimeSpan.FromMinutes(2);
    private readonly string _connectionString;

    public DesktopHubStore(string databasePath)
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
        Initialize();
    }

    public void RegisterAgent(HubAgentRegistration agent, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(agent);
        ValidateIdentifier(agent.AgentId, nameof(agent.AgentId));
        ValidateIdentifier(agent.DeviceId, nameof(agent.DeviceId));
        if (agent.MaxParallelTasks is < 1 or > 16)
            throw new ArgumentOutOfRangeException(nameof(agent.MaxParallelTasks));

        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO Agents(agent_id, device_id, capabilities_json, max_parallel, owner_approved, last_heartbeat_ms)
            VALUES($agent, $device, $capabilities, $parallel, $approved, $heartbeat)
            ON CONFLICT(agent_id) DO UPDATE SET
                device_id = excluded.device_id,
                capabilities_json = excluded.capabilities_json,
                max_parallel = excluded.max_parallel,
                owner_approved = excluded.owner_approved,
                last_heartbeat_ms = excluded.last_heartbeat_ms;
            """;
        command.Parameters.AddWithValue("$agent", agent.AgentId);
        command.Parameters.AddWithValue("$device", agent.DeviceId);
        command.Parameters.AddWithValue("$capabilities", JsonSerializer.Serialize(Normalize(agent.Capabilities)));
        command.Parameters.AddWithValue("$parallel", agent.MaxParallelTasks);
        command.Parameters.AddWithValue("$approved", agent.OwnerApproved ? 1 : 0);
        command.Parameters.AddWithValue("$heartbeat", now.ToUnixTimeMilliseconds());
        command.ExecuteNonQuery();
    }

    /// <summary>Called only by the owner-approval UI after an authenticated pairing ceremony.</summary>
    public bool SetAgentOwnerApproval(string agentId, bool approved)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE Agents SET owner_approved = $approved WHERE agent_id = $agent;";
        command.Parameters.AddWithValue("$approved", approved ? 1 : 0);
        command.Parameters.AddWithValue("$agent", agentId);
        return command.ExecuteNonQuery() == 1;
    }

    public bool Heartbeat(
        string agentId,
        IReadOnlyCollection<string> capabilities,
        int maxParallelTasks,
        DateTimeOffset now)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        if (maxParallelTasks is < 1 or > 16)
            throw new ArgumentOutOfRangeException(nameof(maxParallelTasks));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE Agents
            SET capabilities_json = $capabilities,
                max_parallel = $parallel,
                last_heartbeat_ms = $heartbeat
            WHERE agent_id = $agent;
            """;
        command.Parameters.AddWithValue("$capabilities", JsonSerializer.Serialize(Normalize(capabilities)));
        command.Parameters.AddWithValue("$parallel", maxParallelTasks);
        command.Parameters.AddWithValue("$heartbeat", now.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$agent", agentId);
        return command.ExecuteNonQuery() == 1;
    }

    public void Enqueue(HubTaskRequest task, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(task);
        ValidateIdentifier(task.TaskId, nameof(task.TaskId));
        ValidateIdentifier(task.ProjectId, nameof(task.ProjectId));
        ValidateIdentifier(task.IdempotencyKey, nameof(task.IdempotencyKey));
        ValidateIdentifier(task.TaskType, nameof(task.TaskType));
        ArgumentException.ThrowIfNullOrWhiteSpace(task.PayloadRef);
        if (task.PayloadRef.Length > 512 ||
            !System.Text.RegularExpressions.Regex.IsMatch(task.PayloadSha256, "^[a-fA-F0-9]{64}$"))
            throw new ArgumentException("Payload reference or SHA-256 digest is invalid.", nameof(task));
        if (task.EffectRisk == HubTaskEffectRisk.ExternalSideEffect &&
            string.IsNullOrWhiteSpace(task.ApprovalRef))
            throw new InvalidOperationException("External side-effect tasks require an owner approval reference.");

        var dependencies = Normalize(task.Dependencies);
        if (dependencies.Contains(task.TaskId, StringComparer.Ordinal))
            throw new InvalidOperationException("A task cannot depend on itself.");

        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using (var command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO Tasks(
                    task_id, project_id, idempotency_key, task_type, payload_ref, payload_sha256,
                    required_capabilities_json, effect_risk, approval_ref, priority, state,
                    created_at_ms, attempts, fencing_token)
                VALUES(
                    $task, $project, $idempotency, $type, $payload, $sha,
                    $capabilities, $risk, $approval, $priority, 'Queued',
                    $created, 0, 0);
                """;
            command.Parameters.AddWithValue("$task", task.TaskId);
            command.Parameters.AddWithValue("$project", task.ProjectId);
            command.Parameters.AddWithValue("$idempotency", task.IdempotencyKey);
            command.Parameters.AddWithValue("$type", task.TaskType);
            command.Parameters.AddWithValue("$payload", task.PayloadRef);
            command.Parameters.AddWithValue("$sha", task.PayloadSha256.ToLowerInvariant());
            command.Parameters.AddWithValue("$capabilities", JsonSerializer.Serialize(Normalize(task.RequiredCapabilities)));
            command.Parameters.AddWithValue("$risk", (int)task.EffectRisk);
            command.Parameters.AddWithValue("$approval", (object?)task.ApprovalRef ?? DBNull.Value);
            command.Parameters.AddWithValue("$priority", task.Priority);
            command.Parameters.AddWithValue("$created", now.ToUnixTimeMilliseconds());
            command.ExecuteNonQuery();
        }

        foreach (var dependency in dependencies)
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = "INSERT INTO TaskDependencies(task_id, depends_on_task_id) VALUES($task, $dependency);";
            command.Parameters.AddWithValue("$task", task.TaskId);
            command.Parameters.AddWithValue("$dependency", dependency);
            command.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    public HubTaskLease? ClaimNext(
        string agentId,
        DateTimeOffset now,
        TimeSpan leaseDuration)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        if (leaseDuration <= TimeSpan.Zero || leaseDuration > TimeSpan.FromMinutes(15))
            throw new ArgumentOutOfRangeException(nameof(leaseDuration));

        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        string capabilitiesJson;
        int maxParallel;
        long heartbeat;
        bool approved;
        using (var agent = connection.CreateCommand())
        {
            agent.Transaction = transaction;
            agent.CommandText = """
                SELECT capabilities_json, max_parallel, last_heartbeat_ms, owner_approved
                FROM Agents WHERE agent_id = $agent;
                """;
            agent.Parameters.AddWithValue("$agent", agentId);
            using var reader = agent.ExecuteReader();
            if (!reader.Read())
            {
                transaction.Commit();
                return null;
            }
            capabilitiesJson = reader.GetString(0);
            maxParallel = reader.GetInt32(1);
            heartbeat = reader.GetInt64(2);
            approved = reader.GetInt32(3) == 1;
        }

        if (!approved || now.ToUnixTimeMilliseconds() - heartbeat > (long)AgentFreshness.TotalMilliseconds)
        {
            transaction.Commit();
            return null;
        }

        using (var capacity = connection.CreateCommand())
        {
            capacity.Transaction = transaction;
            capacity.CommandText = "SELECT COUNT(*) FROM Leases WHERE agent_id = $agent;";
            capacity.Parameters.AddWithValue("$agent", agentId);
            if (Convert.ToInt32(capacity.ExecuteScalar()) >= maxParallel)
            {
                transaction.Commit();
                return null;
            }
        }

        var agentCapabilities = new HashSet<string>(
            JsonSerializer.Deserialize<string[]>(capabilitiesJson) ?? [], StringComparer.Ordinal);
        var candidates = new List<Candidate>();
        using (var command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
                SELECT t.task_id, t.project_id, t.task_type, t.payload_ref, t.payload_sha256,
                       t.required_capabilities_json, t.effect_risk, t.approval_ref, t.fencing_token
                FROM Tasks t
                WHERE t.state = 'Queued'
                  AND NOT EXISTS (
                      SELECT 1 FROM TaskDependencies d
                      JOIN Tasks predecessor ON predecessor.task_id = d.depends_on_task_id
                      WHERE d.task_id = t.task_id AND predecessor.state <> 'Succeeded'
                  )
                ORDER BY
                  (SELECT COUNT(*) FROM Tasks active
                   WHERE active.project_id = t.project_id AND active.state = 'Leased') ASC,
                  t.priority DESC,
                  t.created_at_ms ASC
                LIMIT 128;
                """;
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                var required = JsonSerializer.Deserialize<string[]>(reader.GetString(5)) ?? [];
                if (required.All(agentCapabilities.Contains))
                {
                    candidates.Add(new Candidate(
                        reader.GetString(0), reader.GetString(1), reader.GetString(2),
                        reader.GetString(3), reader.GetString(4), (HubTaskEffectRisk)reader.GetInt32(6),
                        reader.IsDBNull(7) ? null : reader.GetString(7), reader.GetInt64(8)));
                    break;
                }
            }
        }

        if (candidates.Count == 0)
        {
            transaction.Commit();
            return null;
        }

        var selected = candidates[0];
        var nextFence = checked(selected.FencingToken + 1);
        var leaseId = Guid.NewGuid().ToString("N");
        var expires = now.Add(leaseDuration);
        using (var update = connection.CreateCommand())
        {
            update.Transaction = transaction;
            update.CommandText = """
                UPDATE Tasks SET state = 'Leased', attempts = attempts + 1, fencing_token = $fence
                WHERE task_id = $task AND state = 'Queued';
                """;
            update.Parameters.AddWithValue("$fence", nextFence);
            update.Parameters.AddWithValue("$task", selected.TaskId);
            if (update.ExecuteNonQuery() != 1)
            {
                transaction.Rollback();
                return null;
            }
        }
        using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO Leases(lease_id, task_id, agent_id, fencing_token, expires_at_ms)
                VALUES($lease, $task, $agent, $fence, $expires);
                """;
            insert.Parameters.AddWithValue("$lease", leaseId);
            insert.Parameters.AddWithValue("$task", selected.TaskId);
            insert.Parameters.AddWithValue("$agent", agentId);
            insert.Parameters.AddWithValue("$fence", nextFence);
            insert.Parameters.AddWithValue("$expires", expires.ToUnixTimeMilliseconds());
            insert.ExecuteNonQuery();
        }
        transaction.Commit();

        return new HubTaskLease(
            selected.TaskId, selected.ProjectId, agentId, leaseId, nextFence, expires,
            selected.TaskType, selected.PayloadRef, selected.PayloadSha256,
            selected.EffectRisk, selected.ApprovalRef);
    }

    public bool Complete(
        string agentId,
        string taskId,
        string leaseId,
        long fencingToken,
        bool succeeded,
        DateTimeOffset now)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using (var validate = connection.CreateCommand())
        {
            validate.Transaction = transaction;
            validate.CommandText = """
                SELECT COUNT(*) FROM Leases
                WHERE agent_id = $agent AND task_id = $task AND lease_id = $lease
                  AND fencing_token = $fence AND expires_at_ms > $now;
                """;
            validate.Parameters.AddWithValue("$agent", agentId);
            validate.Parameters.AddWithValue("$task", taskId);
            validate.Parameters.AddWithValue("$lease", leaseId);
            validate.Parameters.AddWithValue("$fence", fencingToken);
            validate.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            if (Convert.ToInt32(validate.ExecuteScalar()) != 1)
            {
                transaction.Commit();
                return false;
            }
        }
        using (var update = connection.CreateCommand())
        {
            update.Transaction = transaction;
            update.CommandText = "UPDATE Tasks SET state = $state WHERE task_id = $task AND fencing_token = $fence;";
            update.Parameters.AddWithValue("$state", succeeded ? "Succeeded" : "Failed");
            update.Parameters.AddWithValue("$task", taskId);
            update.Parameters.AddWithValue("$fence", fencingToken);
            if (update.ExecuteNonQuery() != 1)
            {
                transaction.Rollback();
                return false;
            }
        }
        using (var delete = connection.CreateCommand())
        {
            delete.Transaction = transaction;
            delete.CommandText = "DELETE FROM Leases WHERE lease_id = $lease;";
            delete.Parameters.AddWithValue("$lease", leaseId);
            delete.ExecuteNonQuery();
        }
        transaction.Commit();
        return true;
    }

    public IReadOnlyList<HubTaskRecovery> RecoverExpiredLeases(DateTimeOffset now)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        var expired = new List<(string TaskId, HubTaskEffectRisk Risk)>();
        using (var query = connection.CreateCommand())
        {
            query.Transaction = transaction;
            query.CommandText = """
                SELECT t.task_id, t.effect_risk
                FROM Leases l JOIN Tasks t ON t.task_id = l.task_id
                WHERE l.expires_at_ms <= $now;
                """;
            query.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            using var reader = query.ExecuteReader();
            while (reader.Read())
                expired.Add((reader.GetString(0), (HubTaskEffectRisk)reader.GetInt32(1)));
        }

        var recovered = new List<HubTaskRecovery>();
        foreach (var item in expired)
        {
            var state = item.Risk == HubTaskEffectRisk.ReadOnly
                ? HubTaskState.Queued
                : HubTaskState.OutcomeUnknown;
            using (var update = connection.CreateCommand())
            {
                update.Transaction = transaction;
                update.CommandText = "UPDATE Tasks SET state = $state WHERE task_id = $task AND state = 'Leased';";
                update.Parameters.AddWithValue("$state", state.ToString());
                update.Parameters.AddWithValue("$task", item.TaskId);
                update.ExecuteNonQuery();
            }
            using (var delete = connection.CreateCommand())
            {
                delete.Transaction = transaction;
                delete.CommandText = "DELETE FROM Leases WHERE task_id = $task;";
                delete.Parameters.AddWithValue("$task", item.TaskId);
                delete.ExecuteNonQuery();
            }
            recovered.Add(new HubTaskRecovery(
                item.TaskId, state,
                state == HubTaskState.Queued
                    ? "Expired read-only lease safely returned to queue."
                    : "Expired write/side-effect lease quarantined; owner reconciliation required."));
        }
        transaction.Commit();
        return recovered;
    }

    public HubTaskState? GetTaskState(string taskId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT state FROM Tasks WHERE task_id = $task;";
        command.Parameters.AddWithValue("$task", taskId);
        var value = command.ExecuteScalar() as string;
        return Enum.TryParse<HubTaskState>(value, out var state) ? state : null;
    }

    private void Initialize()
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = FULL;
            PRAGMA foreign_keys = ON;

            CREATE TABLE IF NOT EXISTS Agents(
                agent_id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL UNIQUE,
                capabilities_json TEXT NOT NULL,
                max_parallel INTEGER NOT NULL CHECK(max_parallel BETWEEN 1 AND 16),
                owner_approved INTEGER NOT NULL CHECK(owner_approved IN (0,1)),
                last_heartbeat_ms INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS Tasks(
                task_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                task_type TEXT NOT NULL,
                payload_ref TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                required_capabilities_json TEXT NOT NULL,
                effect_risk INTEGER NOT NULL,
                approval_ref TEXT NULL,
                priority INTEGER NOT NULL,
                state TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                fencing_token INTEGER NOT NULL,
                UNIQUE(project_id, idempotency_key)
            );

            CREATE TABLE IF NOT EXISTS TaskDependencies(
                task_id TEXT NOT NULL REFERENCES Tasks(task_id) ON DELETE CASCADE,
                depends_on_task_id TEXT NOT NULL REFERENCES Tasks(task_id),
                PRIMARY KEY(task_id, depends_on_task_id)
            );

            CREATE TABLE IF NOT EXISTS Leases(
                lease_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL UNIQUE REFERENCES Tasks(task_id) ON DELETE CASCADE,
                agent_id TEXT NOT NULL REFERENCES Agents(agent_id),
                fencing_token INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS IX_Tasks_Queue
                ON Tasks(state, priority DESC, created_at_ms ASC);
            CREATE INDEX IF NOT EXISTS IX_Leases_Agent
                ON Leases(agent_id, expires_at_ms);
            """;
        command.ExecuteNonQuery();
    }

    private SqliteConnection Open()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        using var pragma = connection.CreateCommand();
        pragma.CommandText = "PRAGMA foreign_keys = ON; PRAGMA synchronous = FULL;";
        pragma.ExecuteNonQuery();
        return connection;
    }

    private static string[] Normalize(IEnumerable<string> values) =>
        values.Where(value => !string.IsNullOrWhiteSpace(value))
            .Select(value => value.Trim())
            .Distinct(StringComparer.Ordinal)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();

    private static void ValidateIdentifier(string value, string name)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value, name);
        if (value.Length > 128)
            throw new ArgumentException("Identifier exceeds 128 characters.", name);
    }

    private sealed record Candidate(
        string TaskId,
        string ProjectId,
        string TaskType,
        string PayloadRef,
        string PayloadSha256,
        HubTaskEffectRisk EffectRisk,
        string? ApprovalRef,
        long FencingToken);
}
