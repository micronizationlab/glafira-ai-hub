using System.Buffers.Binary;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace GLAFIRA.Agent.Worker;

public sealed record AgentResultArtifactMetadata(
    int ContractVersion,
    string ArtifactId,
    string TaskId,
    string OwnerCoreId,
    long OwnerGeneration,
    string LeaseId,
    long FencingToken,
    string SchemaId,
    int SchemaVersion,
    string ContentType,
    int ByteLength,
    string Sha256,
    string RedactionStatus,
    long CreatedAtEpochMs);

public sealed record AgentResultArtifact(
    AgentResultArtifactMetadata Metadata,
    byte[] Payload);

public interface IAgentResultArtifactStore
{
    Task WriteAsync(
        AgentResultArtifactMetadata metadata,
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken);

    Task<AgentResultArtifact?> ReadAsync(
        string artifactId,
        CancellationToken cancellationToken);

    Task<bool> DeleteAsync(
        string artifactId,
        CancellationToken cancellationToken);
}

/// <summary>
/// Central allowlist and validation boundary for Agent result bytes. The first supported
/// schema is deliberately restricted to a small redacted JSON diagnostics snapshot.
/// </summary>
public static partial class AgentResultArtifactPolicy
{
    public const int CurrentContractVersion = 1;
    public const int MaximumArtifactBytes = 32 * 1024;
    public const string Redacted = "REDACTED";
    public const string DiagnosticsSchemaId = "glafira.agent.diagnostics.snapshot";
    public const int DiagnosticsSchemaVersion = 1;
    public const string JsonContentType = "application/json";

    private static readonly UTF8Encoding StrictUtf8 = new(
        encoderShouldEmitUTF8Identifier: false,
        throwOnInvalidBytes: true);

    [GeneratedRegex("^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$", RegexOptions.CultureInvariant)]
    private static partial Regex IdentifierPattern();

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256Pattern();

    public static void Validate(
        AgentResultArtifactMetadata metadata,
        ReadOnlySpan<byte> payload)
    {
        ArgumentNullException.ThrowIfNull(metadata);
        if (metadata.ContractVersion != CurrentContractVersion)
            throw new InvalidDataException("AGENT_ARTIFACT_CONTRACT_UNSUPPORTED");
        ValidateIdentifier(metadata.ArtifactId, "AGENT_ARTIFACT_ID_INVALID");
        ValidateIdentifier(metadata.TaskId, "AGENT_ARTIFACT_TASK_ID_INVALID");
        ValidateIdentifier(metadata.OwnerCoreId, "AGENT_ARTIFACT_OWNER_INVALID");
        ValidateIdentifier(metadata.LeaseId, "AGENT_ARTIFACT_LEASE_INVALID");
        if (metadata.OwnerGeneration <= 0 || metadata.FencingToken <= 0)
            throw new InvalidDataException("AGENT_ARTIFACT_AUTHORITY_INVALID");
        if (metadata.SchemaId != DiagnosticsSchemaId ||
            metadata.SchemaVersion != DiagnosticsSchemaVersion ||
            metadata.ContentType != JsonContentType)
            throw new InvalidDataException("AGENT_ARTIFACT_SCHEMA_UNSUPPORTED");
        if (metadata.RedactionStatus != Redacted)
            throw new InvalidDataException("AGENT_ARTIFACT_NOT_REDACTED");
        if (metadata.CreatedAtEpochMs <= 0)
            throw new InvalidDataException("AGENT_ARTIFACT_TIMESTAMP_INVALID");
        if (payload.Length == 0 || payload.Length > MaximumArtifactBytes ||
            metadata.ByteLength != payload.Length)
            throw new InvalidDataException("AGENT_ARTIFACT_SIZE_INVALID");
        if (!Sha256Pattern().IsMatch(metadata.Sha256))
            throw new InvalidDataException("AGENT_ARTIFACT_DIGEST_INVALID");

        var actualDigest = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();
        if (!CryptographicOperations.FixedTimeEquals(
                Encoding.ASCII.GetBytes(actualDigest),
                Encoding.ASCII.GetBytes(metadata.Sha256)))
            throw new InvalidDataException("AGENT_ARTIFACT_DIGEST_MISMATCH");

        try
        {
            var json = StrictUtf8.GetString(payload);
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
                MaxDepth = 8
            });
            if (document.RootElement.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException("AGENT_ARTIFACT_JSON_OBJECT_REQUIRED");
        }
        catch (DecoderFallbackException error)
        {
            throw new InvalidDataException("AGENT_ARTIFACT_ENCODING_INVALID", error);
        }
        catch (JsonException error)
        {
            throw new InvalidDataException("AGENT_ARTIFACT_JSON_INVALID", error);
        }
    }

    public static string ComputeSha256(ReadOnlySpan<byte> payload) =>
        Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();

    public static void ValidateArtifactId(string artifactId) =>
        ValidateIdentifier(artifactId, "AGENT_ARTIFACT_ID_INVALID");

    private static void ValidateIdentifier(string value, string errorCode)
    {
        if (string.IsNullOrWhiteSpace(value) || !IdentifierPattern().IsMatch(value))
            throw new InvalidDataException(errorCode);
    }
}

/// <summary>
/// Atomic app-owned artifact store. Metadata and bytes share one file so a crash cannot
/// expose a partially paired payload. The final name is derived only from a validated ID.
/// </summary>
public sealed class FileAgentResultArtifactStore : IAgentResultArtifactStore
{
    private const int HeaderPrefixBytes = sizeof(int);
    private const int MaximumMetadataBytes = 8 * 1024;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };

    private readonly string _directory;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public FileAgentResultArtifactStore(string? directory = null)
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        if (directory is null && string.IsNullOrWhiteSpace(root))
            throw new InvalidOperationException("The machine-wide ProgramData directory is not available.");
        _directory = Path.GetFullPath(directory ??
            Path.Combine(root, "GLAFIRA", "Agent", "result-artifacts"));
    }

    public async Task WriteAsync(
        AgentResultArtifactMetadata metadata,
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken)
    {
        AgentResultArtifactPolicy.Validate(metadata, payload.Span);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureRestrictedDirectory();
            var finalPath = GetArtifactPath(metadata.ArtifactId);
            if (File.Exists(finalPath))
            {
                var existing = await ReadFileAsync(finalPath, cancellationToken).ConfigureAwait(false);
                if (existing.Metadata != metadata || !existing.Payload.AsSpan().SequenceEqual(payload.Span))
                    throw new InvalidDataException("AGENT_ARTIFACT_ID_CONFLICT");
                return;
            }

            var metadataBytes = JsonSerializer.SerializeToUtf8Bytes(metadata, JsonOptions);
            if (metadataBytes.Length == 0 || metadataBytes.Length > MaximumMetadataBytes)
                throw new InvalidDataException("AGENT_ARTIFACT_METADATA_SIZE_INVALID");
            var temporaryPath = Path.Combine(
                _directory,
                "." + metadata.ArtifactId + "." + Guid.NewGuid().ToString("N") + ".tmp");
            try
            {
                await using (var stream = new FileStream(
                    temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                    bufferSize: 16 * 1024, FileOptions.Asynchronous | FileOptions.WriteThrough))
                {
                    var prefix = new byte[HeaderPrefixBytes];
                    BinaryPrimitives.WriteInt32BigEndian(prefix, metadataBytes.Length);
                    await stream.WriteAsync(prefix, cancellationToken).ConfigureAwait(false);
                    await stream.WriteAsync(metadataBytes, cancellationToken).ConfigureAwait(false);
                    await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
                    await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                    stream.Flush(flushToDisk: true);
                }
                RestrictFile(temporaryPath);
                cancellationToken.ThrowIfCancellationRequested();
                File.Move(temporaryPath, finalPath, overwrite: false);
            }
            catch (IOException) when (File.Exists(finalPath))
            {
                var existing = await ReadFileAsync(finalPath, cancellationToken).ConfigureAwait(false);
                if (existing.Metadata != metadata || !existing.Payload.AsSpan().SequenceEqual(payload.Span))
                    throw new InvalidDataException("AGENT_ARTIFACT_ID_CONFLICT");
            }
            finally
            {
                CryptographicOperations.ZeroMemory(metadataBytes);
                if (File.Exists(temporaryPath))
                    File.Delete(temporaryPath);
            }
        }
        finally { _gate.Release(); }
    }

    public async Task<AgentResultArtifact?> ReadAsync(
        string artifactId,
        CancellationToken cancellationToken)
    {
        AgentResultArtifactPolicy.ValidateArtifactId(artifactId);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var path = GetArtifactPath(artifactId);
            return File.Exists(path)
                ? await ReadFileAsync(path, cancellationToken).ConfigureAwait(false)
                : null;
        }
        finally { _gate.Release(); }
    }

    public async Task<bool> DeleteAsync(
        string artifactId,
        CancellationToken cancellationToken)
    {
        AgentResultArtifactPolicy.ValidateArtifactId(artifactId);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            var path = GetArtifactPath(artifactId);
            if (!File.Exists(path)) return false;
            File.Delete(path);
            return true;
        }
        finally { _gate.Release(); }
    }

    private async Task<AgentResultArtifact> ReadFileAsync(
        string path,
        CancellationToken cancellationToken)
    {
        var info = new FileInfo(path);
        var maximumFileBytes = HeaderPrefixBytes + MaximumMetadataBytes +
            AgentResultArtifactPolicy.MaximumArtifactBytes;
        if (info.Length <= HeaderPrefixBytes || info.Length > maximumFileBytes)
            throw new InvalidDataException("AGENT_ARTIFACT_FILE_SIZE_INVALID");

        await using var stream = new FileStream(
            path, FileMode.Open, FileAccess.Read, FileShare.Read,
            bufferSize: 16 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        var prefix = new byte[HeaderPrefixBytes];
        await stream.ReadExactlyAsync(prefix, cancellationToken).ConfigureAwait(false);
        var metadataLength = BinaryPrimitives.ReadInt32BigEndian(prefix);
        if (metadataLength <= 0 || metadataLength > MaximumMetadataBytes ||
            info.Length <= HeaderPrefixBytes + metadataLength)
            throw new InvalidDataException("AGENT_ARTIFACT_METADATA_SIZE_INVALID");

        var metadataBytes = new byte[metadataLength];
        await stream.ReadExactlyAsync(metadataBytes, cancellationToken).ConfigureAwait(false);
        AgentResultArtifactMetadata metadata;
        try
        {
            metadata = JsonSerializer.Deserialize<AgentResultArtifactMetadata>(metadataBytes, JsonOptions)
                ?? throw new InvalidDataException("AGENT_ARTIFACT_METADATA_INVALID");
        }
        catch (JsonException error)
        {
            throw new InvalidDataException("AGENT_ARTIFACT_METADATA_INVALID", error);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(metadataBytes);
        }

        if (GetArtifactPath(metadata.ArtifactId) != path)
            throw new InvalidDataException("AGENT_ARTIFACT_FILE_ID_MISMATCH");
        var payloadLength = checked((int)(info.Length - HeaderPrefixBytes - metadataLength));
        var payload = new byte[payloadLength];
        await stream.ReadExactlyAsync(payload, cancellationToken).ConfigureAwait(false);
        AgentResultArtifactPolicy.Validate(metadata, payload);
        return new AgentResultArtifact(metadata, payload);
    }

    private string GetArtifactPath(string artifactId)
    {
        AgentResultArtifactPolicy.ValidateArtifactId(artifactId);
        return Path.Combine(_directory, artifactId + ".artifact");
    }

    private void EnsureRestrictedDirectory()
    {
        Directory.CreateDirectory(_directory);
        if (OperatingSystem.IsWindows())
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
            new DirectoryInfo(_directory).SetAccessControl(security);
        }
        else
        {
            File.SetUnixFileMode(_directory, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
    }

    private static void RestrictFile(string path)
    {
        if (OperatingSystem.IsWindows())
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
        else
        {
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }
    }
}
