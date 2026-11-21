using Microsoft.Data.Sqlite;

namespace GLAFIRA.Desktop.Hub.Core;

public enum AgentRevocationReason
{
    OWNER_REQUEST,
    DEVICE_REPLACED,
    CREDENTIAL_COMPROMISE,
    DECOMMISSIONED
}

public enum AgentRevocationStatus
{
    REVOKED,
    ALREADY_REVOKED,
    CONFLICT,
    NOT_FOUND
}

public enum AgentActiveLeaseDisposition
{
    NONE,
    READ_ONLY_REQUEUED,
    OUTCOME_UNKNOWN
}

public sealed record AgentRevocationRequest(
    int SchemaVersion,
    string RevocationId,
    string EnrollmentId,
    string AgentId,
    string RequestedByDeviceId,
    DateTimeOffset RequestedAt,
    AgentRevocationReason ReasonCode);

public sealed record AgentRevocationDecision(
    int SchemaVersion,
    string RevocationId,
    string EnrollmentId,
    string AgentId,
    AgentRevocationStatus Status,
    DateTimeOffset DecidedAt,
    DateTimeOffset? RevokedAt,
    long LastAcceptedFencingToken,
    AgentActiveLeaseDisposition ActiveLeaseDisposition,
    bool RequiresOwnerReconciliation);

internal static class AgentRevocationSql
{
    public static void EnsureSchema(SqliteConnection connection)
    {
        ArgumentNullException.ThrowIfNull(connection);
        using var enrollmentColumns = connection.CreateCommand();
        enrollmentColumns.CommandText = "PRAGMA table_info(HubAgentEnrollments);";
        using var reader = enrollmentColumns.ExecuteReader();
        var hasEnrollmentId = false;
        while (reader.Read())
            hasEnrollmentId |= StringComparer.Ordinal.Equals(reader.GetString(1), "enrollment_id");
        reader.Close();
        if (!hasEnrollmentId)
        {
            using var migration = connection.CreateCommand();
            migration.CommandText = """
                ALTER TABLE HubAgentEnrollments ADD COLUMN enrollment_id TEXT NULL;
                UPDATE HubAgentEnrollments SET enrollment_id = agent_id WHERE enrollment_id IS NULL;
                """;
            migration.ExecuteNonQuery();
        }

        using var schema = connection.CreateCommand();
        schema.CommandText = """
            CREATE UNIQUE INDEX IF NOT EXISTS IX_HubAgentEnrollments_Enrollment
                ON HubAgentEnrollments(enrollment_id);
            CREATE TABLE IF NOT EXISTS HubAgentRevocations(
                revocation_id TEXT NOT NULL PRIMARY KEY,
                enrollment_id TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                requested_by_device_id TEXT NOT NULL,
                requested_at_ms INTEGER NOT NULL,
                reason_code TEXT NOT NULL,
                status TEXT NOT NULL,
                decided_at_ms INTEGER NOT NULL,
                revoked_at_ms INTEGER NULL,
                last_accepted_fencing_token INTEGER NOT NULL,
                active_lease_disposition TEXT NOT NULL,
                requires_owner_reconciliation INTEGER NOT NULL CHECK(requires_owner_reconciliation IN (0,1))
            );
            CREATE INDEX IF NOT EXISTS IX_HubAgentRevocations_Enrollment
                ON HubAgentRevocations(enrollment_id, decided_at_ms);
            """;
        schema.ExecuteNonQuery();
    }

    public static AgentRevocationDecision Apply(
        SqliteConnection connection,
        SqliteTransaction transaction,
        AgentRevocationRequest request,
        DateTimeOffset now)
    {
        Validate(request, now);
        var existing = ReadByRevocationId(connection, transaction, request.RevocationId);
        if (existing is not null)
        {
            if (ExistingRequestMatches(connection, transaction, request))
                return existing;
            return Conflict(request, now);
        }

        string? currentEnrollmentId = null;
        DateTimeOffset? existingRevokedAt = null;
        using (var enrollment = connection.CreateCommand())
        {
            enrollment.Transaction = transaction;
            enrollment.CommandText = """
                SELECT enrollment_id, revoked_at_ms
                FROM HubAgentEnrollments
                WHERE agent_id = $agent;
                """;
            enrollment.Parameters.AddWithValue("$agent", request.AgentId);
            using var enrollmentReader = enrollment.ExecuteReader();
            if (enrollmentReader.Read())
            {
                currentEnrollmentId = enrollmentReader.IsDBNull(0) ? request.AgentId : enrollmentReader.GetString(0);
                if (!enrollmentReader.IsDBNull(1))
                    existingRevokedAt = DateTimeOffset.FromUnixTimeMilliseconds(enrollmentReader.GetInt64(1));
            }
        }

        if (currentEnrollmentId is null)
            return Store(connection, transaction, request, new AgentRevocationDecision(
                1, request.RevocationId, request.EnrollmentId, request.AgentId,
                AgentRevocationStatus.NOT_FOUND, now, null, 0,
                AgentActiveLeaseDisposition.NONE, false));
        if (!StringComparer.Ordinal.Equals(currentEnrollmentId, request.EnrollmentId))
            return Store(connection, transaction, request, Conflict(request, now));
        if (existingRevokedAt is not null)
        {
            var prior = ReadLatestByEnrollment(connection, transaction, request.EnrollmentId);
            return Store(connection, transaction, request, new AgentRevocationDecision(
                1, request.RevocationId, request.EnrollmentId, request.AgentId,
                AgentRevocationStatus.ALREADY_REVOKED, now, existingRevokedAt,
                prior?.LastAcceptedFencingToken ?? 0,
                prior?.ActiveLeaseDisposition ?? AgentActiveLeaseDisposition.NONE,
                prior?.RequiresOwnerReconciliation ?? false));
        }

        var leases = new List<(string LeaseId, string TaskId, long Fence, HubTaskEffectRisk Risk)>();
        using (var active = connection.CreateCommand())
        {
            active.Transaction = transaction;
            active.CommandText = """
                SELECT l.lease_id, l.task_id, l.fencing_token, t.effect_risk
                FROM Leases AS l
                JOIN Tasks AS t ON t.task_id = l.task_id
                WHERE l.agent_id = $agent
                ORDER BY l.task_id COLLATE BINARY;
                """;
            active.Parameters.AddWithValue("$agent", request.AgentId);
            using var leaseReader = active.ExecuteReader();
            while (leaseReader.Read())
                leases.Add((leaseReader.GetString(0), leaseReader.GetString(1), leaseReader.GetInt64(2),
                    (HubTaskEffectRisk)leaseReader.GetInt32(3)));
        }

        foreach (var lease in leases)
        {
            var nextState = lease.Risk == HubTaskEffectRisk.ReadOnly
                ? HubTaskState.Queued : HubTaskState.OutcomeUnknown;
            using var task = connection.CreateCommand();
            task.Transaction = transaction;
            task.CommandText = "UPDATE Tasks SET state = $state WHERE task_id = $task;";
            task.Parameters.AddWithValue("$state", nextState.ToString());
            task.Parameters.AddWithValue("$task", lease.TaskId);
            task.ExecuteNonQuery();

            using var taskEvent = connection.CreateCommand();
            taskEvent.Transaction = transaction;
            taskEvent.CommandText = """
                INSERT INTO TaskEvents(
                    task_id, state, occurred_at_ms, agent_id, lease_id, fencing_token, event_code,
                    progress_percent, progress_message)
                VALUES($task, $state, $now, $agent, $lease, $fence, $code, NULL, NULL);
                """;
            taskEvent.Parameters.AddWithValue("$task", lease.TaskId);
            taskEvent.Parameters.AddWithValue("$state", nextState.ToString());
            taskEvent.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            taskEvent.Parameters.AddWithValue("$agent", request.AgentId);
            taskEvent.Parameters.AddWithValue("$lease", lease.LeaseId);
            taskEvent.Parameters.AddWithValue("$fence", lease.Fence);
            taskEvent.Parameters.AddWithValue("$code", nextState == HubTaskState.Queued
                ? "AGENT_REVOKED_READ_ONLY_REQUEUED" : "AGENT_REVOKED_OUTCOME_UNKNOWN");
            taskEvent.ExecuteNonQuery();
        }

        using (var deleteLeases = connection.CreateCommand())
        {
            deleteLeases.Transaction = transaction;
            deleteLeases.CommandText = "DELETE FROM Leases WHERE agent_id = $agent;";
            deleteLeases.Parameters.AddWithValue("$agent", request.AgentId);
            deleteLeases.ExecuteNonQuery();
        }
        using (var approval = connection.CreateCommand())
        {
            approval.Transaction = transaction;
            approval.CommandText = "UPDATE Agents SET owner_approved = 0, last_heartbeat_ms = 0 WHERE agent_id = $agent;";
            approval.Parameters.AddWithValue("$agent", request.AgentId);
            approval.ExecuteNonQuery();
        }
        using (var credential = connection.CreateCommand())
        {
            credential.Transaction = transaction;
            credential.CommandText = """
                UPDATE HubCredentials
                SET protected_secret = X'', scopes_json = '[]', enabled = 0, updated_at_ms = $now
                WHERE device_id = $agent;
                """;
            credential.Parameters.AddWithValue("$agent", request.AgentId);
            credential.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            credential.ExecuteNonQuery();
        }
        using (var enrollment = connection.CreateCommand())
        {
            enrollment.Transaction = transaction;
            enrollment.CommandText = """
                UPDATE HubAgentEnrollments SET revoked_at_ms = $now
                WHERE agent_id = $agent AND enrollment_id = $enrollment AND revoked_at_ms IS NULL;
                """;
            enrollment.Parameters.AddWithValue("$agent", request.AgentId);
            enrollment.Parameters.AddWithValue("$enrollment", request.EnrollmentId);
            enrollment.Parameters.AddWithValue("$now", now.ToUnixTimeMilliseconds());
            if (enrollment.ExecuteNonQuery() != 1)
                throw new InvalidOperationException("AGENT_REVOCATION_GENERATION_CHANGED");
        }

        var disposition = leases.Any(item => item.Risk != HubTaskEffectRisk.ReadOnly)
            ? AgentActiveLeaseDisposition.OUTCOME_UNKNOWN
            : leases.Count > 0
                ? AgentActiveLeaseDisposition.READ_ONLY_REQUEUED
                : AgentActiveLeaseDisposition.NONE;
        return Store(connection, transaction, request, new AgentRevocationDecision(
            1, request.RevocationId, request.EnrollmentId, request.AgentId,
            AgentRevocationStatus.REVOKED, now, now,
            leases.Count == 0 ? 0 : leases.Max(item => item.Fence), disposition,
            disposition == AgentActiveLeaseDisposition.OUTCOME_UNKNOWN));
    }

    private static AgentRevocationDecision Store(
        SqliteConnection connection,
        SqliteTransaction transaction,
        AgentRevocationRequest request,
        AgentRevocationDecision decision)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO HubAgentRevocations(
                revocation_id, enrollment_id, agent_id, requested_by_device_id, requested_at_ms,
                reason_code, status, decided_at_ms, revoked_at_ms, last_accepted_fencing_token,
                active_lease_disposition, requires_owner_reconciliation)
            VALUES($revocation, $enrollment, $agent, $requestedBy, $requestedAt,
                   $reason, $status, $decidedAt, $revokedAt, $fence, $disposition, $reconcile);
            """;
        command.Parameters.AddWithValue("$revocation", request.RevocationId);
        command.Parameters.AddWithValue("$enrollment", request.EnrollmentId);
        command.Parameters.AddWithValue("$agent", request.AgentId);
        command.Parameters.AddWithValue("$requestedBy", request.RequestedByDeviceId);
        command.Parameters.AddWithValue("$requestedAt", request.RequestedAt.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$reason", request.ReasonCode.ToString());
        command.Parameters.AddWithValue("$status", decision.Status.ToString());
        command.Parameters.AddWithValue("$decidedAt", decision.DecidedAt.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$revokedAt", decision.RevokedAt is null
            ? DBNull.Value : decision.RevokedAt.Value.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$fence", decision.LastAcceptedFencingToken);
        command.Parameters.AddWithValue("$disposition", decision.ActiveLeaseDisposition.ToString());
        command.Parameters.AddWithValue("$reconcile", decision.RequiresOwnerReconciliation ? 1 : 0);
        command.ExecuteNonQuery();
        return decision;
    }

    private static AgentRevocationDecision? ReadByRevocationId(
        SqliteConnection connection, SqliteTransaction transaction, string revocationId)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT revocation_id, enrollment_id, agent_id, status, decided_at_ms, revoked_at_ms,
                   last_accepted_fencing_token, active_lease_disposition, requires_owner_reconciliation
            FROM HubAgentRevocations WHERE revocation_id = $revocation;
            """;
        command.Parameters.AddWithValue("$revocation", revocationId);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadDecision(reader) : null;
    }

    private static AgentRevocationDecision? ReadLatestByEnrollment(
        SqliteConnection connection, SqliteTransaction transaction, string enrollmentId)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT revocation_id, enrollment_id, agent_id, status, decided_at_ms, revoked_at_ms,
                   last_accepted_fencing_token, active_lease_disposition, requires_owner_reconciliation
            FROM HubAgentRevocations WHERE enrollment_id = $enrollment
            ORDER BY decided_at_ms DESC LIMIT 1;
            """;
        command.Parameters.AddWithValue("$enrollment", enrollmentId);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadDecision(reader) : null;
    }

    private static bool ExistingRequestMatches(
        SqliteConnection connection, SqliteTransaction transaction, AgentRevocationRequest request)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT enrollment_id, agent_id, requested_by_device_id, requested_at_ms, reason_code
            FROM HubAgentRevocations WHERE revocation_id = $revocation;
            """;
        command.Parameters.AddWithValue("$revocation", request.RevocationId);
        using var reader = command.ExecuteReader();
        return reader.Read() &&
            StringComparer.Ordinal.Equals(reader.GetString(0), request.EnrollmentId) &&
            StringComparer.Ordinal.Equals(reader.GetString(1), request.AgentId) &&
            StringComparer.Ordinal.Equals(reader.GetString(2), request.RequestedByDeviceId) &&
            reader.GetInt64(3) == request.RequestedAt.ToUnixTimeMilliseconds() &&
            StringComparer.Ordinal.Equals(reader.GetString(4), request.ReasonCode.ToString());
    }

    private static AgentRevocationDecision ReadDecision(SqliteDataReader reader) => new(
        1, reader.GetString(0), reader.GetString(1), reader.GetString(2),
        Enum.Parse<AgentRevocationStatus>(reader.GetString(3), false),
        DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(4)),
        reader.IsDBNull(5) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5)),
        reader.GetInt64(6), Enum.Parse<AgentActiveLeaseDisposition>(reader.GetString(7), false),
        reader.GetInt32(8) == 1);

    private static AgentRevocationDecision Conflict(AgentRevocationRequest request, DateTimeOffset now) => new(
        1, request.RevocationId, request.EnrollmentId, request.AgentId,
        AgentRevocationStatus.CONFLICT, now, null, 0, AgentActiveLeaseDisposition.NONE, true);

    private static void Validate(AgentRevocationRequest request, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.SchemaVersion != 1 || !IsIdentifier(request.RevocationId) ||
            !IsIdentifier(request.EnrollmentId) || !IsIdentifier(request.AgentId) ||
            !IsIdentifier(request.RequestedByDeviceId) || request.RequestedAt > now.AddMinutes(5) ||
            request.RequestedAt < now.AddDays(-1) || !Enum.IsDefined(request.ReasonCode))
            throw new ArgumentException("Agent revocation request is invalid.", nameof(request));
    }

    private static bool IsIdentifier(string value) => value is { Length: >= 1 and <= 128 } &&
        value.All(character => char.IsLetterOrDigit(character) || character is '-' or '_' or '.');
}
