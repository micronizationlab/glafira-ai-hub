using System.Security.Cryptography;
using System.Text;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// The only owner-enqueueable Agent task in the first production slice. The request has
/// no caller-controlled instruction, path, command or source; its content-address is
/// pinned independently by both Hub and Agent.
/// </summary>
public static class HubDiagnosticsTaskContract
{
    public const string TaskType = "diagnostics.snapshot";
    public const string RequiredCapability = "agent.diagnostics.read";
    public const string CanonicalRequestJson =
        "{\"schemaVersion\":1,\"source\":\"local-safe-runtime\"}";
    public const string RequestPayloadSha256 =
        "b8bed4167621e0151c9ad655844d0574be18d9a7cae05f998b035c7634efbae4";
    public const string RequestPayloadReference = RequestPayloadSha256;
}

public sealed class HubDiagnosticsTaskService
{
    private readonly DesktopHubStore _store;
    private readonly HubTaskPayloadStore _payloadStore;
    private readonly string _ownerCoreId;
    private readonly long _ownerGeneration;

    public HubDiagnosticsTaskService(
        DesktopHubStore store,
        HubTaskPayloadStore payloadStore,
        string ownerCoreId,
        long ownerGeneration = 1)
    {
        _store = store ?? throw new ArgumentNullException(nameof(store));
        _payloadStore = payloadStore ?? throw new ArgumentNullException(nameof(payloadStore));
        ArgumentException.ThrowIfNullOrWhiteSpace(ownerCoreId);
        if (ownerGeneration <= 0) throw new ArgumentOutOfRangeException(nameof(ownerGeneration));
        _ownerCoreId = ownerCoreId;
        _ownerGeneration = ownerGeneration;
    }

    public async Task<HubDiagnosticsTaskSubmission> QueueAsync(
        string projectId,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(projectId);
        if (projectId.Length > 128)
            throw new ArgumentException("Project identifier exceeds 128 characters.", nameof(projectId));
        if (now.ToUnixTimeMilliseconds() <= 0)
            throw new ArgumentOutOfRangeException(nameof(now));

        var payload = Encoding.UTF8.GetBytes(HubDiagnosticsTaskContract.CanonicalRequestJson);
        HubTaskPayloadReference reference;
        try
        {
            var actual = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();
            if (!StringComparer.Ordinal.Equals(actual, HubDiagnosticsTaskContract.RequestPayloadSha256))
                throw new InvalidOperationException("DIAGNOSTICS_REQUEST_CONTRACT_DIGEST_MISMATCH");
            reference = await _payloadStore.StoreAsync(payload, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(payload);
        }

        if (!StringComparer.Ordinal.Equals(
                reference.PayloadRef, HubDiagnosticsTaskContract.RequestPayloadReference) ||
            !StringComparer.Ordinal.Equals(
                reference.PayloadSha256, HubDiagnosticsTaskContract.RequestPayloadSha256))
            throw new InvalidDataException("DIAGNOSTICS_REQUEST_CONTENT_ADDRESS_MISMATCH");

        var taskId = "diagnostics-" + Guid.NewGuid().ToString("N");
        _store.Enqueue(new HubTaskRequest(
            taskId,
            projectId,
            _ownerCoreId,
            _ownerGeneration,
            "enqueue-" + taskId,
            HubDiagnosticsTaskContract.TaskType,
            reference.PayloadRef,
            reference.PayloadSha256,
            [HubDiagnosticsTaskContract.RequiredCapability],
            [],
            HubTaskEffectRisk.ReadOnly,
            ApprovalRef: null,
            Priority: 10), now);

        return new HubDiagnosticsTaskSubmission(
            taskId, projectId, reference.PayloadRef, reference.PayloadSha256, now);
    }
}
