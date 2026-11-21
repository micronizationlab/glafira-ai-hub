using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using GLAFIRA.Desktop.Core.Identity;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

[SupportedOSPlatform("windows")]
public sealed class WindowsDesktopCoreInstallationIdentityStoreTests : IDisposable
{
    private readonly string _identityPath = Path.Combine(
        Path.GetTempPath(), "glafira-desktop-core-identity-" + Guid.NewGuid().ToString("N") + ".dpapi");

    [Fact]
    public void LoadOrCreatePersistsStableCoreIdAndNonExportableSigningIdentity()
    {
        var store = new WindowsDesktopCoreInstallationIdentityStore(_identityPath);
        var first = store.LoadOrCreate();
        var second = store.LoadOrCreate();
        var payload = Encoding.UTF8.GetBytes("GLAFIRA desktop core identity proof v1");
        var signature = store.Sign(payload);

        using var verifier = ECDsa.Create();
        verifier.ImportSubjectPublicKeyInfo(
            Convert.FromBase64String(first.PublicKeySpkiBase64), out var bytesRead);

        Assert.Equal("WINDOWS_DESKTOP", first.CoreKind);
        Assert.Equal(first.CoreId, second.CoreId);
        Assert.Equal(1, first.KeyEpoch);
        Assert.Equal(first.PublicKeySha256, second.PublicKeySha256);
        Assert.Equal(first.PublicKeySha256, Convert.ToHexString(
            SHA256.HashData(Convert.FromBase64String(first.PublicKeySpkiBase64))));
        Assert.Equal(Convert.FromBase64String(first.PublicKeySpkiBase64).Length, bytesRead);
        Assert.True(verifier.VerifyData(payload, signature, HashAlgorithmName.SHA256));

        var protectedMetadata = Encoding.UTF8.GetString(File.ReadAllBytes(_identityPath));
        Assert.DoesNotContain(first.CoreId, protectedMetadata);
        Assert.DoesNotContain("PublicKeySpkiBase64", protectedMetadata);
        Assert.DoesNotContain("KeyEpoch", protectedMetadata);
    }

    [Fact]
    public void RotationIncrementsKeyEpochWithoutChangingCoreIdAndRetiresPreviousKey()
    {
        var store = new WindowsDesktopCoreInstallationIdentityStore(_identityPath);
        var first = store.LoadOrCreate();
        var beforePayload = Encoding.UTF8.GetBytes("before rotation");
        var beforeSignature = store.Sign(beforePayload);

        var rotated = store.RotateKey();
        var afterPayload = Encoding.UTF8.GetBytes("after rotation");
        var afterSignature = store.Sign(afterPayload);

        using var oldVerifier = ECDsa.Create();
        oldVerifier.ImportSubjectPublicKeyInfo(
            Convert.FromBase64String(first.PublicKeySpkiBase64), out _);
        using var newVerifier = ECDsa.Create();
        newVerifier.ImportSubjectPublicKeyInfo(
            Convert.FromBase64String(rotated.PublicKeySpkiBase64), out _);

        Assert.Equal(first.CoreId, rotated.CoreId);
        Assert.Equal(first.KeyEpoch + 1, rotated.KeyEpoch);
        Assert.Equal(first.PublicKeySha256, rotated.PreviousPublicKeySha256);
        Assert.NotEqual(first.PublicKeySha256, rotated.PublicKeySha256);
        Assert.False(oldVerifier.VerifyData(afterPayload, afterSignature, HashAlgorithmName.SHA256));
        Assert.True(newVerifier.VerifyData(afterPayload, afterSignature, HashAlgorithmName.SHA256));
        Assert.True(oldVerifier.VerifyData(beforePayload, beforeSignature, HashAlgorithmName.SHA256));

        var reloaded = store.LoadOrCreate();
        Assert.Equal(rotated.CoreId, reloaded.CoreId);
        Assert.Equal(rotated.KeyEpoch, reloaded.KeyEpoch);
        Assert.Equal(rotated.PublicKeySha256, reloaded.PublicKeySha256);
    }

    [Fact]
    public void CorruptProtectedMetadataFailsClosedInsteadOfCreatingAnotherIdentity()
    {
        var store = new WindowsDesktopCoreInstallationIdentityStore(_identityPath);
        var original = store.LoadOrCreate();
        var protectedMetadata = File.ReadAllBytes(_identityPath);
        protectedMetadata[protectedMetadata.Length / 2] ^= 0x5A;
        File.WriteAllBytes(_identityPath, protectedMetadata);

        var error = Assert.Throws<InvalidOperationException>(() => store.LoadOrCreate());

        Assert.Contains("DESKTOP_CORE_IDENTITY_METADATA_INVALID", error.Message);
        Assert.True(File.Exists(_identityPath));
    }

    public void Dispose()
    {
        foreach (var path in new[] { _identityPath, _identityPath + ".tmp" })
        {
            if (File.Exists(path))
                File.Delete(path);
        }
    }
}
