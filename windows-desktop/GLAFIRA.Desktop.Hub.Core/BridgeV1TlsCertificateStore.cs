using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Runtime.Versioning;
using System.Text;
using System.Text.Json;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record BridgeV1TlsCertificateIdentity(
    string HubId,
    IReadOnlyList<IPAddress> BindAddresses,
    X509Certificate2 Certificate,
    string FingerprintSha256,
    DateTimeOffset NotAfter,
    string? PreviousFingerprintSha256 = null);

/// <summary>
/// Persists the Desktop Hub TLS private key only as a DPAPI CurrentUser-protected PFX.
/// A SAN or identity change requires an explicit Rotate call so clients are never silently
/// disconnected by an automatic certificate replacement.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class BridgeV1TlsCertificateStore
{
    private static readonly byte[] Entropy =
        Encoding.UTF8.GetBytes("GLAFIRA.Desktop.Hub.BridgeTlsIdentity.v1");

    private readonly string _identityPath;
    private readonly TimeProvider _timeProvider;

    private sealed record PersistedIdentity(
        string HubId,
        string[] BindAddresses,
        string FingerprintSha256,
        string PfxBase64);

    public BridgeV1TlsCertificateStore(string? identityPath = null, TimeProvider? timeProvider = null)
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(root))
            throw new InvalidOperationException("The Desktop Hub user profile is not available.");
        _identityPath = Path.GetFullPath(identityPath ??
            Path.Combine(root, "GLAFIRA", "DesktopHub", "bridge-tls-identity.dpapi"));
        _timeProvider = timeProvider ?? TimeProvider.System;
    }

    public BridgeV1TlsCertificateIdentity LoadOrCreate(
        string hubId,
        IReadOnlyCollection<IPAddress> bindAddresses)
    {
        ValidateIdentity(hubId, bindAddresses);
        var normalized = NormalizeAddresses(bindAddresses);
        if (!File.Exists(_identityPath))
            return CreateAndPersist(hubId, normalized, previousFingerprint: null);

        var existing = LoadPersisted();
        if (!StringComparer.Ordinal.Equals(existing.HubId, hubId) ||
            !existing.BindAddresses.SequenceEqual(normalized.Select(address => address.ToString()), StringComparer.Ordinal))
        {
            existing.Certificate.Dispose();
            throw new InvalidOperationException("BRIDGE_TLS_ROTATION_REQUIRED");
        }

        if (existing.Certificate.NotAfter <= _timeProvider.GetUtcNow().AddDays(30))
        {
            existing.Certificate.Dispose();
            throw new InvalidOperationException("BRIDGE_TLS_ROTATION_REQUIRED");
        }

        return existing;
    }

    /// <summary>
    /// Explicit owner-controlled certificate rotation. The previous fingerprint is returned
    /// for diagnostics and re-enrollment; the caller must not assume existing Agent pins update.
    /// </summary>
    public BridgeV1TlsCertificateIdentity Rotate(
        string hubId,
        IReadOnlyCollection<IPAddress> bindAddresses)
    {
        ValidateIdentity(hubId, bindAddresses);
        var normalized = NormalizeAddresses(bindAddresses);
        string? previous = null;
        if (File.Exists(_identityPath))
        {
            var existing = LoadPersisted();
            previous = existing.FingerprintSha256;
            existing.Certificate.Dispose();
        }
        return CreateAndPersist(hubId, normalized, previous);
    }

    private BridgeV1TlsCertificateIdentity CreateAndPersist(
        string hubId,
        IReadOnlyList<IPAddress> addresses,
        string? previousFingerprint)
    {
        var now = _timeProvider.GetUtcNow();
        using var rsa = RSA.Create(3072);
        var request = new CertificateRequest(
            new X500DistinguishedName($"CN=GLAFIRA Desktop Hub {hubId}"),
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);

        var san = new SubjectAlternativeNameBuilder();
        foreach (var address in addresses)
            san.AddIpAddress(address);
        request.CertificateExtensions.Add(san.Build());
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        var usages = new OidCollection { new("1.3.6.1.5.5.7.3.1") };
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(usages, true));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));

        using var issued = request.CreateSelfSigned(now.AddMinutes(-5), now.AddDays(180));
        var pfx = issued.Export(X509ContentType.Pfx);
        try
        {
            var certificate = new X509Certificate2(
                pfx, (string?)null, X509KeyStorageFlags.EphemeralKeySet);
            var fingerprint = Fingerprint(certificate);
            var persisted = new PersistedIdentity(
                hubId,
                addresses.Select(address => address.ToString()).ToArray(),
                fingerprint,
                Convert.ToBase64String(pfx));
            var clear = JsonSerializer.SerializeToUtf8Bytes(persisted);
            byte[] protectedBytes;
            try
            {
                protectedBytes = ProtectedData.Protect(
                    clear, Entropy, DataProtectionScope.CurrentUser);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(clear);
            }

            try
            {
                WriteAtomically(protectedBytes);
            }
            catch
            {
                certificate.Dispose();
                throw;
            }
            finally
            {
                CryptographicOperations.ZeroMemory(protectedBytes);
            }

            return new BridgeV1TlsCertificateIdentity(
                hubId, addresses, certificate, fingerprint, certificate.NotAfter, previousFingerprint);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }

    private BridgeV1TlsCertificateIdentity LoadPersisted()
    {
        var protectedBytes = File.ReadAllBytes(_identityPath);
        byte[] clear;
        try
        {
            clear = ProtectedData.Unprotect(
                protectedBytes, Entropy, DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException error)
        {
            throw new InvalidDataException(
                "Desktop Hub TLS identity cannot be decrypted for this Windows user.", error);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
        }

        try
        {
            var persisted = JsonSerializer.Deserialize<PersistedIdentity>(clear)
                ?? throw new InvalidDataException("Desktop Hub TLS identity is empty.");
            var pfx = Convert.FromBase64String(persisted.PfxBase64);
            try
            {
                var certificate = new X509Certificate2(
                    pfx, (string?)null, X509KeyStorageFlags.EphemeralKeySet);
                var fingerprint = Fingerprint(certificate);
                if (string.IsNullOrWhiteSpace(persisted.HubId) ||
                    persisted.BindAddresses is null ||
                    persisted.BindAddresses.Length is < 1 or > 8 ||
                    !StringComparer.Ordinal.Equals(fingerprint, persisted.FingerprintSha256) ||
                    !certificate.HasPrivateKey ||
                    certificate.NotBefore > _timeProvider.GetUtcNow().AddMinutes(5))
                {
                    certificate.Dispose();
                    throw new InvalidDataException("Desktop Hub TLS identity integrity or validity check failed.");
                }

                var addresses = persisted.BindAddresses.Select(IPAddress.Parse).ToArray();
                ValidateIdentity(persisted.HubId, addresses);
                return new BridgeV1TlsCertificateIdentity(
                    persisted.HubId, addresses, certificate, fingerprint, certificate.NotAfter);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(pfx);
            }
        }
        catch (Exception error) when (error is JsonException or FormatException or CryptographicException)
        {
            throw new InvalidDataException("Desktop Hub TLS identity is corrupt.", error);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
        }
    }

    private void WriteAtomically(byte[] protectedBytes)
    {
        var directory = Path.GetDirectoryName(_identityPath)
            ?? throw new InvalidOperationException("TLS identity path has no parent directory.");
        Directory.CreateDirectory(directory);
        var temporaryPath = _identityPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = new FileStream(
                temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                4096, FileOptions.WriteThrough))
            {
                stream.Write(protectedBytes);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, _identityPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    private static void ValidateIdentity(string hubId, IReadOnlyCollection<IPAddress> addresses)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(hubId);
        if (hubId.Length is < 8 or > 128 ||
            hubId.Any(character => !(char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.')))
            throw new ArgumentException("Hub ID must be a stable 8–128 character identifier.", nameof(hubId));
        ArgumentNullException.ThrowIfNull(addresses);
        if (addresses.Count is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(addresses), "One to eight private LAN addresses are required.");
        if (addresses.Distinct().Count() != addresses.Count || addresses.Any(address => !IsPrivateLanAddress(address)))
            throw new ArgumentException("TLS identity may include only unique private LAN addresses.", nameof(addresses));
    }

    internal static bool IsPrivateLanAddress(IPAddress address)
    {
        if (IPAddress.IsLoopback(address) || address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.IPv6Any) || address.IsIPv6Multicast ||
            address.IsIPv4MappedToIPv6)
            return false;

        var bytes = address.GetAddressBytes();
        if (address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
        {
            return bytes[0] == 10 ||
                (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) ||
                (bytes[0] == 192 && bytes[1] == 168);
        }

        return address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6 &&
            (bytes[0] & 0xFE) == 0xFC;
    }

    private static IReadOnlyList<IPAddress> NormalizeAddresses(IReadOnlyCollection<IPAddress> addresses) =>
        addresses.OrderBy(address => address.AddressFamily.ToString(), StringComparer.Ordinal)
            .ThenBy(address => address.ToString(), StringComparer.Ordinal)
            .ToArray();

    private static string Fingerprint(X509Certificate2 certificate) =>
        Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant();
}
