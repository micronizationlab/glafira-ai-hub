using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Desktop.Core.Ownership;
using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Durable, owner-controlled scheduler state for the Windows Desktop Hub.
/// This store is called only after the HTTPS admission layer has authenticated
/// the device, checked scopes/replay protection and enforced owner approval.
/// </summary>
public sealed partial class DesktopHubStore
{
    private static readonly TimeSpan AgentFreshness = TimeSpan.FromMinutes(2);
    private readonly string _connectionString;
    private readonly IHubDataProtector _dataProtector;

    public DesktopHubStore(string databasePath, IHubDataProtector? dataProtector = null)
    {
        _dataProtector = dataProtector ?? CreateDefaultDataProtector();
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

    private static IHubDataProtector CreateDefaultDataProtector()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("The Windows Desktop Hub requires Windows DPAPI for result-at-rest protection.");
        return new WindowsDpapiHubDataProtector();
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

    /// <summary>
    /// Creates a paired Agent in a denied-by-default state. Existing owner-approved
    /// identities cannot be silently replaced by a new pairing ticket.
    /// </summary>
    public void RegisterPendingAgent(string agentId, string deviceId, DateTimeOffset now)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        ValidateIdentifier(deviceId, nameof(deviceId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO Agents(agent_id, device_id, capabilities_json, max_parallel, owner_approved, last_heartbeat_ms)
            VALUES($agent, $device, '[]', 1, 0, 0)
            ON CONFLICT(agent_id) DO UPDATE SET
                device_id = excluded.device_id,
                capabilities_json = '[]',
                max_parallel = 1,
                owner_approved = 0,
                last_heartbeat_ms = 0
            WHERE Agents.owner_approved = 0;
            """;
        command.Parameters.AddWithValue("$agent", agentId);
        command.Parameters.AddWithValue("$device", deviceId);
        if (command.ExecuteNonQuery() != 1)
            throw new InvalidOperationException("AGENT_ALREADY_OWNER_APPROVED");
    }

    /// <summary>
    /// Repairs the narrow crash window between durable credential enrollment and
    /// pending scheduler registration. It never approves an Agent or grants capabilities.
    /// </summary>
    public int ReconcilePendingAgents(DateTimeOffset now)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        var agentIds = new List<string>();
        using (var read = connection.CreateCommand())
        {
            read.Transaction = transaction;
            read.CommandText = """
                SELECT agent_id FROM HubAgentEnrollments
                WHERE revoked_at_ms IS NULL AND expires_at_ms > $now
                ORDER BY agent_id;
                """;
            read.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            using var reader = read.ExecuteReader();
            while (reader.Read()) agentIds.Add(reader.GetString(0));
        }

        var inserted = 0;
        foreach (var agentId in agentIds)
        {
            using var insert = connection.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT OR IGNORE INTO Agents(
                    agent_id, device_id, capabilities_json, max_parallel, owner_approved, last_heartbeat_ms)
                VALUES($agent, $agent, '[]', 1, 0, 0);
                """;
            insert.Parameters.AddWithValue("$agent", agentId);
            inserted += insert.ExecuteNonQuery();
        }
        transaction.Commit();
        return inserted;
    }

    /// <summary>Assigns capabilities and enables a paired Agent without fabricating a heartbeat.</summary>
    public bool ConfigureAgentApproval(
        string agentId,
        IReadOnlyCollection<string> capabilities,
        int maxParallelTasks)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        ArgumentNullException.ThrowIfNull(capabilities);
        if (maxParallelTasks is < 1 or > 16)
            throw new ArgumentOutOfRangeException(nameof(maxParallelTasks));
        var normalized = Normalize(capabilities);
        if (normalized.Length is < 1 or > 16)
            throw new ArgumentException("An approved Agent must have 1–16 explicit capabilities.", nameof(capabilities));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE Agents
            SET capabilities_json = $capabilities,
                max_parallel = $parallel,
                owner_approved = 1
            WHERE agent_id = $agent;
            """;
        command.Parameters.AddWithValue("$capabilities", JsonSerializer.Serialize(normalized));
        command.Parameters.AddWithValue("$parallel", maxParallelTasks);
        command.Parameters.AddWithValue("$agent", agentId);
        return command.ExecuteNonQuery() == 1;
    }

    public bool IsAgentOwnerApproved(string agentId)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT owner_approved FROM Agents WHERE agent_id = $agent;";
        command.Parameters.AddWithValue("$agent", agentId);
        return Convert.ToInt32(command.ExecuteScalar() ?? 0) == 1;
    }

    /// <summary>Revokes Hub credentials, enrollment and scheduler approval in one transaction.</summary>
    public bool RevokeAgent(string agentId, DateTimeOffset now)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);

        bool agentRowUpdated;
        using (var approval = connection.CreateCommand())
        {
            approval.Transaction = transaction;
            approval.CommandText = "UPDATE Agents SET owner_approved = 0, last_heartbeat_ms = 0 WHERE agent_id = $agent;";
            approval.Parameters.AddWithValue("$agent", agentId);
            agentRowUpdated = approval.ExecuteNonQuery() == 1;
        }

        bool credentialUpdated;
        using (var credential = connection.CreateCommand())
        {
            credential.Transaction = transaction;
            credential.CommandText = """
                UPDATE HubCredentials
                SET protected_secret = X'', scopes_json = '[]', enabled = 0, updated_at_ms = $now
                WHERE device_id = $agent;
                """;
            credential.Parameters.AddWithValue("$agent", agentId);
            credential.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            credentialUpdated = credential.ExecuteNonQuery() > 0;
        }

        bool enrollmentUpdated;
        using (var enrollment = connection.CreateCommand())
        {
            enrollment.Transaction = transaction;
            enrollment.CommandText = """
                UPDATE HubAgentEnrollments
                SET revoked_at_ms = $now
                WHERE agent_id = $agent AND revoked_at_ms IS NULL;
                """;
            enrollment.Parameters.AddWithValue("$agent", agentId);
            enrollment.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            enrollmentUpdated = enrollment.ExecuteNonQuery() > 0;
        }

        transaction.Commit();
        return agentRowUpdated || credentialUpdated || enrollmentUpdated;
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
        ValidateOwnerCoreId(task.OwnerCoreId, nameof(task.OwnerCoreId));
        if (task.OwnerGeneration <= 0)
            throw new ArgumentOutOfRangeException(nameof(task.OwnerGeneration));
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
                    task_id, project_id, owner_core_id, owner_generation,
                    idempotency_key, task_type, payload_ref, payload_sha256,
                    required_capabilities_json, effect_risk, approval_ref, priority, state,
                    created_at_ms, attempts, fencing_token)
                VALUES(
                    $task, $project, $owner, $generation,
                    $idempotency, $type, $payload, $sha,
                    $capabilities, $risk, $approval, $priority, 'Queued',
                    $created, 0, 0);
                """;
            command.Parameters.AddWithValue("$task", task.TaskId);
            command.Parameters.AddWithValue("$project", task.ProjectId);
            command.Parameters.AddWithValue("$owner", task.OwnerCoreId);
            command.Parameters.AddWithValue("$generation", task.OwnerGeneration);
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
        AppendTaskEvent(connection, transaction, task.TaskId, HubTaskState.Queued, now, null, null, null, "TASK_ENQUEUED");
        transaction.Commit();
    }

    /// <summary>Returns the exact immutable task fields that must be covered by an ownership signature.</summary>
    public HubOwnershipTransferSource? GetQueuedOwnershipTransferSource(string taskId)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT task_id, project_id, owner_core_id, owner_generation, fencing_token,
                   payload_sha256, idempotency_key, effect_risk, approval_ref
            FROM Tasks WHERE task_id = $task AND state = 'Queued';
            """;
        command.Parameters.AddWithValue("$task", taskId);
        using var reader = command.ExecuteReader();
        if (!reader.Read()) return null;
        return new HubOwnershipTransferSource(
            reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetInt64(3),
            reader.GetInt64(4), reader.GetString(5), reader.GetString(6),
            (HubTaskEffectRisk)reader.GetInt32(7), reader.IsDBNull(8) ? null : reader.GetString(8));
    }

    /// <summary>
    /// Verifies and atomically applies a Desktop Core signed ownership transition.
    /// The trusted public key is supplied by the local installation identity, never by the envelope.
    /// </summary>
    public bool ApplySignedQueuedTaskOwnershipTransfer(
        SignedOwnershipTransferEnvelope envelope,
        ReadOnlySpan<byte> trustedOldOwnerPublicKey,
        DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var payload = envelope.Payload;
        byte[] signature;
        try
        {
            signature = Convert.FromBase64String(envelope.SignatureBase64);
        }
        catch (FormatException error)
        {
            throw new ArgumentException("OWNERSHIP_TRANSFER_SIGNATURE_ENCODING_INVALID", nameof(envelope), error);
        }
        if (signature.Length != 64 ||
            !StringComparer.Ordinal.Equals(Convert.ToBase64String(signature), envelope.SignatureBase64) ||
            !CoreOwnershipTransferCryptography.Verify(payload, trustedOldOwnerPublicKey, signature))
            throw new InvalidOperationException("OWNERSHIP_TRANSFER_SIGNATURE_INVALID");

        var issuedAt = DateTimeOffset.Parse(payload.IssuedAt, System.Globalization.CultureInfo.InvariantCulture);
        var expiresAt = DateTimeOffset.Parse(payload.ExpiresAt, System.Globalization.CultureInfo.InvariantCulture);
        if (now < issuedAt || now >= expiresAt)
            throw new InvalidOperationException("OWNERSHIP_TRANSFER_OUTSIDE_VALIDITY_WINDOW");

        var envelopeJson = envelope.ToWireJson();
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);

        using (var replay = connection.CreateCommand())
        {
            replay.Transaction = transaction;
            replay.CommandText = "SELECT envelope_json FROM OwnershipTransfers WHERE transfer_id = $transfer;";
            replay.Parameters.AddWithValue("$transfer", payload.TransferId);
            if (replay.ExecuteScalar() is string existing)
            {
                if (!StringComparer.Ordinal.Equals(existing, envelopeJson))
                    throw new InvalidOperationException("OWNERSHIP_TRANSFER_IDEMPOTENCY_CONFLICT");
                transaction.Commit();
                return true;
            }
        }

        using (var update = connection.CreateCommand())
        {
            update.Transaction = transaction;
            update.CommandText = """
                UPDATE Tasks
                SET owner_core_id = $newOwner, owner_generation = $newGeneration
                WHERE task_id = $task AND project_id = $project AND state = 'Queued'
                  AND owner_core_id = $oldOwner AND owner_generation = $oldGeneration
                  AND fencing_token = $stateVersion AND payload_sha256 = $payloadSha
                  AND idempotency_key = $idempotency AND effect_risk = $risk
                  AND (($approval IS NULL AND approval_ref IS NULL) OR approval_ref = $approval);
                """;
            update.Parameters.AddWithValue("$newOwner", payload.NewOwnerCoreId);
            update.Parameters.AddWithValue("$newGeneration", payload.NewOwnerGeneration);
            update.Parameters.AddWithValue("$task", payload.TaskId);
            update.Parameters.AddWithValue("$project", payload.ProjectId);
            update.Parameters.AddWithValue("$oldOwner", payload.OldOwnerCoreId);
            update.Parameters.AddWithValue("$oldGeneration", payload.OldOwnerGeneration);
            update.Parameters.AddWithValue("$stateVersion", payload.StateVersion);
            update.Parameters.AddWithValue("$payloadSha", payload.PayloadSha256);
            update.Parameters.AddWithValue("$idempotency", payload.IdempotencyKey);
            update.Parameters.AddWithValue("$risk", (int)ParseOwnershipRisk(payload.EffectRisk));
            update.Parameters.AddWithValue("$approval", (object?)payload.ApprovalRef ?? DBNull.Value);
            if (update.ExecuteNonQuery() != 1)
            {
                transaction.Rollback();
                return false;
            }
        }

        using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO OwnershipTransfers(
                    transfer_id, nonce, task_id, project_id,
                    old_owner_core_id, new_owner_core_id,
                    old_owner_generation, new_owner_generation,
                    state_version, issued_at_ms, expires_at_ms, envelope_json)
                VALUES(
                    $transfer, $nonce, $task, $project,
                    $oldOwner, $newOwner, $oldGeneration, $newGeneration,
                    $stateVersion, $issued, $expires, $envelope);
                """;
            insert.Parameters.AddWithValue("$transfer", payload.TransferId);
            insert.Parameters.AddWithValue("$nonce", payload.Nonce);
            insert.Parameters.AddWithValue("$task", payload.TaskId);
            insert.Parameters.AddWithValue("$project", payload.ProjectId);
            insert.Parameters.AddWithValue("$oldOwner", payload.OldOwnerCoreId);
            insert.Parameters.AddWithValue("$newOwner", payload.NewOwnerCoreId);
            insert.Parameters.AddWithValue("$oldGeneration", payload.OldOwnerGeneration);
            insert.Parameters.AddWithValue("$newGeneration", payload.NewOwnerGeneration);
            insert.Parameters.AddWithValue("$stateVersion", payload.StateVersion);
            insert.Parameters.AddWithValue("$issued", issuedAt.ToUnixTimeMilliseconds());
            insert.Parameters.AddWithValue("$expires", expiresAt.ToUnixTimeMilliseconds());
            insert.Parameters.AddWithValue("$envelope", envelopeJson);
            insert.ExecuteNonQuery();
        }

        AppendTaskEvent(connection, transaction, payload.TaskId, HubTaskState.Queued, now,
            null, null, null, "TASK_OWNER_TRANSFERRED_SIGNED");
        transaction.Commit();
        return true;
    }

    public HubOwnershipTransferSnapshot? GetOwnershipTransfer(string transferId)
    {
        ValidateIdentifier(transferId, nameof(transferId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT transfer_id, task_id, project_id, old_owner_core_id, new_owner_core_id,
                   old_owner_generation, new_owner_generation, issued_at_ms, expires_at_ms, envelope_json
            FROM OwnershipTransfers WHERE transfer_id = $transfer;
            """;
        command.Parameters.AddWithValue("$transfer", transferId);
        using var reader = command.ExecuteReader();
        if (!reader.Read()) return null;
        return new HubOwnershipTransferSnapshot(
            reader.GetString(0), reader.GetString(1), reader.GetString(2),
            reader.GetString(3), reader.GetString(4), reader.GetInt64(5), reader.GetInt64(6),
            DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(7)),
            DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(8)), reader.GetString(9));
    }

    /// <summary>
    /// Returns a bounded, stable list of the newest still-valid transfer for each task in the
    /// exact project. The caller must already have authenticated and authorized the signed
    /// Bridge request; this method never widens project scope.
    /// </summary>
    public IReadOnlyList<string> GetDiscoverableOwnershipTransferIds(
        string projectId,
        DateTimeOffset now,
        int limit = 100)
    {
        if (projectId != "default") ValidateIdentifier(projectId, nameof(projectId));
        if (limit is < 1 or > 100) throw new ArgumentOutOfRangeException(nameof(limit));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT current.transfer_id
            FROM OwnershipTransfers current
            WHERE current.project_id = $project
              AND current.expires_at_ms > $now
              AND NOT EXISTS (
                  SELECT 1
                  FROM OwnershipTransfers newer
                  WHERE newer.task_id = current.task_id
                    AND (
                        newer.new_owner_generation > current.new_owner_generation OR
                        (newer.new_owner_generation = current.new_owner_generation AND
                         newer.issued_at_ms > current.issued_at_ms) OR
                        (newer.new_owner_generation = current.new_owner_generation AND
                         newer.issued_at_ms = current.issued_at_ms AND
                         newer.transfer_id > current.transfer_id)
                    )
              )
            ORDER BY current.issued_at_ms ASC, current.transfer_id ASC
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$project", projectId);
        command.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$limit", limit);
        using var reader = command.ExecuteReader();
        var transferIds = new List<string>();
        while (reader.Read()) transferIds.Add(reader.GetString(0));
        return transferIds;
    }

    private static HubTaskEffectRisk ParseOwnershipRisk(string value) => value switch
    {
        "READ_ONLY" => HubTaskEffectRisk.ReadOnly,
        "DRAFT" => HubTaskEffectRisk.LocalWrite,
        "EXTERNAL_SIDE_EFFECT" => HubTaskEffectRisk.ExternalSideEffect,
        _ => throw new InvalidOperationException("OWNERSHIP_TRANSFER_EFFECT_RISK_UNSUPPORTED_BY_HUB")
    };

    public HubTaskLease? ClaimNext(
        string agentId,
        DateTimeOffset now,
        TimeSpan leaseDuration,
        IReadOnlyCollection<string>? supportedTaskTypes = null)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        if (leaseDuration <= TimeSpan.Zero || leaseDuration > TimeSpan.FromMinutes(15))
            throw new ArgumentOutOfRangeException(nameof(leaseDuration));
        HashSet<string>? supportedTypes = null;
        if (supportedTaskTypes is not null)
        {
            if (supportedTaskTypes.Count is < 1 or > 64 ||
                supportedTaskTypes.Any(type => string.IsNullOrWhiteSpace(type) || type.Length > 96) ||
                supportedTaskTypes.Distinct(StringComparer.Ordinal).Count() != supportedTaskTypes.Count)
                throw new ArgumentException("Supported task types must be 1–64 unique bounded identifiers.", nameof(supportedTaskTypes));
            supportedTypes = new HashSet<string>(supportedTaskTypes, StringComparer.Ordinal);
        }

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
        // Stream in scheduling order until the first eligible task. A SQL candidate limit
        // before capability filtering can starve every compatible task behind that window.
        var candidates = new List<Candidate>();
        using (var command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
                SELECT t.task_id, t.project_id, t.owner_core_id, t.owner_generation,
                       t.task_type, t.payload_ref, t.payload_sha256,
                       t.required_capabilities_json, t.effect_risk, t.approval_ref, t.fencing_token,
                       t.idempotency_key
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
                ;
                """;
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                var taskType = reader.GetString(4);
                if (supportedTypes is not null && !supportedTypes.Contains(taskType)) continue;
                var required = JsonSerializer.Deserialize<string[]>(reader.GetString(7)) ?? [];
                if (required.All(agentCapabilities.Contains))
                {
                    candidates.Add(new Candidate(
                        reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetInt64(3), taskType,
                        reader.GetString(5), reader.GetString(6), (HubTaskEffectRisk)reader.GetInt32(8),
                        reader.IsDBNull(9) ? null : reader.GetString(9), reader.GetInt64(10), reader.GetString(11)));
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
        AppendTaskEvent(connection, transaction, selected.TaskId, HubTaskState.Leased, now, agentId, leaseId, nextFence, "TASK_LEASED");
        transaction.Commit();

        return new HubTaskLease(
            selected.TaskId, selected.ProjectId, selected.OwnerCoreId, selected.OwnerGeneration,
            agentId, leaseId, nextFence, expires,
            selected.TaskType, selected.IdempotencyKey, selected.PayloadRef, selected.PayloadSha256,
            selected.EffectRisk, selected.ApprovalRef);
    }

    /// <summary>
    /// Applies an explicit local-owner cancellation. A queued task is safely cancelled.
    /// A leased ReadOnly task is fenced and cancelled; a leased write/side-effect task
    /// becomes OutcomeUnknown because cancellation cannot prove the effect did not occur.
    /// </summary>
    public bool CancelTask(string taskId, DateTimeOffset now)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);

        HubTaskState current;
        HubTaskEffectRisk risk;
        string? agentId;
        string? leaseId;
        long? fencingToken;
        using (var read = connection.CreateCommand())
        {
            read.Transaction = transaction;
            read.CommandText = """
                SELECT t.state, t.effect_risk, l.agent_id, l.lease_id, l.fencing_token
                FROM Tasks t LEFT JOIN Leases l ON l.task_id = t.task_id
                WHERE t.task_id = $task;
                """;
            read.Parameters.AddWithValue("$task", taskId);
            using var reader = read.ExecuteReader();
            if (!reader.Read())
            {
                transaction.Commit();
                return false;
            }
            if (!Enum.TryParse(reader.GetString(0), out current))
                throw new InvalidDataException("HUB_TASK_STATE_INVALID");
            risk = (HubTaskEffectRisk)reader.GetInt32(1);
            agentId = reader.IsDBNull(2) ? null : reader.GetString(2);
            leaseId = reader.IsDBNull(3) ? null : reader.GetString(3);
            fencingToken = reader.IsDBNull(4) ? null : reader.GetInt64(4);
        }

        if (current is not (HubTaskState.Queued or HubTaskState.Leased))
        {
            transaction.Commit();
            return false;
        }

        var next = current == HubTaskState.Leased && risk != HubTaskEffectRisk.ReadOnly
            ? HubTaskState.OutcomeUnknown
            : HubTaskState.Cancelled;
        using (var update = connection.CreateCommand())
        {
            update.Transaction = transaction;
            update.CommandText = "UPDATE Tasks SET state = $state WHERE task_id = $task AND state = $current;";
            update.Parameters.AddWithValue("$state", next.ToString());
            update.Parameters.AddWithValue("$task", taskId);
            update.Parameters.AddWithValue("$current", current.ToString());
            if (update.ExecuteNonQuery() != 1)
            {
                transaction.Rollback();
                return false;
            }
        }

        // Removing the lease fences every delayed progress/result update from the Agent.
        using (var delete = connection.CreateCommand())
        {
            delete.Transaction = transaction;
            delete.CommandText = "DELETE FROM Leases WHERE task_id = $task;";
            delete.Parameters.AddWithValue("$task", taskId);
            delete.ExecuteNonQuery();
        }

        var eventCode = next == HubTaskState.Cancelled
            ? "TASK_CANCELLED_OWNER_REQUEST"
            : "TASK_CANCELLED_OUTCOME_UNKNOWN";
        AppendTaskEvent(connection, transaction, taskId, next, now, agentId, leaseId, fencingToken, eventCode);
        transaction.Commit();
        return true;
    }

    public bool Complete(
        string agentId,
        string taskId,
        string leaseId,
        long fencingToken,
        bool succeeded,
        DateTimeOffset now,
        string? result = null,
        string? errorCode = null)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        ValidateIdentifier(taskId, nameof(taskId));
        ValidateIdentifier(leaseId, nameof(leaseId));
        if (fencingToken <= 0) throw new ArgumentOutOfRangeException(nameof(fencingToken));
        if (result is { Length: > 16_384 }) throw new ArgumentException("Task result exceeds 16 KB.", nameof(result));
        if (errorCode is { Length: > 128 }) throw new ArgumentException("Task error code exceeds 128 characters.", nameof(errorCode));

        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using (var validate = connection.CreateCommand())
        {
            validate.Transaction = transaction;
            validate.CommandText = """
                SELECT COUNT(*)
                FROM Leases l
                JOIN Tasks t ON t.task_id = l.task_id
                JOIN Agents a ON a.agent_id = l.agent_id
                WHERE l.agent_id = $agent AND l.task_id = $task AND l.lease_id = $lease
                  AND l.fencing_token = $fence AND l.expires_at_ms > $now
                  AND t.state = 'Leased' AND t.fencing_token = $fence
                  AND a.owner_approved = 1;
                """;
            validate.Parameters.AddWithValue("$agent", agentId);
            validate.Parameters.AddWithValue("$task", taskId);
            validate.Parameters.AddWithValue("$lease", leaseId);
            validate.Parameters.AddWithValue("$fence", fencingToken);
            validate.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            if (Convert.ToInt32(validate.ExecuteScalar()) != 1)
            {
                var duplicate = IsExactDuplicateCompletion(
                    connection, transaction, agentId, taskId, leaseId, fencingToken,
                    succeeded, result, errorCode);
                transaction.Commit();
                return duplicate;
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

        AppendTaskEvent(connection, transaction, taskId,
            succeeded ? HubTaskState.Succeeded : HubTaskState.Failed,
            now, agentId, leaseId, fencingToken,
            succeeded ? "TASK_SUCCEEDED" : "TASK_FAILED");

        var resultBytes = JsonSerializer.SerializeToUtf8Bytes(new HubTaskResult(result, errorCode, now));
        var entropy = Encoding.UTF8.GetBytes("glafira.desktop-hub.task-result.v1:" + taskId);
        byte[] ciphertext;
        try
        {
            ciphertext = _dataProtector.Protect(resultBytes, entropy);
        }
        finally
        {
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(resultBytes);
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(entropy);
        }
        try
        {
            using var save = connection.CreateCommand();
            save.Transaction = transaction;
            save.CommandText = """
                INSERT INTO TaskResults(task_id, result_ciphertext, completed_at_ms)
                VALUES($task, $ciphertext, $completed)
                ON CONFLICT(task_id) DO UPDATE SET
                    result_ciphertext = excluded.result_ciphertext,
                    completed_at_ms = excluded.completed_at_ms;
                """;
            save.Parameters.AddWithValue("$task", taskId);
            save.Parameters.AddWithValue("$ciphertext", ciphertext);
            save.Parameters.AddWithValue("$completed", now.ToUnixTimeMilliseconds());
            save.ExecuteNonQuery();
        }
        finally
        {
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(ciphertext);
        }

        transaction.Commit();
        return true;
    }

    public HubTaskResult? GetTaskResult(string taskId)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT result_ciphertext FROM TaskResults WHERE task_id = $task;";
        command.Parameters.AddWithValue("$task", taskId);
        var value = command.ExecuteScalar();
        if (value is not byte[] ciphertext) return null;
        return UnprotectTaskResult(taskId, ciphertext);
    }

    /// <summary>
    /// Returns a bounded owner-facing projection of terminal state and verified artifact
    /// identity. Raw result text and decrypted artifact bytes never leave this boundary.
    /// </summary>
    public HubTaskOwnerResultSnapshot? GetTaskOwnerResult(string taskId)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        string taskType;
        string ownerCoreId;
        long ownerGeneration;
        HubTaskState state;
        using (var connection = Open())
        using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT task_type, state, owner_core_id, owner_generation
                FROM Tasks WHERE task_id = $task;
                """;
            command.Parameters.AddWithValue("$task", taskId);
            using var reader = command.ExecuteReader();
            if (!reader.Read()) return null;
            taskType = reader.GetString(0);
            if (!Enum.TryParse(reader.GetString(1), out state))
                throw new InvalidDataException("Stored task state is invalid.");
            ownerCoreId = reader.GetString(2);
            ownerGeneration = reader.GetInt64(3);
        }

        var result = GetTaskResult(taskId);
        if (result is null)
            return new HubTaskOwnerResultSnapshot(
                taskId, taskType, state, false, null, null,
                null, null, false, null, null, null);

        string? resultReference = null;
        string? resultSha256 = null;
        HubTaskArtifactMetadata? artifactMetadata = null;
        if (result.Result is not null)
        {
            try
            {
                using var document = JsonDocument.Parse(result.Result, new JsonDocumentOptions
                {
                    AllowTrailingCommas = false,
                    CommentHandling = JsonCommentHandling.Disallow,
                    MaxDepth = 4
                });
                var root = document.RootElement;
                if (root.ValueKind == JsonValueKind.Object &&
                    root.TryGetProperty("TaskId", out var resultTask) &&
                    resultTask.ValueKind == JsonValueKind.String &&
                    StringComparer.Ordinal.Equals(resultTask.GetString(), taskId) &&
                    root.TryGetProperty("Outcome", out var outcome) &&
                    outcome.ValueKind == JsonValueKind.String &&
                    StringComparer.Ordinal.Equals(outcome.GetString(), "SUCCEEDED") &&
                    root.TryGetProperty("ResultRef", out var reference) &&
                    reference.ValueKind == JsonValueKind.String &&
                    root.TryGetProperty("ResultSha256", out var digest) &&
                    digest.ValueKind == JsonValueKind.String)
                {
                    var candidateReference = reference.GetString();
                    var candidateDigest = digest.GetString();
                    const string prefix = "agent-artifact:";
                    if (candidateReference is { Length: > 15 and <= 143 } &&
                        candidateReference.StartsWith(prefix, StringComparison.Ordinal) &&
                        candidateDigest is { Length: 64 } &&
                        candidateDigest.All(character =>
                            character is >= '0' and <= '9' or >= 'a' and <= 'f'))
                    {
                        var artifactId = candidateReference[prefix.Length..];
                        if (artifactId.Length is >= 1 and <= 128 &&
                            char.IsAsciiLetterOrDigit(artifactId[0]) &&
                            artifactId.All(character => char.IsAsciiLetterOrDigit(character) ||
                                character is '-' or '_' or '.' or ':'))
                        {
                            resultReference = candidateReference;
                            resultSha256 = candidateDigest;
                            artifactMetadata = GetTaskArtifactMetadata(artifactId);
                        }
                    }
                }
            }
            catch (JsonException)
            {
                // Legacy/plain terminal results remain visible as completed state only.
            }
        }

        var artifactAvailable = artifactMetadata is not null &&
            StringComparer.Ordinal.Equals(artifactMetadata.TaskId, taskId) &&
            StringComparer.Ordinal.Equals(artifactMetadata.OwnerCoreId, ownerCoreId) &&
            artifactMetadata.OwnerGeneration == ownerGeneration &&
            StringComparer.Ordinal.Equals(artifactMetadata.Sha256, resultSha256);
        return new HubTaskOwnerResultSnapshot(
            taskId,
            taskType,
            state,
            true,
            result.CompletedAt,
            result.ErrorCode,
            resultReference,
            resultSha256,
            artifactAvailable,
            artifactAvailable ? artifactMetadata!.SchemaId : null,
            artifactAvailable ? artifactMetadata!.SchemaVersion : null,
            artifactAvailable ? artifactMetadata!.RedactionStatus : null);
    }

    private HubTaskArtifactMetadata? GetTaskArtifactMetadata(string artifactId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT artifact_id, task_id, owner_core_id, owner_generation,
                   lease_id, fencing_token, contract_version, schema_id, schema_version,
                   content_type, byte_length, sha256, redaction_status, created_at_ms
            FROM TaskArtifacts WHERE artifact_id = $artifact;
            """;
        command.Parameters.AddWithValue("$artifact", artifactId);
        using var reader = command.ExecuteReader();
        if (!reader.Read()) return null;
        return new HubTaskArtifactMetadata(
            reader.GetInt32(6), reader.GetString(0), reader.GetString(1), reader.GetString(2),
            reader.GetInt64(3), reader.GetString(4), reader.GetInt64(5), reader.GetString(7),
            reader.GetInt32(8), reader.GetString(9), reader.GetInt32(10), reader.GetString(11),
            reader.GetString(12), DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(13)));
    }

    /// <summary>
    /// Commits a bounded, redacted task artifact while the exact owner generation,
    /// lease and fencing token are still active. An identical retry is accepted even
    /// after the lease is gone so an Agent can recover from a lost acknowledgement.
    /// </summary>
    public HubTaskArtifactAcceptance AcceptTaskArtifact(
        string agentId,
        string projectId,
        HubTaskArtifactMetadata metadata,
        ReadOnlySpan<byte> payload,
        DateTimeOffset now)
    {
        ValidateIdentifier(agentId, nameof(agentId));
        ValidateIdentifier(projectId, nameof(projectId));
        ArgumentNullException.ThrowIfNull(metadata);
        ValidateTaskArtifact(metadata, payload);

        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        var existing = ReadTaskArtifact(connection, transaction, metadata.ArtifactId);
        if (existing is not null)
        {
            var duplicate = StringComparer.Ordinal.Equals(existing.AgentId, agentId) &&
                StringComparer.Ordinal.Equals(existing.ProjectId, projectId) &&
                existing.Metadata == metadata &&
                CryptographicOperations.FixedTimeEquals(existing.Artifact.Payload, payload);
            CryptographicOperations.ZeroMemory(existing.Artifact.Payload);
            transaction.Commit();
            return duplicate ? HubTaskArtifactAcceptance.Duplicate : HubTaskArtifactAcceptance.Conflict;
        }

        using (var bounded = connection.CreateCommand())
        {
            bounded.Transaction = transaction;
            bounded.CommandText = """
                SELECT COUNT(*) FROM TaskArtifacts
                WHERE task_id = $task AND schema_id = $schema AND schema_version = $version;
                """;
            bounded.Parameters.AddWithValue("$task", metadata.TaskId);
            bounded.Parameters.AddWithValue("$schema", metadata.SchemaId);
            bounded.Parameters.AddWithValue("$version", metadata.SchemaVersion);
            if (Convert.ToInt32(bounded.ExecuteScalar()) != 0)
            {
                transaction.Commit();
                return HubTaskArtifactAcceptance.Conflict;
            }
        }

        using (var validate = connection.CreateCommand())
        {
            validate.Transaction = transaction;
            validate.CommandText = """
                SELECT COUNT(*)
                FROM Leases l
                JOIN Tasks t ON t.task_id = l.task_id
                JOIN Agents a ON a.agent_id = l.agent_id
                WHERE l.agent_id = $agent AND l.task_id = $task AND l.lease_id = $lease
                  AND l.fencing_token = $fence AND l.expires_at_ms > $now
                  AND t.state = 'Leased' AND t.fencing_token = $fence
                  AND t.project_id = $project
                  AND t.owner_core_id = $owner AND t.owner_generation = $generation
                  AND a.owner_approved = 1;
                """;
            validate.Parameters.AddWithValue("$agent", agentId);
            validate.Parameters.AddWithValue("$task", metadata.TaskId);
            validate.Parameters.AddWithValue("$lease", metadata.LeaseId);
            validate.Parameters.AddWithValue("$fence", metadata.FencingToken);
            validate.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            validate.Parameters.AddWithValue("$project", projectId);
            validate.Parameters.AddWithValue("$owner", metadata.OwnerCoreId);
            validate.Parameters.AddWithValue("$generation", metadata.OwnerGeneration);
            if (Convert.ToInt32(validate.ExecuteScalar()) != 1)
            {
                transaction.Commit();
                return HubTaskArtifactAcceptance.LeaseRejected;
            }
        }

        var entropy = Encoding.UTF8.GetBytes("glafira.desktop-hub.task-artifact.v1:" + metadata.ArtifactId);
        byte[] ciphertext;
        try
        {
            ciphertext = _dataProtector.Protect(payload, entropy);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(entropy);
        }

        try
        {
            using var save = connection.CreateCommand();
            save.Transaction = transaction;
            save.CommandText = """
                INSERT INTO TaskArtifacts(
                    artifact_id, task_id, project_id, agent_id, owner_core_id, owner_generation,
                    lease_id, fencing_token, contract_version, schema_id, schema_version,
                    content_type, byte_length, sha256, redaction_status, created_at_ms,
                    payload_ciphertext, accepted_at_ms)
                VALUES(
                    $artifact, $task, $project, $agent, $owner, $generation,
                    $lease, $fence, $contract, $schema, $schemaVersion,
                    $contentType, $byteLength, $sha256, $redaction, $created,
                    $ciphertext, $accepted);
                """;
            save.Parameters.AddWithValue("$artifact", metadata.ArtifactId);
            save.Parameters.AddWithValue("$task", metadata.TaskId);
            save.Parameters.AddWithValue("$project", projectId);
            save.Parameters.AddWithValue("$agent", agentId);
            save.Parameters.AddWithValue("$owner", metadata.OwnerCoreId);
            save.Parameters.AddWithValue("$generation", metadata.OwnerGeneration);
            save.Parameters.AddWithValue("$lease", metadata.LeaseId);
            save.Parameters.AddWithValue("$fence", metadata.FencingToken);
            save.Parameters.AddWithValue("$contract", metadata.ContractVersion);
            save.Parameters.AddWithValue("$schema", metadata.SchemaId);
            save.Parameters.AddWithValue("$schemaVersion", metadata.SchemaVersion);
            save.Parameters.AddWithValue("$contentType", metadata.ContentType);
            save.Parameters.AddWithValue("$byteLength", metadata.ByteLength);
            save.Parameters.AddWithValue("$sha256", metadata.Sha256);
            save.Parameters.AddWithValue("$redaction", metadata.RedactionStatus);
            save.Parameters.AddWithValue("$created", metadata.CreatedAt.ToUnixTimeMilliseconds());
            save.Parameters.AddWithValue("$ciphertext", ciphertext);
            save.Parameters.AddWithValue("$accepted", now.ToUnixTimeMilliseconds());
            save.ExecuteNonQuery();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(ciphertext);
        }

        transaction.Commit();
        return HubTaskArtifactAcceptance.Accepted;
    }

    public HubTaskArtifact? GetTaskArtifact(string artifactId)
    {
        ValidateIdentifier(artifactId, nameof(artifactId));
        using var connection = Open();
        var stored = ReadTaskArtifact(connection, transaction: null, artifactId);
        return stored?.Artifact;
    }

    private StoredTaskArtifact? ReadTaskArtifact(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        string artifactId)
    {
        using var read = connection.CreateCommand();
        read.Transaction = transaction;
        read.CommandText = """
            SELECT artifact_id, task_id, project_id, agent_id, owner_core_id, owner_generation,
                   lease_id, fencing_token, contract_version, schema_id, schema_version,
                   content_type, byte_length, sha256, redaction_status, created_at_ms,
                   payload_ciphertext
            FROM TaskArtifacts WHERE artifact_id = $artifact;
            """;
        read.Parameters.AddWithValue("$artifact", artifactId);
        using var reader = read.ExecuteReader();
        if (!reader.Read()) return null;
        var metadata = new HubTaskArtifactMetadata(
            reader.GetInt32(8), reader.GetString(0), reader.GetString(1), reader.GetString(4),
            reader.GetInt64(5), reader.GetString(6), reader.GetInt64(7), reader.GetString(9),
            reader.GetInt32(10), reader.GetString(11), reader.GetInt32(12), reader.GetString(13),
            reader.GetString(14), DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(15)));
        var ciphertext = (byte[])reader[16];
        var entropy = Encoding.UTF8.GetBytes("glafira.desktop-hub.task-artifact.v1:" + artifactId);
        byte[] plaintext;
        try
        {
            plaintext = _dataProtector.Unprotect(ciphertext, entropy);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(entropy);
        }
        return new StoredTaskArtifact(reader.GetString(3), reader.GetString(2), metadata, new HubTaskArtifact(metadata, plaintext));
    }

    private static void ValidateTaskArtifact(HubTaskArtifactMetadata metadata, ReadOnlySpan<byte> payload)
    {
        ValidateIdentifier(metadata.TaskId, nameof(metadata.TaskId));
        ValidateOwnerCoreId(metadata.OwnerCoreId, nameof(metadata.OwnerCoreId));
        ValidateIdentifier(metadata.LeaseId, nameof(metadata.LeaseId));
        if (metadata.ContractVersion != 1 || metadata.OwnerGeneration <= 0 || metadata.FencingToken <= 0 ||
            metadata.SchemaVersion != 1 || metadata.ByteLength is < 1 or > 32_768 ||
            metadata.ByteLength != payload.Length || metadata.CreatedAt.ToUnixTimeMilliseconds() <= 0 ||
            metadata.ArtifactId is not { Length: >= 1 and <= 128 } ||
            !char.IsAsciiLetterOrDigit(metadata.ArtifactId[0]) ||
            metadata.ArtifactId.Any(character => !char.IsAsciiLetterOrDigit(character) && character is not ('-' or '_' or '.' or ':')) ||
            !StringComparer.Ordinal.Equals(metadata.SchemaId, "glafira.agent.diagnostics.snapshot") ||
            !StringComparer.Ordinal.Equals(metadata.ContentType, "application/json") ||
            !StringComparer.Ordinal.Equals(metadata.RedactionStatus, "REDACTED") ||
            metadata.Sha256 is not { Length: 64 } ||
            metadata.Sha256.Any(character => character is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new ArgumentException("Invalid task artifact metadata.", nameof(metadata));

        byte[] expected;
        try { expected = Convert.FromHexString(metadata.Sha256); }
        catch (FormatException error) { throw new ArgumentException("Invalid artifact SHA-256.", nameof(metadata), error); }
        var actual = SHA256.HashData(payload);
        try
        {
            if (!CryptographicOperations.FixedTimeEquals(expected, actual))
                throw new ArgumentException("Artifact SHA-256 mismatch.", nameof(payload));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(expected);
            CryptographicOperations.ZeroMemory(actual);
        }
    }

    private bool IsExactDuplicateCompletion(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string agentId,
        string taskId,
        string leaseId,
        long fencingToken,
        bool succeeded,
        string? result,
        string? errorCode)
    {
        using (var query = connection.CreateCommand())
        {
            query.Transaction = transaction;
            query.CommandText = """
                SELECT COUNT(*)
                FROM Tasks t
                JOIN TaskEvents e ON e.event_id = (
                    SELECT MAX(latest.event_id) FROM TaskEvents latest WHERE latest.task_id = t.task_id
                )
                WHERE t.task_id = $task AND t.state = $state
                  AND e.agent_id = $agent AND e.lease_id = $lease AND e.fencing_token = $fence;
                """;
            query.Parameters.AddWithValue("$task", taskId);
            query.Parameters.AddWithValue("$state", succeeded ? "Succeeded" : "Failed");
            query.Parameters.AddWithValue("$agent", agentId);
            query.Parameters.AddWithValue("$lease", leaseId);
            query.Parameters.AddWithValue("$fence", fencingToken);
            if (Convert.ToInt32(query.ExecuteScalar()) != 1) return false;
        }

        using var read = connection.CreateCommand();
        read.Transaction = transaction;
        read.CommandText = "SELECT result_ciphertext FROM TaskResults WHERE task_id = $task;";
        read.Parameters.AddWithValue("$task", taskId);
        if (read.ExecuteScalar() is not byte[] ciphertext) return false;
        var stored = UnprotectTaskResult(taskId, ciphertext);
        return StringComparer.Ordinal.Equals(stored.Result, result) &&
               StringComparer.Ordinal.Equals(stored.ErrorCode, errorCode);
    }

    private HubTaskResult UnprotectTaskResult(string taskId, byte[] ciphertext)
    {
        var entropy = Encoding.UTF8.GetBytes("glafira.desktop-hub.task-result.v1:" + taskId);
        byte[] plaintext;
        try
        {
            plaintext = _dataProtector.Unprotect(ciphertext, entropy);
        }
        finally
        {
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(entropy);
        }
        try
        {
            return JsonSerializer.Deserialize<HubTaskResult>(plaintext)
                ?? throw new System.Security.Cryptography.CryptographicException("Protected task result is empty.");
        }
        finally
        {
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(plaintext);
        }
    }

    public bool ReportProgress(
        string agentId,
        string taskId,
        string leaseId,
        long fencingToken,
        int progressPercent,
        string progressMessage,
        DateTimeOffset now,
        TimeSpan? leaseDuration = null)
    {
        leaseDuration ??= TimeSpan.FromMinutes(2);
        if (leaseDuration <= TimeSpan.Zero || leaseDuration > TimeSpan.FromMinutes(15))
            throw new ArgumentOutOfRangeException(nameof(leaseDuration));
        ValidateIdentifier(agentId, nameof(agentId));
        ValidateIdentifier(taskId, nameof(taskId));
        ValidateIdentifier(leaseId, nameof(leaseId));
        if (fencingToken <= 0)
            throw new ArgumentOutOfRangeException(nameof(fencingToken));
        if (progressPercent is < 0 or > 100)
            throw new ArgumentOutOfRangeException(nameof(progressPercent));
        ArgumentException.ThrowIfNullOrWhiteSpace(progressMessage);
        if (progressMessage.Length > 512)
            throw new ArgumentException("Progress message exceeds 512 characters.", nameof(progressMessage));
        var safeProgressMessage = new string(progressMessage
            .Select(character => char.IsControl(character) ? ' ' : character)
            .ToArray()).Trim();
        if (safeProgressMessage.Length == 0)
            throw new ArgumentException("Progress message must contain visible status text.", nameof(progressMessage));

        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using (var validate = connection.CreateCommand())
        {
            validate.Transaction = transaction;
            validate.CommandText = """
                SELECT COUNT(*)
                FROM Leases l
                JOIN Tasks t ON t.task_id = l.task_id
                JOIN Agents a ON a.agent_id = l.agent_id
                WHERE l.agent_id = $agent AND l.task_id = $task AND l.lease_id = $lease
                  AND l.fencing_token = $fence AND l.expires_at_ms > $now
                  AND t.state = 'Leased' AND t.fencing_token = $fence
                  AND a.owner_approved = 1;
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

        // Progress is also the lease-renewal signal. Extend only after the current
        // owner-approved lease and fencing token have been validated in this transaction.
        using (var renew = connection.CreateCommand())
        {
            renew.Transaction = transaction;
            renew.CommandText = "UPDATE Leases SET expires_at_ms = $expires WHERE lease_id = $lease AND agent_id = $agent AND task_id = $task AND fencing_token = $fence;";
            renew.Parameters.AddWithValue("$expires", now.Add(leaseDuration.Value).ToUnixTimeMilliseconds());
            renew.Parameters.AddWithValue("$lease", leaseId);
            renew.Parameters.AddWithValue("$agent", agentId);
            renew.Parameters.AddWithValue("$task", taskId);
            renew.Parameters.AddWithValue("$fence", fencingToken);
            if (renew.ExecuteNonQuery() != 1)
            {
                transaction.Commit();
                return false;
            }
        }

        AppendTaskEvent(connection, transaction, taskId, HubTaskState.Leased, now,
            agentId, leaseId, fencingToken, "TASK_PROGRESS", progressPercent, safeProgressMessage);
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
            AppendTaskEvent(connection, transaction, item.TaskId, state, now, null, null, null,
                state == HubTaskState.Queued ? "LEASE_EXPIRED_READ_ONLY_REQUEUED" : "LEASE_EXPIRED_OUTCOME_UNKNOWN");
            recovered.Add(new HubTaskRecovery(
                item.TaskId, state,
                state == HubTaskState.Queued
                    ? "Expired read-only lease safely returned to queue."
                    : "Expired write/side-effect lease quarantined; owner reconciliation required."));
        }
        transaction.Commit();
        return recovered;
    }


    /// <summary>Read-only dashboard view of enrolled Agents; never exposes credentials.</summary>
    public IReadOnlyList<HubAgentSnapshot> GetAgents(DateTimeOffset now)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT agent_id, device_id, capabilities_json, max_parallel,
                   owner_approved, last_heartbeat_ms
            FROM Agents ORDER BY agent_id COLLATE BINARY;
            """;
        using var reader = command.ExecuteReader();
        var agents = new List<HubAgentSnapshot>();
        while (reader.Read())
        {
            var heartbeat = DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5));
            var age = now - heartbeat;
            agents.Add(new HubAgentSnapshot(
                reader.GetString(0), reader.GetString(1),
                JsonSerializer.Deserialize<string[]>(reader.GetString(2)) ?? [],
                reader.GetInt32(3), reader.GetInt32(4) == 1, heartbeat,
                age >= TimeSpan.Zero && age <= AgentFreshness));
        }
        return agents;
    }

    /// <summary>Resolves an enrolled device to its stable Agent ID without exposing credentials.</summary>
    public string? GetAgentIdByDeviceId(string deviceId)
    {
        ValidateIdentifier(deviceId, nameof(deviceId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT agent_id FROM Agents WHERE device_id = $device;";
        command.Parameters.AddWithValue("$device", deviceId);
        return command.ExecuteScalar() as string;
    }

    /// <summary>Read-only, bounded task list for the Desktop owner dashboard.</summary>
    public IReadOnlyList<HubTaskSnapshot> GetTasks(int limit = 200)
    {
        if (limit is < 1 or > 1_000)
            throw new ArgumentOutOfRangeException(nameof(limit));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT task_id, project_id, owner_core_id, owner_generation, task_type, state, priority,
                   effect_risk, attempts, created_at_ms, approval_ref
            FROM Tasks
            ORDER BY created_at_ms DESC, task_id COLLATE BINARY
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$limit", limit);
        using var reader = command.ExecuteReader();
        var tasks = new List<HubTaskSnapshot>();
        while (reader.Read())
        {
            if (!Enum.TryParse<HubTaskState>(reader.GetString(5), out var state))
                continue;
            tasks.Add(new HubTaskSnapshot(
                reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetInt64(3),
                reader.GetString(4), state, reader.GetInt32(6), (HubTaskEffectRisk)reader.GetInt32(7),
                reader.GetInt32(8), DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(9)),
                reader.IsDBNull(10) ? null : reader.GetString(10)));
        }
        return tasks;
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

    public int? GetTaskAttempt(string taskId)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT attempts FROM Tasks WHERE task_id = $task;";
        command.Parameters.AddWithValue("$task", taskId);
        var value = command.ExecuteScalar();
        return value is long attempt ? checked((int)attempt) : null;
    }

    public IReadOnlyList<string> GetTaskDependencyIds(string taskId)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT depends_on_task_id FROM TaskDependencies
            WHERE task_id = $task ORDER BY depends_on_task_id COLLATE BINARY;
            """;
        command.Parameters.AddWithValue("$task", taskId);
        using var reader = command.ExecuteReader();
        var dependencies = new List<string>();
        while (reader.Read()) dependencies.Add(reader.GetString(0));
        return dependencies;
    }

    /// <summary>
    /// Matches the canonical ownership identity attached to a durable task. Ownership can
    /// advance only while queued, so this check remains stable for leased and terminal updates.
    /// </summary>
    public bool MatchesTaskOwnership(string taskId, string ownerCoreId, long ownerGeneration)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        ValidateOwnerCoreId(ownerCoreId, nameof(ownerCoreId));
        if (ownerGeneration <= 0)
            throw new ArgumentOutOfRangeException(nameof(ownerGeneration));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT COUNT(*) FROM Tasks
            WHERE task_id = $task AND owner_core_id = $owner AND owner_generation = $generation;
            """;
        command.Parameters.AddWithValue("$task", taskId);
        command.Parameters.AddWithValue("$owner", ownerCoreId);
        command.Parameters.AddWithValue("$generation", ownerGeneration);
        return Convert.ToInt32(command.ExecuteScalar()) == 1;
    }

    /// <summary>Returns the durable, append-only state history for one task without payloads or credentials.</summary>
    public IReadOnlyList<HubTaskEventSnapshot> GetTaskHistory(string taskId, int limit = 500)
    {
        ValidateIdentifier(taskId, nameof(taskId));
        if (limit is < 1 or > 5_000)
            throw new ArgumentOutOfRangeException(nameof(limit));
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT event_id, task_id, owner_core_id, owner_generation,
                   state, occurred_at_ms, agent_id, lease_id, fencing_token,
                   event_code, progress_percent, progress_message
            FROM TaskEvents
            WHERE task_id = $task
            ORDER BY event_id ASC
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$task", taskId);
        command.Parameters.AddWithValue("$limit", limit);
        using var reader = command.ExecuteReader();
        var events = new List<HubTaskEventSnapshot>();
        while (reader.Read())
        {
            if (!Enum.TryParse<HubTaskState>(reader.GetString(4), out var state))
                continue;
            events.Add(new HubTaskEventSnapshot(
                reader.GetInt64(0), reader.GetString(1), reader.GetString(2), reader.GetInt64(3), state,
                DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5)),
                reader.IsDBNull(6) ? null : reader.GetString(6),
                reader.IsDBNull(7) ? null : reader.GetString(7),
                reader.IsDBNull(8) ? null : reader.GetInt64(8),
                reader.GetString(9),
                reader.IsDBNull(10) ? null : reader.GetInt32(10),
                reader.IsDBNull(11) ? null : reader.GetString(11)));
        }
        return events;
    }

    private static void AppendTaskEvent(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string taskId,
        HubTaskState state,
        DateTimeOffset occurredAt,
        string? agentId,
        string? leaseId,
        long? fencingToken,
        string eventCode,
        int? progressPercent = null,
        string? progressMessage = null)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO TaskEvents(
                task_id, owner_core_id, owner_generation,
                state, occurred_at_ms, agent_id, lease_id, fencing_token, event_code,
                progress_percent, progress_message)
            SELECT $task, owner_core_id, owner_generation,
                   $state, $occurred, $agent, $lease, $fence, $code, $progress, $message
            FROM Tasks WHERE task_id = $task;
            """;
        command.Parameters.AddWithValue("$task", taskId);
        command.Parameters.AddWithValue("$state", state.ToString());
        command.Parameters.AddWithValue("$occurred", occurredAt.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$agent", (object?)agentId ?? DBNull.Value);
        command.Parameters.AddWithValue("$lease", (object?)leaseId ?? DBNull.Value);
        command.Parameters.AddWithValue("$fence", (object?)fencingToken ?? DBNull.Value);
        command.Parameters.AddWithValue("$code", eventCode);
        command.Parameters.AddWithValue("$progress", (object?)progressPercent ?? DBNull.Value);
        command.Parameters.AddWithValue("$message", (object?)progressMessage ?? DBNull.Value);
        command.ExecuteNonQuery();
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
                owner_core_id TEXT NOT NULL,
                owner_generation INTEGER NOT NULL CHECK(owner_generation > 0),
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

            CREATE TABLE IF NOT EXISTS OwnershipTransfers(
                transfer_id TEXT PRIMARY KEY,
                nonce TEXT NOT NULL UNIQUE,
                task_id TEXT NOT NULL REFERENCES Tasks(task_id) ON DELETE CASCADE,
                project_id TEXT NOT NULL,
                old_owner_core_id TEXT NOT NULL,
                new_owner_core_id TEXT NOT NULL,
                old_owner_generation INTEGER NOT NULL,
                new_owner_generation INTEGER NOT NULL,
                state_version INTEGER NOT NULL,
                issued_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                envelope_json TEXT NOT NULL,
                UNIQUE(task_id, new_owner_generation)
            );

            CREATE INDEX IF NOT EXISTS IX_OwnershipTransfers_Task
                ON OwnershipTransfers(task_id, new_owner_generation);

            CREATE TABLE IF NOT EXISTS Leases(
                lease_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL UNIQUE REFERENCES Tasks(task_id) ON DELETE CASCADE,
                agent_id TEXT NOT NULL REFERENCES Agents(agent_id),
                fencing_token INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS TaskResults(
                task_id TEXT PRIMARY KEY REFERENCES Tasks(task_id) ON DELETE CASCADE,
                result_ciphertext BLOB NOT NULL,
                completed_at_ms INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS TaskArtifacts(
                artifact_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES Tasks(task_id) ON DELETE CASCADE,
                project_id TEXT NOT NULL,
                agent_id TEXT NOT NULL REFERENCES Agents(agent_id),
                owner_core_id TEXT NOT NULL,
                owner_generation INTEGER NOT NULL CHECK(owner_generation > 0),
                lease_id TEXT NOT NULL,
                fencing_token INTEGER NOT NULL CHECK(fencing_token > 0),
                contract_version INTEGER NOT NULL CHECK(contract_version = 1),
                schema_id TEXT NOT NULL,
                schema_version INTEGER NOT NULL CHECK(schema_version = 1),
                content_type TEXT NOT NULL,
                byte_length INTEGER NOT NULL CHECK(byte_length BETWEEN 1 AND 32768),
                sha256 TEXT NOT NULL,
                redaction_status TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                payload_ciphertext BLOB NOT NULL,
                accepted_at_ms INTEGER NOT NULL,
                UNIQUE(task_id, schema_id, schema_version)
            );

            CREATE INDEX IF NOT EXISTS IX_TaskArtifacts_Task
                ON TaskArtifacts(task_id, accepted_at_ms);

            CREATE TABLE IF NOT EXISTS TaskEvents(
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL REFERENCES Tasks(task_id) ON DELETE CASCADE,
                owner_core_id TEXT NOT NULL,
                owner_generation INTEGER NOT NULL CHECK(owner_generation > 0),
                state TEXT NOT NULL,
                occurred_at_ms INTEGER NOT NULL,
                agent_id TEXT NULL,
                lease_id TEXT NULL,
                fencing_token INTEGER NULL,
                event_code TEXT NOT NULL,
                progress_percent INTEGER NULL,
                progress_message TEXT NULL
            );

            CREATE INDEX IF NOT EXISTS IX_TaskEvents_Task
                ON TaskEvents(task_id, event_id);

            CREATE INDEX IF NOT EXISTS IX_Tasks_Queue
                ON Tasks(state, priority DESC, created_at_ms ASC);
            CREATE INDEX IF NOT EXISTS IX_Leases_Agent
                ON Leases(agent_id, expires_at_ms);

            CREATE TABLE IF NOT EXISTS WorkspaceProjects(
                project_id TEXT PRIMARY KEY,
                version INTEGER NOT NULL CHECK(version > 0),
                snapshot_ciphertext BLOB NOT NULL,
                updated_at_ms INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS WorkspaceConversations(
                project_id TEXT NOT NULL REFERENCES WorkspaceProjects(project_id) ON DELETE CASCADE,
                conversation_id TEXT NOT NULL,
                version INTEGER NOT NULL CHECK(version > 0),
                snapshot_ciphertext BLOB NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(project_id, conversation_id)
            );

            CREATE TABLE IF NOT EXISTS WorkspaceAppendReceipts(
                project_id TEXT NOT NULL REFERENCES WorkspaceProjects(project_id) ON DELETE CASCADE,
                idempotency_key TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                base_version INTEGER NOT NULL CHECK(base_version > 0),
                resulting_version INTEGER NOT NULL CHECK(resulting_version > base_version),
                message_sha256 TEXT NOT NULL,
                committed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(project_id, idempotency_key)
            );

            CREATE INDEX IF NOT EXISTS IX_WorkspaceConversations_Project
                ON WorkspaceConversations(project_id, conversation_id);
            """;
        command.ExecuteNonQuery();
        EnsureTaskColumn(connection, "owner_core_id", "TEXT NOT NULL DEFAULT 'legacy-owner'");
        EnsureTaskColumn(connection, "owner_generation", "INTEGER NOT NULL DEFAULT 1");
        EnsureTaskEventColumn(connection, "owner_core_id", "TEXT NOT NULL DEFAULT 'legacy-owner'");
        EnsureTaskEventColumn(connection, "owner_generation", "INTEGER NOT NULL DEFAULT 1");
        EnsureTaskEventColumn(connection, "progress_percent", "INTEGER NULL");
        EnsureTaskEventColumn(connection, "progress_message", "TEXT NULL");
    }

    private static void EnsureTaskEventColumn(SqliteConnection connection, string columnName, string columnDefinition)
    {
        using var query = connection.CreateCommand();
        query.CommandText = "PRAGMA table_info(TaskEvents);";
        using var reader = query.ExecuteReader();
        var exists = false;
        while (reader.Read())
        {
            if (StringComparer.Ordinal.Equals(reader.GetString(1), columnName))
            {
                exists = true;
                break;
            }
        }
        reader.Close();
        if (exists) return;
        using var alter = connection.CreateCommand();
        alter.CommandText = $"ALTER TABLE TaskEvents ADD COLUMN {columnName} {columnDefinition};";
        alter.ExecuteNonQuery();
    }

    private static void EnsureTaskColumn(SqliteConnection connection, string columnName, string columnDefinition)
    {
        using var query = connection.CreateCommand();
        query.CommandText = "PRAGMA table_info(Tasks);";
        using var reader = query.ExecuteReader();
        var exists = false;
        while (reader.Read())
            exists |= StringComparer.Ordinal.Equals(reader.GetString(1), columnName);
        reader.Close();
        if (exists) return;
        using var alter = connection.CreateCommand();
        alter.CommandText = $"ALTER TABLE Tasks ADD COLUMN {columnName} {columnDefinition};";
        alter.ExecuteNonQuery();
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

    private static void ValidateOwnerCoreId(string value, string name)
    {
        ValidateIdentifier(value, name);
        if (value.Length < 8 ||
            !System.Text.RegularExpressions.Regex.IsMatch(value, "^[A-Za-z0-9._-]+$"))
            throw new ArgumentException(
                "Owner Core ID must be 8–128 characters using letters, digits, dot, underscore, or hyphen.",
                name);
    }

    private sealed record Candidate(
        string TaskId,
        string ProjectId,
        string OwnerCoreId,
        long OwnerGeneration,
        string TaskType,
        string PayloadRef,
        string PayloadSha256,
        HubTaskEffectRisk EffectRisk,
        string? ApprovalRef,
        long FencingToken,
        string IdempotencyKey);

    private sealed record StoredTaskArtifact(
        string AgentId,
        string ProjectId,
        HubTaskArtifactMetadata Metadata,
        HubTaskArtifact Artifact);
}
