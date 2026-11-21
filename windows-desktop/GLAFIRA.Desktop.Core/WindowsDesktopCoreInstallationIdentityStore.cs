using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace GLAFIRA.Desktop.Core.Identity;

public sealed record DesktopCoreInstallationIdentity(
    string CoreId,
    string CoreKind,
    long KeyEpoch,
    string PublicKeySpkiBase64,
    string PublicKeySha256,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset KeyCreatedAtUtc,
    string? PreviousPublicKeySha256 = null);

/// <summary>
/// Stable Windows Desktop Core installation identity. The signing key is persisted by
/// the Windows CNG software KSP as non-exportable; only DPAPI CurrentUser-protected
/// metadata is stored in the profile. This identity is deliberately independent of HubId,
/// TLS certificates, Agent credentials and portable Core backups.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsDesktopCoreInstallationIdentityStore
{
    private const string CoreKind = "WINDOWS_DESKTOP";
    private static readonly byte[] Entropy =
        Encoding.UTF8.GetBytes("GLAFIRA.Desktop.Core.InstallationIdentity.v1");
    private readonly string _identityPath;
    private readonly string _mutexName;

    private sealed record PersistedIdentity(
        string CoreId,
        long KeyEpoch,
        DateTimeOffset CreatedAtUtc,
        DateTimeOffset KeyCreatedAtUtc,
        string PublicKeySpkiBase64,
        string PublicKeySha256);

    public WindowsDesktopCoreInstallationIdentityStore(string? identityPath = null)
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(root))
            throw new InvalidOperationException("DESKTOP_CORE_PROFILE_UNAVAILABLE");

        _identityPath = Path.GetFullPath(identityPath ??
            Path.Combine(root, "GLAFIRA", "DesktopCore", "installation-identity.dpapi"));
        var mutexDigest = Convert.ToHexString(SHA256.HashData(
            Encoding.UTF8.GetBytes(_identityPath.ToUpperInvariant())));
        _mutexName = "Local\\GLAFIRA.Desktop.Core.Identity." + mutexDigest;
    }

    public DesktopCoreInstallationIdentity LoadOrCreate()
    {
        EnsureWindows();
        using var mutex = new Mutex(false, _mutexName);
        EnterMutex(mutex);
        try
        {
            if (!File.Exists(_identityPath))
                return CreateFirstIdentity();
            return LoadExisting();
        }
        finally
        {
            mutex.ReleaseMutex();
        }
    }

    /// <summary>
    /// Rotates the signing key without changing the installation Core ID. The new
    /// protected metadata is atomically committed before the old CNG key is deleted.
    /// A corrupt metadata file or missing/mismatched key is never silently replaced.
    /// </summary>
    public DesktopCoreInstallationIdentity RotateKey()
    {
        EnsureWindows();
        using var mutex = new Mutex(false, _mutexName);
        EnterMutex(mutex);
        try
        {
            var existing = ReadAndValidate();
            if (existing.KeyEpoch == long.MaxValue)
                throw new InvalidOperationException("DESKTOP_CORE_KEY_EPOCH_EXHAUSTED");

            var nextEpoch = existing.KeyEpoch + 1;
            var nextKeyName = KeyName(existing.CoreId, nextEpoch);
            using var nextKey = CreateSigningKey(nextKeyName);
            try
            {
                var publicKey = ExportPublicKey(nextKey);
                var next = new PersistedIdentity(
                    existing.CoreId,
                    nextEpoch,
                    existing.CreatedAtUtc,
                    DateTimeOffset.UtcNow,
                    Convert.ToBase64String(publicKey),
                    Fingerprint(publicKey));
                Persist(next);
                try
                {
                    using var previousKey = CngKey.Open(
                        KeyName(existing.CoreId, existing.KeyEpoch),
                        CngProvider.MicrosoftSoftwareKeyStorageProvider);
                    previousKey.Delete();
                }
                catch (CryptographicException ex)
                {
                    throw new InvalidOperationException(
                        "DESKTOP_CORE_IDENTITY_ROTATED_OLD_KEY_CLEANUP_REQUIRED", ex);
                }

                return ToPublicIdentity(next, existing.PublicKeySha256);
            }
            catch
            {
                // If metadata already points to the new key, it must remain available.
                if (!MetadataPointsTo(existing.CoreId, nextEpoch))
                {
                    try { nextKey.Delete(); } catch (CryptographicException) { }
                }
                throw;
            }
        }
        finally
        {
            mutex.ReleaseMutex();
        }
    }

    public byte[] Sign(ReadOnlySpan<byte> data)
    {
        EnsureWindows();
        using var mutex = new Mutex(false, _mutexName);
        EnterMutex(mutex);
        try
        {
            var identity = ReadAndValidate();
            using var key = CngKey.Open(
                KeyName(identity.CoreId, identity.KeyEpoch),
                CngProvider.MicrosoftSoftwareKeyStorageProvider);
            using var signer = new ECDsaCng(key);
            return signer.SignData(
                data.ToArray(),
                HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        }
        finally
        {
            mutex.ReleaseMutex();
        }
    }

    private DesktopCoreInstallationIdentity CreateFirstIdentity()
    {
        var coreId = Guid.NewGuid().ToString("N");
        const long epoch = 1;
        var keyName = KeyName(coreId, epoch);
        using var key = CreateSigningKey(keyName);
        try
        {
            var publicKey = ExportPublicKey(key);
            var now = DateTimeOffset.UtcNow;
            var persisted = new PersistedIdentity(
                coreId, epoch, now, now,
                Convert.ToBase64String(publicKey), Fingerprint(publicKey));
            Persist(persisted);
            return ToPublicIdentity(persisted, null);
        }
        catch
        {
            try { key.Delete(); } catch (CryptographicException) { }
            throw;
        }
    }

    private DesktopCoreInstallationIdentity LoadExisting() =>
        ToPublicIdentity(ReadAndValidate(), null);

    private PersistedIdentity ReadAndValidate()
    {
        byte[] protectedBytes;
        try
        {
            protectedBytes = File.ReadAllBytes(_identityPath);
        }
        catch (IOException ex)
        {
            throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_METADATA_UNREADABLE", ex);
        }

        byte[] clear;
        try
        {
            clear = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException ex)
        {
            throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_METADATA_INVALID", ex);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
        }

        PersistedIdentity persisted;
        try
        {
            persisted = JsonSerializer.Deserialize<PersistedIdentity>(clear)
                ?? throw new JsonException("Empty identity metadata.");
        }
        catch (JsonException ex)
        {
            throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_METADATA_INVALID", ex);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
        }

        if (!Guid.TryParseExact(persisted.CoreId, "N", out _) ||
            persisted.KeyEpoch <= 0 ||
            persisted.CreatedAtUtc == default ||
            persisted.KeyCreatedAtUtc == default)
            throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_METADATA_INVALID");

        try
        {
            using var key = CngKey.Open(
                KeyName(persisted.CoreId, persisted.KeyEpoch),
                CngProvider.MicrosoftSoftwareKeyStorageProvider);
            if (key.ExportPolicy != CngExportPolicies.None)
                throw new InvalidOperationException("DESKTOP_CORE_SIGNING_KEY_MUST_BE_NON_EXPORTABLE");

            var actualPublicKey = ExportPublicKey(key);
            var expectedPublicKey = Convert.FromBase64String(persisted.PublicKeySpkiBase64);
            if (!CryptographicOperations.FixedTimeEquals(actualPublicKey, expectedPublicKey) ||
                !StringComparer.Ordinal.Equals(Fingerprint(actualPublicKey), persisted.PublicKeySha256))
                throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_KEY_MISMATCH");
        }
        catch (CryptographicException ex)
        {
            throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_KEY_MISSING_OR_INVALID", ex);
        }
        catch (FormatException ex)
        {
            throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_METADATA_INVALID", ex);
        }

        return persisted;
    }

    private bool MetadataPointsTo(string coreId, long epoch)
    {
        try
        {
            var persisted = ReadAndValidate();
            return StringComparer.Ordinal.Equals(persisted.CoreId, coreId) &&
                persisted.KeyEpoch == epoch;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    private void Persist(PersistedIdentity identity)
    {
        var directory = Path.GetDirectoryName(_identityPath);
        if (string.IsNullOrWhiteSpace(directory))
            throw new InvalidOperationException("DESKTOP_CORE_IDENTITY_PATH_INVALID");
        Directory.CreateDirectory(directory);

        var clear = JsonSerializer.SerializeToUtf8Bytes(identity);
        byte[] protectedBytes;
        try
        {
            protectedBytes = ProtectedData.Protect(clear, Entropy, DataProtectionScope.CurrentUser);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
        }

        var temporaryPath = _identityPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = new FileStream(
                temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                4096, FileOptions.WriteThrough))
            {
                stream.Write(protectedBytes);
                stream.Flush(true);
            }
            File.Move(temporaryPath, _identityPath, true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    private static CngKey CreateSigningKey(string keyName)
    {
        var parameters = new CngKeyCreationParameters
        {
            Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider,
            KeyUsage = CngKeyUsages.Signing,
            ExportPolicy = CngExportPolicies.None,
            KeyCreationOptions = CngKeyCreationOptions.None
        };
        return CngKey.Create(CngAlgorithm.ECDsaP256, keyName, parameters);
    }

    private static byte[] ExportPublicKey(CngKey key)
    {
        using var signer = new ECDsaCng(key);
        return signer.ExportSubjectPublicKeyInfo();
    }

    private static string Fingerprint(byte[] publicKey) =>
        Convert.ToHexString(SHA256.HashData(publicKey));

    private static string KeyName(string coreId, long epoch) =>
        "GLAFIRA.Desktop.Core." + coreId + ".E" + epoch.ToString(
            System.Globalization.CultureInfo.InvariantCulture);

    private static DesktopCoreInstallationIdentity ToPublicIdentity(
        PersistedIdentity identity,
        string? previousFingerprint) =>
        new(
            identity.CoreId,
            CoreKind,
            identity.KeyEpoch,
            identity.PublicKeySpkiBase64,
            identity.PublicKeySha256,
            identity.CreatedAtUtc,
            identity.KeyCreatedAtUtc,
            previousFingerprint);

    private static void EnterMutex(Mutex mutex)
    {
        try
        {
            if (!mutex.WaitOne(TimeSpan.FromSeconds(20)))
                throw new TimeoutException("DESKTOP_CORE_IDENTITY_LOCK_TIMEOUT");
        }
        catch (AbandonedMutexException)
        {
            // The abandoned mutex is acquired by this thread; validate durable state below.
        }
    }

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Windows CNG and DPAPI are required.");
    }
}
