using System.Globalization;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using GLAFIRA.Desktop.Core.Identity;
using GLAFIRA.Desktop.Core.Ownership;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Issues a signed transfer from the authoritative Windows Desktop Core and commits
/// the task CAS, append-only envelope journal and task event in one SQLite transaction.
/// </summary>
public sealed class DesktopHubOwnershipTransferIssuer
{
    private readonly DesktopHubStore _store;
    private readonly string _ownerCoreId;
    private readonly long _signingKeyEpoch;
    private readonly byte[] _publicKey;
    private readonly string _publicKeySha256;
    private readonly Func<OwnershipTransferSigningPayload, byte[]> _sign;

    public DesktopHubOwnershipTransferIssuer(
        DesktopHubStore store,
        string ownerCoreId,
        long signingKeyEpoch,
        byte[] publicKeySubjectPublicKeyInfo,
        string publicKeySha256,
        Func<OwnershipTransferSigningPayload, byte[]> sign)
    {
        _store = store ?? throw new ArgumentNullException(nameof(store));
        ArgumentException.ThrowIfNullOrWhiteSpace(ownerCoreId);
        if (signingKeyEpoch < 1) throw new ArgumentOutOfRangeException(nameof(signingKeyEpoch));
        ArgumentNullException.ThrowIfNull(publicKeySubjectPublicKeyInfo);
        ArgumentException.ThrowIfNullOrWhiteSpace(publicKeySha256);
        _sign = sign ?? throw new ArgumentNullException(nameof(sign));

        var actualFingerprint = Convert.ToHexString(
            SHA256.HashData(publicKeySubjectPublicKeyInfo)).ToLowerInvariant();
        if (!StringComparer.Ordinal.Equals(actualFingerprint, publicKeySha256.ToLowerInvariant()))
            throw new ArgumentException("OWNERSHIP_SIGNING_KEY_FINGERPRINT_MISMATCH", nameof(publicKeySha256));

        _ownerCoreId = ownerCoreId;
        _signingKeyEpoch = signingKeyEpoch;
        _publicKey = publicKeySubjectPublicKeyInfo.ToArray();
        _publicKeySha256 = actualFingerprint;
    }

    [SupportedOSPlatform("windows")]
    public static DesktopHubOwnershipTransferIssuer ForWindowsInstallation(
        DesktopHubStore store,
        WindowsDesktopCoreInstallationIdentityStore identityStore)
    {
        ArgumentNullException.ThrowIfNull(identityStore);
        var identity = identityStore.LoadOrCreate();
        return new DesktopHubOwnershipTransferIssuer(
            store,
            identity.CoreId,
            identity.KeyEpoch,
            Convert.FromBase64String(identity.PublicKeySpkiBase64),
            identity.PublicKeySha256,
            payload => CoreOwnershipTransferCryptography.SignWithInstallationIdentity(
                identityStore, payload));
    }

    public SignedOwnershipTransferEnvelope Issue(
        string taskId,
        string newOwnerCoreId,
        DateTimeOffset now,
        TimeSpan validity)
    {
        if (validity <= TimeSpan.Zero || validity > TimeSpan.FromMinutes(15))
            throw new ArgumentOutOfRangeException(nameof(validity));
        var source = _store.GetQueuedOwnershipTransferSource(taskId) ??
            throw new InvalidOperationException("OWNERSHIP_TRANSFER_TASK_NOT_QUEUED");
        if (!StringComparer.Ordinal.Equals(source.OwnerCoreId, _ownerCoreId))
            throw new InvalidOperationException("OWNERSHIP_TRANSFER_OLD_CORE_ID_MISMATCH");
        if (source.OwnerGeneration == long.MaxValue)
            throw new InvalidOperationException("OWNERSHIP_TRANSFER_GENERATION_EXHAUSTED");

        var payload = new OwnershipTransferSigningPayload(
            SchemaVersion: 1,
            TransferId: "transfer-" + Guid.NewGuid().ToString("N"),
            Nonce: "nonce-" + Guid.NewGuid().ToString("N"),
            TaskId: source.TaskId,
            ProjectId: source.ProjectId,
            OldOwnerCoreId: source.OwnerCoreId,
            NewOwnerCoreId: newOwnerCoreId,
            OldOwnerGeneration: source.OwnerGeneration,
            NewOwnerGeneration: source.OwnerGeneration + 1,
            StateVersion: source.StateVersion,
            PayloadSha256: source.PayloadSha256,
            IdempotencyKey: source.IdempotencyKey,
            EffectRisk: OwnershipRisk(source.EffectRisk),
            ApprovalRef: source.ApprovalRef,
            IssuedAt: FormatTimestamp(now),
            ExpiresAt: FormatTimestamp(now + validity),
            SigningKeyEpoch: _signingKeyEpoch,
            SigningPublicKeySha256: _publicKeySha256,
            SignatureAlgorithm: CoreOwnershipTransferCryptography.Algorithm,
            SignatureFormat: CoreOwnershipTransferCryptography.SignatureFormat);
        var envelope = new SignedOwnershipTransferEnvelope(
            payload, Convert.ToBase64String(_sign(payload)));
        if (!_store.ApplySignedQueuedTaskOwnershipTransfer(envelope, _publicKey, now))
            throw new InvalidOperationException("OWNERSHIP_TRANSFER_CONCURRENT_STATE_CHANGE");
        return envelope;
    }

    private static string OwnershipRisk(HubTaskEffectRisk risk) => risk switch
    {
        HubTaskEffectRisk.ReadOnly => "READ_ONLY",
        HubTaskEffectRisk.LocalWrite => "DRAFT",
        HubTaskEffectRisk.ExternalSideEffect => "EXTERNAL_SIDE_EFFECT",
        _ => throw new InvalidOperationException("OWNERSHIP_TRANSFER_EFFECT_RISK_UNSUPPORTED_BY_HUB")
    };

    private static string FormatTimestamp(DateTimeOffset value) =>
        value.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
}
