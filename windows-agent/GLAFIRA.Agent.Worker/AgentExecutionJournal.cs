using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace GLAFIRA.Agent.Worker;

public sealed record AgentPendingResult(
    string TaskId,
    string LeaseId,
    long FencingToken,
    string State,
    int ProgressPercent,
    string ProgressMessage,
    string ResultJson,
    string ErrorCode,
    long LeaseExpiresAtEpochMs,
    string ResultSha256);

public sealed record AgentExecutionJournalSnapshot(
    IReadOnlyList<AgentTaskLease> ActiveLeases,
    IReadOnlyList<AgentPendingResult> PendingResults)
{
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
    Task PersistCompletedResultAsync(AgentPendingResult result, CancellationToken cancellationToken);
    Task<bool> AcknowledgeResultAsync(
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
/// Durable write-ahead journal for claimed leases and terminal results awaiting Hub ACK.
/// It contains no enrollment secret. Every snapshot is DPAPI LocalMachine protected and
/// atomically replaced; a result is removed only after the exact lease/fence ACK is verified.
/// </summary>
public sealed class DpapiAgentExecutionJournal : IAgentExecutionJournal
{
    private const int MaximumProtectedBytes = 1_048_576;
    private const int MaximumActiveLeases = 16;
    private const int MaximumPendingResults = 32;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = System.Text.Json.Serialization.JsonUnmappedMemberHandling.Disallow
    };

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
                if (existing.LeaseId == lease.LeaseId && existing.FencingToken == lease.FencingToken)
                    return;
                throw new InvalidDataException("AGENT_ACTIVE_LEASE_CONFLICT");
            }
            if (snapshot.ActiveLeases.Count >= MaximumActiveLeases)
                throw new InvalidDataException("AGENT_ACTIVE_LEASE_LIMIT");
            if (snapshot.PendingResults.Any(item => item.TaskId == lease.TaskId))
                throw new InvalidDataException("AGENT_TASK_RESULT_STILL_PENDING");
            var next = snapshot with { ActiveLeases = snapshot.ActiveLeases.Append(lease).ToArray() };
            await WriteSnapshotAsync(next, cancellationToken).ConfigureAwait(false);
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
            var existing = snapshot.PendingResults.SingleOrDefault(item => item.TaskId == result.TaskId);
            if (existing is not null)
            {
                if (existing.LeaseId == result.LeaseId &&
                    existing.FencingToken == result.FencingToken &&
                    existing.ResultSha256 == result.ResultSha256)
                    return;
                throw new InvalidDataException("AGENT_PENDING_RESULT_CONFLICT");
            }

            var active = snapshot.ActiveLeases.SingleOrDefault(item => item.TaskId == result.TaskId);
            if (active is null || active.LeaseId != result.LeaseId ||
                active.FencingToken != result.FencingToken)
                throw new InvalidDataException("AGENT_RESULT_WITHOUT_CURRENT_LEASE");
            if (snapshot.PendingResults.Count >= MaximumPendingResults)
                throw new InvalidDataException("AGENT_PENDING_RESULT_LIMIT");

            var next = snapshot with
            {
                ActiveLeases = snapshot.ActiveLeases.Where(item => item.TaskId != result.TaskId).ToArray(),
                PendingResults = snapshot.PendingResults.Append(result).ToArray()
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
            var match = snapshot.PendingResults.SingleOrDefault(item => item.TaskId == taskId);
            if (match is null) return false;
            if (match.LeaseId != leaseId || match.FencingToken != fencingToken)
                throw new InvalidDataException("AGENT_RESULT_ACK_IDENTITY_MISMATCH");
            var next = snapshot with
            {
                PendingResults = snapshot.PendingResults.Where(item => item.TaskId != taskId).ToArray()
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
        if (snapshot.ActiveLeases is null || snapshot.PendingResults is null ||
            snapshot.ActiveLeases.Count > MaximumActiveLeases ||
            snapshot.PendingResults.Count > MaximumPendingResults ||
            snapshot.ActiveLeases.Select(item => item.TaskId).Distinct(StringComparer.Ordinal).Count() != snapshot.ActiveLeases.Count ||
            snapshot.PendingResults.Select(item => item.TaskId).Distinct(StringComparer.Ordinal).Count() != snapshot.PendingResults.Count ||
            snapshot.ActiveLeases.Any(item => item is null) ||
            snapshot.PendingResults.Any(item => item is null))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_STRUCTURE_INVALID");
        foreach (var lease in snapshot.ActiveLeases) ValidateLease(lease);
        foreach (var result in snapshot.PendingResults) ValidatePendingResult(result);
        if (snapshot.ActiveLeases.Any(active => snapshot.PendingResults.Any(pending => pending.TaskId == active.TaskId)))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_DUPLICATE_TASK");
    }

    private static void ValidateLease(AgentTaskLease lease)
    {
        if (string.IsNullOrWhiteSpace(lease.TaskId) || lease.TaskId.Length > 64 ||
            string.IsNullOrWhiteSpace(lease.LeaseId) || lease.LeaseId.Length > 128 ||
            lease.FencingToken <= 0 || lease.LeaseExpiresAtEpochMs <= 0 ||
            string.IsNullOrWhiteSpace(lease.ProjectId) || lease.ProjectId.Length > 128 ||
            !System.Text.RegularExpressions.Regex.IsMatch(lease.PayloadSha256, "^[a-fA-F0-9]{64}$") ||
            lease.RequiredCapabilities is null || lease.RequiredCapabilities.Count > 16)
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_LEASE_INVALID");
    }

    private static void ValidatePendingResult(AgentPendingResult result)
    {
        if (string.IsNullOrWhiteSpace(result.TaskId) || result.TaskId.Length > 64 ||
            string.IsNullOrWhiteSpace(result.LeaseId) || result.LeaseId.Length > 128 ||
            result.FencingToken <= 0 || result.State is not ("SUCCEEDED" or "FAILED") ||
            result.ProgressPercent is < 0 or > 100 ||
            string.IsNullOrWhiteSpace(result.ProgressMessage) || result.ProgressMessage.Length > 512 ||
            string.IsNullOrWhiteSpace(result.ResultJson) || result.ResultJson.Length > 12_000 ||
            string.IsNullOrWhiteSpace(result.ErrorCode) || result.ErrorCode.Length > 128 ||
            result.LeaseExpiresAtEpochMs <= 0 ||
            !System.Text.RegularExpressions.Regex.IsMatch(result.ResultSha256, "^[a-f0-9]{64}$"))
            throw new InvalidDataException("AGENT_EXECUTION_JOURNAL_RESULT_INVALID");
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
