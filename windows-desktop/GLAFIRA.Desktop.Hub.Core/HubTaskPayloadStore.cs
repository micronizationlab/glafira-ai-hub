using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record HubTaskPayloadReference(string PayloadRef, string PayloadSha256);

/// <summary>
/// Content-addressed, DPAPI-protected task payload storage for the Windows Desktop Hub.
/// Payload references are SHA-256 identifiers, never arbitrary paths or URLs.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class HubTaskPayloadStore : IHubTaskPayloadResolver
{
    private const int MaxPayloadBytes = 65_536;
    private static readonly byte[] Entropy =
        Encoding.UTF8.GetBytes("GLAFIRA.Desktop.Hub.TaskPayload.v1");

    private readonly string _root;
    private readonly IHubDataProtector _protector;

    public HubTaskPayloadStore(string rootDirectory, IHubDataProtector? protector = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        _root = Path.GetFullPath(rootDirectory);
        _protector = protector ?? new WindowsDpapiHubDataProtector();
        Directory.CreateDirectory(_root);
        if ((File.GetAttributes(_root) & FileAttributes.ReparsePoint) != 0)
            throw new InvalidOperationException("Task payload root cannot be a reparse point.");
    }

    public async ValueTask<HubTaskPayloadReference> StoreAsync(
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken = default)
    {
        if (payload.Length is 0 or > MaxPayloadBytes)
            throw new ArgumentOutOfRangeException(nameof(payload), "Task payload must contain 1–65536 bytes.");

        var hashBytes = SHA256.HashData(payload.Span);
        var hash = Convert.ToHexString(hashBytes).ToLowerInvariant();
        CryptographicOperations.ZeroMemory(hashBytes);
        var path = GetPath(hash);

        if (File.Exists(path))
        {
            var existing = await ResolveAsync(hash, cancellationToken).ConfigureAwait(false);
            if (existing is null)
                throw new InvalidDataException("An existing content-addressed payload failed integrity verification.");
            try
            {
                if (!CryptographicOperations.FixedTimeEquals(existing, payload.Span))
                    throw new InvalidDataException("Content-addressed payload collision detected.");
            }
            finally
            {
                CryptographicOperations.ZeroMemory(existing);
            }
            return new HubTaskPayloadReference(hash, hash);
        }

        var encrypted = _protector.Protect(payload.Span, Entropy);
        try
        {
            await WriteAtomicallyAsync(path, encrypted, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(encrypted);
        }

        return new HubTaskPayloadReference(hash, hash);
    }

    public async ValueTask<byte[]?> ResolveAsync(
        string payloadRef,
        CancellationToken cancellationToken)
    {
        if (!IsSha256Reference(payloadRef))
            return null;

        var path = GetPath(payloadRef);
        if (!File.Exists(path) || (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            return null;

        var info = new FileInfo(path);
        if (info.Length is <= 0 or > 131_072)
            return null;

        var encrypted = await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
        try
        {
            byte[] plaintext;
            try
            {
                plaintext = _protector.Unprotect(encrypted, Entropy);
            }
            catch (CryptographicException)
            {
                return null;
            }

            if (plaintext.Length is 0 or > MaxPayloadBytes)
            {
                CryptographicOperations.ZeroMemory(plaintext);
                return null;
            }

            var actualHash = SHA256.HashData(plaintext);
            var expectedHash = Convert.FromHexString(payloadRef);
            var matches = CryptographicOperations.FixedTimeEquals(actualHash, expectedHash);
            CryptographicOperations.ZeroMemory(actualHash);
            CryptographicOperations.ZeroMemory(expectedHash);
            if (!matches)
            {
                CryptographicOperations.ZeroMemory(plaintext);
                return null;
            }

            return plaintext;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(encrypted);
        }
    }

    private string GetPath(string payloadRef) => Path.Combine(_root, payloadRef + ".dpapi");

    private static bool IsSha256Reference(string? value) =>
        value is { Length: 64 } &&
        value.All(character =>
            character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static async Task WriteAtomicallyAsync(
        string path,
        byte[] encrypted,
        CancellationToken cancellationToken)
    {
        var temporaryPath = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await using (var stream = new FileStream(
                temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                4096, FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await stream.WriteAsync(encrypted, cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, path, overwrite: false);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }
}
