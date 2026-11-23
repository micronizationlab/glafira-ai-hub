using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GLAFIRA.Agent.Worker;

public sealed record AgentPendingProgress(
    AgentTaskLease Lease,
    int ProgressPercent,
    string ProgressMessage);

public sealed record AgentPendingResult(
    AgentTaskLease Lease,
    string State,
    int ProgressPercent,
    string ProgressMessage,
    string ResultJson,
    string ErrorCode,
    string ResultSha256);

public sealed record AgentExecutionJournalSnapshot(
    IReadOnlyList<AgentTaskLease> ActiveLeases,
    IReadOnlyList<AgentPendingResult> PendingResults)
{
    // Additive default preserves compatibility with already persisted v1 DPAPI snapshots.
    public IReadOnlyList<AgentPendingProgress> PendingProgresses { get; init; } = [];
    public static AgentExecutionJournalSnapshot Empty { get; } = new([], []);
}

public interface IAgentLocalDataProtector
{
    byte[] Protect(byte[] plaintext);
    byte[] Unprotect(byte[] protectedBytes);
}

public interface IAgentExecutionJournal
{
    Task<AgentExecutionJournalSnapshot> LoadAsync(CancellationToken cancellationToken);
    Task SaveActiveLeaseAsync(AgentTaskLease lease, CancellationToken cancellationToken);
    Task PersistProgressAsync(AgentPendingProgress progress, CancellationToken cancellationToken);
    Task<bool> AcknowledgeProgressAsync(
        string taskId, string leaseId, long fencingToken, CancellationToken cancellationToken);
    Task<bool> DiscardStaleProgressAsync(
        string taskId, string leaseId, long fencingToken, CancellationToken cancellationToken);
    Task PersistCompletedResultAsync(AgentPendingResult result, CancellationToken cancellationToken);
    Task<bool> AcknowledgeResultAsync(
        string taskId, string leaseId, long fencingToken, CancellationToken cancellationToken);
    Task<bool> ClearExpiredActiveLeaseAsync(
        string taskId, string leaseId, long fencingToken, long nowEpochMs, CancellationToken cancellationToken);
    Task<bool> DiscardStaleActiveLeaseAsync(
        string taskId, string leaseId, long fencingToken, CancellationToken cancellationToken);
    Task<bool> DiscardStaleResultAsync(
        string taskId, string leaseId, long fencingToken, CancellationToken cancellationToken);
}

/// <summary>Machine-bound DPAPI protection for the Agent's local execution journal.</summary>
public sealed class WindowsDpapiAgentDataProtector : IAgentLocalDataProtector
{
    private static readonly byte[] Entropy = "GLAFIRA.Agent.ExecutionJournal.v1"u8.ToArray();

    public byte[] Protect(byte[] plaintext) =>
        ProtectedData.Protect(plaintext, Entropy, DataProtectionScope.LocalMachine);

    public byte[] Unprotect(byte[] protectedBytes) =>
        ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.LocalMachine);
}

/// <summary>
/// Durable write-ahead journal for active leases, progress and terminal results awaiting Hub ACK.
/// It contains no enrollment secret. Every snapshot is DPAPI LocalMachine protected and
/// atomically replaced; progress/results are removed only after exact lease/fence ACK validation.
/// </summary>
public sealed class DpapiAgentExecutionJournal : IAgentExecutionJournal
{
    private const int MaximumProtectedBytes = 1_048_576;
    private const int MaximumActiveLeases = 16;
    private const int MaximumPendingResults = 32;
    private const int MaximumPendingProgresses = 16;
    private static readonly JsonSerializerOptions JsonOptions = CreateJsonOptions();

    private static JsonSerializerOptions CreateJsonOptions()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web)
        {
            PropertyNameCaseInsensitive = false,
            UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
        };
        options.Converters.Add(new ReadOnlyStringSetConverter());
        return options;
    }

    private sealed class ReadOnlyStringSetConverter : JsonConverter<IReadOnlySet<string>>
    {
        public override IReadOnlySet<string> Read(
            ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            var values = JsonSerializer.Deserialize<string[]>(ref reader, options) ?? [];
            if (values.Any(string.IsNullOrWhiteSpace) ||
                values.Distinct(StringComparer.Ordinal).Count() != values.Length)
                throw new JsonException("Agent task capability set is invalid.");
            return new HashSet<string>(values, StringComparer.Ordinal);
        }

        public override void Write(
            Utf8JsonWriter writer, IReadOnlySet<string> value, JsonSerializerOptions options) =>
            JsonSerializer.Serialize(writer, value.Order(StringComparer.Ordinal).ToArray(), options);
    }

    private readonly string _journalPath;
    private readonly IAgentLocalDataProtector _protector;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public DpapiAgentExecutionJournal(
        string? journalPath = null,
        IAgentLocalDataProtector? protector = null)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("The Windows Agent execution journal requires Windows DPAPI.");
        var root = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        if (string.IsNullOrWhiteSpace(root))
            throw new InvalidOperationException("The machine-wide ProgramData directory is not available.");
        _journalPath = Path.GetFullPath(journalPath ??
            Path.Combine(root, "GLAFIRA", "Agent", "execution-journal.dpapi"));
        _protector = protector ?? new WindowsDpapiAgentDataProtector();
    }

    public async Task<AgentExecutionJournalSnapshot> LoadAsync(CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try { return await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false); }
        finally { _gate.Release(); }
    }

    public async Task SaveActiveLeaseAsync(
        AgentTaskLease lease,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(lease);
        ValidateLease(lease);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var existing = snapshot.ActiveLeases.SingleOrDefault(item => item.TaskId == lease.TaskId);
            if (existing is not null)
            {
                if (existing.LeaseId != lease.LeaseId || existing.FencingToken != lease.FencingToken ||
                    existing.OwnerCoreId != lease.OwnerCoreId ||
                    existing.OwnerGeneration != lease.OwnerGeneration ||
                    existing.IdempotencyKey != lease.IdempotencyKey ||
                    existing.PayloadSha256 != lease.PayloadSha256 ||
                    existing.TaskType != lease.TaskType)
                    throw new InvalidDataException("AGENT_ACTIVE_LEASE_CONFLICT");
                if (lease.LeaseExpiresAtEpochMs > existing.LeaseExpiresAtEpochMs)
                {
                    var renewed = snapshot with
                    {
                        ActiveLeases = snapshot.ActiveLeases
                            .Select(item => item.TaskId == lease.TaskId ? lease : item).ToArray()
                    };
                    await WriteSnapshotAsync(renewed, cancellationToken).ConfigureAwait(false);
                }
                return;
            }
            if (snapshot.ActiveLeases.Count >= MaximumActiveLeases)
                throw new InvalidDataException("AGENT_ACTIVE_LEASE_LIMIT");
            if (snapshot.PendingResults.Any(item => item.Lease.TaskId == lease.TaskId))
                throw new InvalidDataException("AGENT_TASK_RESULT_STILL_PENDING");
            var next = snapshot with { ActiveLeases = snapshot.ActiveLeases.Append(lease).ToArray() };
            await WriteSnapshotAsync(next, cancellationToken).ConfigureAwait(false);
        }
        finally { _gate.Release(); }
    }

    public async Task PersistProgressAsync(
        AgentPendingProgress progress,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        ValidatePendingProgress(progress);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var active = snapshot.ActiveLeases.SingleOrDefault(item => item.TaskId == progress.Lease.TaskId);
            if (active is null || active.LeaseId != progress.Lease.LeaseId ||
                active.FencingToken != progress.Lease.FencingToken ||
                !SameOwner(active, progress.Lease))
                throw new InvalidDataException("AGENT_PROGRESS_WITHOUT_CURRENT_LEASE");
            if (snapshot.PendingResults.Any(item => item.Lease.TaskId == progress.Lease.TaskId))
                throw new InvalidDataException("AGENT_PROGRESS_AFTER_TERMINAL_RESULT");
            var existing = snapshot.PendingProgresses.SingleOrDefault(item => item.Lease.TaskId == progress.Lease.TaskId);
            if (existing is not null &&
                (existing.Lease.LeaseId != progress.Lease.LeaseId ||
                 existing.Lease.FencingToken != progress.Lease.FencingToken ||
                 !SameOwner(existing.Lease, progress.Lease)))
                throw new InvalidDataException("AGENT_PENDING_PROGRESS_CONFLICT");
            var nextProgresses = snapshot.PendingProgresses
                .Where(item => item.Lease.TaskId != progress.Lease.TaskId)
                .Append(progress).ToArray();
            if (nextProgresses.Length > MaximumPendingProgresses)
                throw new InvalidDataException("AGENT_PENDING_PROGRESS_LIMIT");
            await WriteSnapshotAsync(snapshot with { PendingProgresses = nextProgresses }, cancellationToken)
                .ConfigureAwait(false);
        }
        finally { _gate.Release(); }
    }

    public async Task<bool> AcknowledgeProgressAsync(
        string taskId,
        string leaseId,
        long fencingToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(taskId) || string.IsNullOrWhiteSpace(leaseId) || fencingToken <= 0)
            throw new ArgumentException("A complete task/lease/fence identity is required.");
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var match = snapshot.PendingProgresses.SingleOrDefault(item => item.Lease.TaskId == taskId);
            if (match is null) return false;
            if (match.Lease.LeaseId != leaseId || match.Lease.FencingToken != fencingToken)
                throw new InvalidDataException("AGENT_PROGRESS_ACK_IDENTITY_MISMATCH");
            await WriteSnapshotAsync(snapshot with
            {
                PendingProgresses = snapshot.PendingProgresses.Where(item => item.Lease.TaskId != taskId).ToArray()
            }, cancellationToken).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    public async Task<bool> DiscardStaleProgressAsync(
        string taskId,
        string leaseId,
        long fencingToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(taskId) || string.IsNullOrWhiteSpace(leaseId) || fencingToken <= 0)
            throw new ArgumentException("A complete task/lease/fence identity is required.");
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var match = snapshot.PendingProgresses.SingleOrDefault(item => item.Lease.TaskId == taskId);
            if (match is null) return false;
            if (match.Lease.LeaseId != leaseId || match.Lease.FencingToken != fencingToken)
                throw new InvalidDataException("AGENT_STALE_PROGRESS_IDENTITY_MISMATCH");
            await WriteSnapshotAsync(snapshot with
            {
                PendingProgresses = snapshot.PendingProgresses.Where(item => item.Lease.TaskId != taskId).ToArray()
            }, cancellationToken).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    public async Task PersistCompletedResultAsync(
        AgentPendingResult result,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(result);
        ValidatePendingResult(result);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var existing = snapshot.PendingResults.SingleOrDefault(item => item.Lease.TaskId == result.Lease.TaskId);
            if (existing is not null)
            {
                if (existing.Lease.LeaseId == result.Lease.LeaseId &&
                    existing.Lease.FencingToken == result.Lease.FencingToken &&
                    SameOwner(existing.Lease, result.Lease) &&
                    existing.ResultSha256 == result.ResultSha256)
                    return;
                throw new InvalidDataException("AGENT_PENDING_RESULT_CONFLICT");
            }

            var active = snapshot.ActiveLeases.SingleOrDefault(item => item.TaskId == result.Lease.TaskId);
            if (active is null || active.LeaseId != result.Lease.LeaseId ||
                active.FencingToken != result.Lease.FencingToken ||
                !SameOwner(active, result.Lease))
                throw new InvalidDataException("AGENT_RESULT_WITHOUT_CURRENT_LEASE");
            if (snapshot.PendingResults.Count >= MaximumPendingResults)
                throw new InvalidDataException("AGENT_PENDING_RESULT_LIMIT");

            var next = snapshot with
            {
                ActiveLeases = snapshot.ActiveLeases.Where(item => item.TaskId != result.Lease.TaskId).ToArray(),
                PendingResults = snapshot.PendingResults.Append(result).ToArray(),
                PendingProgresses = snapshot.PendingProgresses
                    .Where(item => item.Lease.TaskId != result.Lease.TaskId).ToArray()
            };
            await WriteSnapshotAsync(next, cancellationToken).ConfigureAwait(false);
        }
        finally { _gate.Release(); }
    }

    public async Task<bool> AcknowledgeResultAsync(
        string taskId,
        string leaseId,
        long fencingToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(taskId) || string.IsNullOrWhiteSpace(leaseId) || fencingToken <= 0)
            throw new ArgumentException("A complete task/lease/fence identity is required.");
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var match = snapshot.PendingResults.SingleOrDefault(item => item.Lease.TaskId == taskId);
            if (match is null) return false;
            if (match.Lease.LeaseId != leaseId || match.Lease.FencingToken != fencingToken)
                throw new InvalidDataException("AGENT_RESULT_ACK_IDENTITY_MISMATCH");
            var next = snapshot with
            {
                PendingResults = snapshot.PendingResults.Where(item => item.Lease.TaskId != taskId).ToArray()
            };
            await WriteSnapshotAsync(next, cancellationToken).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    public async Task<bool> ClearExpiredActiveLeaseAsync(
        string taskId,
        string leaseId,
        long fencingToken,
        long nowEpochMs,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(taskId) || string.IsNullOrWhiteSpace(leaseId) ||
            fencingToken <= 0 || nowEpochMs <= 0)
            throw new ArgumentException("A complete task/lease/fence/time identity is required.");
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var active = snapshot.ActiveLeases.SingleOrDefault(item => item.TaskId == taskId);
            if (active is null) return false;
            if (active.LeaseId != leaseId || active.FencingToken != fencingToken)
                throw new InvalidDataException("AGENT_ACTIVE_LEASE_IDENTITY_MISMATCH");
            if (active.LeaseExpiresAtEpochMs > nowEpochMs) return false;
            var next = snapshot with
            {
                ActiveLeases = snapshot.ActiveLeases.Where(item => item.TaskId != taskId).ToArray(),
                PendingProgresses = snapshot.PendingProgresses.Where(item => item.Lease.TaskId != taskId).ToArray()
            };
            await WriteSnapshotAsync(next, cancellationToken).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    public async Task<bool> DiscardStaleActiveLeaseAsync(
        string taskId,
        string leaseId,
        long fencingToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(taskId) || string.IsNullOrWhiteSpace(leaseId) || fencingToken <= 0)
            throw new ArgumentException("A complete task/lease/fence identity is required.");
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var active = snapshot.ActiveLeases.SingleOrDefault(item => item.TaskId == taskId);
            if (active is null) return false;
            if (active.LeaseId != leaseId || active.FencingToken != fencingToken)
                throw new InvalidDataException("AGENT_STALE_ACTIVE_LEASE_IDENTITY_MISMATCH");
            var next = snapshot with
            {
                ActiveLeases = snapshot.ActiveLeases.Where(item => item.TaskId != taskId).ToArray(),
                PendingProgresses = snapshot.PendingProgresses.Where(item => item.Lease.TaskId != taskId).ToArray()
            };
            await WriteSnapshotAsync(next, cancellationToken).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    public async Task<bool> DiscardStaleResultAsync(
        string taskId,
        string leaseId,
        long fencingToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(taskId) || string.IsNullOrWhiteSpace(leaseId) || fencingToken <= 0)
            throw new ArgumentException("A complete task/lease/fence identity is required.");
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var snapshot = await ReadSnapshotAsync(cancellationToken).ConfigureAwait(false);
            var pending = snapshot.PendingResults.SingleOrDefault(item => item.Lease.TaskId == taskId);
            if (pending is null) return false;
            if (pending.Lease.LeaseId != leaseId || pending.Lease.FencingToken != fencingToken)
                throw new InvalidDataException("AGENT_STALE_RESULT_IDENTITY_MISMATCH");
            var next = snapshot with
            {
                PendingResults = snapshot.PendingResults.Where(item => item.Lease.TaskId != taskId).ToArray()
            };
            await WriteSnapshotAsync(next, cancellationToken).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    private async Task<AgentExecutionJournalSnapshot> ReadSnapshotAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(_journalPath)) return AgentExecutionJournalSnapshot.Empty;
        var protectedBytes = await File.ReadAllBytesAsync(_journalPath, cancellationToken).ConfigureAwait(false);
        if (protectedBytes.Length is 0 or > MaximumProtectedBytes)
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_SIZE_INVALID");
        }

        byte[] clear;
        try { clear = _protector.Unprotect(protectedBytes); }
        catch (CryptographicException error)
        {
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_DECRYPT_FAILED", error);
        }
        finally { CryptographicOperations.ZeroMemory(protectedBytes); }

        try
        {
            if (clear.Length > MaximumProtectedBytes)
                throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_SIZE_INVALID");
            var snapshot = JsonSerializer.Deserialize<AgentExecutionJournalSnapshot>(clear, JsonOptions)
                ?? throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_EMPTY");
            ValidateSnapshot(snapshot);
            return snapshot;
        }
        catch (JsonException error)
        {
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_INVALID", error);
        }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }

    private async Task WriteSnapshotAsync(
        AgentExecutionJournalSnapshot snapshot,
        CancellationToken cancellationToken)
    {
        ValidateSnapshot(snapshot);
        var clear = JsonSerializer.SerializeToUtf8Bytes(snapshot, JsonOptions);
        if (clear.Length > MaximumProtectedBytes)
        {
            CryptographicOperations.ZeroMemory(clear);
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_SIZE_INVALID");
        }

        byte[] protectedBytes;
        try { protectedBytes = _protector.Protect(clear); }
        finally { CryptographicOperations.ZeroMemory(clear); }
        if (protectedBytes.Length is 0 or > MaximumProtectedBytes)
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_SIZE_INVALID");
        }

        var directory = Path.GetDirectoryName(_journalPath)
            ?? throw new InvalidOperationException("Execution journal path has no parent directory.");
        Directory.CreateDirectory(directory);
        RestrictDirectoryAcl(directory);
        var temporaryPath = _journalPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await File.WriteAllBytesAsync(temporaryPath, protectedBytes, cancellationToken).ConfigureAwait(false);
            RestrictFileAcl(temporaryPath);
            cancellationToken.ThrowIfCancellationRequested();
            File.Move(temporaryPath, _journalPath, overwrite: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }
    }

    private static void ValidateSnapshot(AgentExecutionJournalSnapshot snapshot)
    {
        if (snapshot.ActiveLeases is null || snapshot.PendingResults is null || snapshot.PendingProgresses is null ||
            snapshot.ActiveLeases.Count > MaximumActiveLeases ||
            snapshot.PendingResults.Count > MaximumPendingResults ||
            snapshot.PendingProgresses.Count > MaximumPendingProgresses ||
            snapshot.ActiveLeases.Any(item => item is null) ||
            snapshot.PendingResults.Any(item => item is null || item.Lease is null) ||
            snapshot.PendingProgresses.Any(item => item is null || item.Lease is null))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_STRUCTURE_INVALID");
        if (snapshot.ActiveLeases.Select(item => item.TaskId).Distinct(StringComparer.Ordinal).Count() != snapshot.ActiveLeases.Count ||
            snapshot.PendingResults.Select(item => item.Lease.TaskId).Distinct(StringComparer.Ordinal).Count() != snapshot.PendingResults.Count ||
            snapshot.PendingProgresses.Select(item => item.Lease.TaskId).Distinct(StringComparer.Ordinal).Count() != snapshot.PendingProgresses.Count)
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_DUPLICATE_TASK");
        foreach (var lease in snapshot.ActiveLeases) ValidateLease(lease);
        foreach (var result in snapshot.PendingResults) ValidatePendingResult(result);
        foreach (var progress in snapshot.PendingProgresses) ValidatePendingProgress(progress);
        if (snapshot.ActiveLeases.Any(active => snapshot.PendingResults.Any(pending => pending.Lease.TaskId == active.TaskId)) ||
            snapshot.PendingProgresses.Any(progress =>
                !snapshot.ActiveLeases.Any(active => active.TaskId == progress.Lease.TaskId &&
                    active.LeaseId == progress.Lease.LeaseId &&
                    active.FencingToken == progress.Lease.FencingToken &&
                    SameOwner(active, progress.Lease))) ||
            snapshot.PendingProgresses.Any(progress =>
                snapshot.PendingResults.Any(result => result.Lease.TaskId == progress.Lease.TaskId)))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_DUPLICATE_TASK");
    }

    private static void ValidateLease(AgentTaskLease lease)
    {
        if (string.IsNullOrWhiteSpace(lease.TaskId) || lease.TaskId.Length > 64 ||
            string.IsNullOrWhiteSpace(lease.LeaseId) || lease.LeaseId.Length > 128 ||
            lease.FencingToken <= 0 || lease.LeaseExpiresAtEpochMs <= 0 ||
            string.IsNullOrWhiteSpace(lease.ProjectId) || lease.ProjectId.Length > 128 ||
            string.IsNullOrWhiteSpace(lease.OwnerCoreId) || lease.OwnerCoreId.Length is < 8 or > 128 ||
            !System.Text.RegularExpressions.Regex.IsMatch(lease.OwnerCoreId, "^[A-Za-z0-9._-]+$") ||
            lease.OwnerGeneration <= 0 ||
            !System.Text.RegularExpressions.Regex.IsMatch(lease.PayloadSha256, "^[a-fA-F0-9]{64}$") ||
            lease.RequiredCapabilities is null || lease.RequiredCapabilities.Count > 16)
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_LEASE_INVALID");
    }

    private static bool SameOwner(AgentTaskLease left, AgentTaskLease right) =>
        StringComparer.Ordinal.Equals(left.OwnerCoreId, right.OwnerCoreId) &&
        left.OwnerGeneration == right.OwnerGeneration;

    private static void ValidatePendingProgress(AgentPendingProgress progress)
    {
        if (progress.Lease is null || progress.ProgressPercent is < 0 or > 99 ||
            string.IsNullOrWhiteSpace(progress.ProgressMessage) || progress.ProgressMessage.Length > 512)
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_PROGRESS_INVALID");
        ValidateLease(progress.Lease);
    }

    private static void ValidatePendingResult(AgentPendingResult result)
    {
        var lease = result.Lease;
        if (lease is null || result.State is not ("SUCCEEDED" or "FAILED") ||
            result.ProgressPercent is < 0 or > 100 ||
            string.IsNullOrWhiteSpace(result.ProgressMessage) || result.ProgressMessage.Length > 512 ||
            string.IsNullOrWhiteSpace(result.ResultJson) || result.ResultJson.Length > 12_000 ||
            string.IsNullOrWhiteSpace(result.ErrorCode) || result.ErrorCode.Length > 128 ||
            !System.Text.RegularExpressions.Regex.IsMatch(result.ResultSha256, "^[a-f0-9]{64}$"))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_RESULT_INVALID");
        ValidateLease(lease);
        using var parsed = JsonDocument.Parse(result.ResultJson, new JsonDocumentOptions { MaxDepth = 2 });
        if (parsed.RootElement.ValueKind is not (JsonValueKind.String or JsonValueKind.Null))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_RESULT_INVALID");
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(result.ResultJson))).ToLowerInvariant();
        if (!StringComparer.Ordinal.Equals(digest, result.ResultSha256))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_RESULT_DIGEST_MISMATCH");
    }

    private static void RestrictDirectoryAcl(string path)
    {
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        var inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl, inheritance, PropagationFlags.None, AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl, inheritance, PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(path).SetAccessControl(security);
    }

    private static void RestrictFileAcl(string path)
    {
        var security = new FileSecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        new FileInfo(path).SetAccessControl(security);
    }
}
