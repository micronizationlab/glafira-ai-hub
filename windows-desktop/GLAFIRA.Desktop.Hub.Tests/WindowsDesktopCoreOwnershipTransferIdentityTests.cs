using System.Runtime.Versioning;
using System.Security.Cryptography;
using GLAFIRA.Desktop.Core.Identity;
using GLAFIRA.Desktop.Core.Ownership;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

[SupportedOSPlatform("windows")]
public sealed class WindowsDesktopCoreOwnershipTransferIdentityTests : IDisposable
{
    private readonly string _identityPath = Path.Combine(
        Path.GetTempPath(), "glafira-core-transfer-identity-" + Guid.NewGuid().ToString("N") + ".dpapi");
    private DesktopCoreInstallationIdentity? _identity;

    [Fact]
    public void OwnershipTransferSignatureUsesCurrentDesktopCoreIdentityAndP1363()
    {
        var store = new WindowsDesktopCoreInstallationIdentityStore(_identityPath);
        _identity = store.LoadOrCreate();
        var payload = CreatePayload(_identity);

        var signature = CoreOwnershipTransferCryptography.SignWithInstallationIdentity(store, payload);
        var publicKey = Convert.FromBase64String(_identity.PublicKeySpkiBase64);

        Assert.Equal(64, signature.Length);
        Assert.True(CoreOwnershipTransferCryptography.Verify(payload, publicKey, signature));
        Assert.Throws<InvalidOperationException>(() =>
            CoreOwnershipTransferCryptography.SignWithInstallationIdentity(
                store, payload with { OldOwnerCoreId = "different-core-0001" }));
        Assert.Throws<InvalidOperationException>(() =>
            CoreOwnershipTransferCryptography.SignWithInstallationIdentity(
                store, payload with { SigningKeyEpoch = _identity.KeyEpoch + 1 }));
    }

    private static OwnershipTransferSigningPayload CreatePayload(DesktopCoreInstallationIdentity identity) =>
        new(
            SchemaVersion: 1,
            TransferId: "transfer-desktop-0001",
            Nonce: "nonce-desktop-0001",
            TaskId: "desktop-task-0001",
            ProjectId: "desktop-project-01",
            OldOwnerCoreId: identity.CoreId,
            NewOwnerCoreId: "android-core-0001",
            OldOwnerGeneration: 1,
            NewOwnerGeneration: 2,
            StateVersion: 0,
            PayloadSha256: new string('a', 64),
            IdempotencyKey: "desktop-idempotency-01",
            EffectRisk: "READ_ONLY",
            ApprovalRef: null,
            IssuedAt: "2026-11-21T12:00:00.000Z",
            ExpiresAt: "2026-11-21T12:05:00.000Z",
            SigningKeyEpoch: identity.KeyEpoch,
            SigningPublicKeySha256: identity.PublicKeySha256.ToLowerInvariant(),
            SignatureAlgorithm: CoreOwnershipTransferCryptography.Algorithm,
            SignatureFormat: CoreOwnershipTransferCryptography.SignatureFormat);

    public void Dispose()
    {
        if (_identity is not null)
        {
            try
            {
                using var key = CngKey.Open(
                    "GLAFIRA.Desktop.Core." + _identity.CoreId + ".E" + _identity.KeyEpoch,
                    CngProvider.MicrosoftSoftwareKeyStorageProvider);
                key.Delete();
            }
            catch (CryptographicException)
            {
                // Test key may already have been retired by a prior failure/cleanup.
            }
        }

        if (File.Exists(_identityPath))
            File.Delete(_identityPath);
    }
}
