using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.Json;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Persists enrollment as a Windows DPAPI LocalMachine-protected blob under
/// ProgramData. The directory and file ACL admit only LocalSystem and local
/// Administrators; the Agent Windows service runs as LocalSystem. This allows
/// an elevated pairing wizard and the service to share credentials without
/// exposing plaintext or depending on the interactive user's DPAPI profile.
/// </summary>
public sealed class DpapiAgentCredentialStore : IAgentCredentialStore
{
    private static readonly byte[] Entropy =
        "GLAFIRA.Agent.Enrollment.v1"u8.ToArray();

    private sealed record StoredCredential(
        string AgentId,
        string DesktopHubId,
        Uri Endpoint,
        string TlsCertificateSha256,
        string SharedSecretBase64,
        string[] Scopes,
        DateTimeOffset EnrolledAt,
        DateTimeOffset ExpiresAt);

    private readonly string _credentialPath;
    private readonly TimeProvider _timeProvider;

    public DpapiAgentCredentialStore(string? credentialPath = null, TimeProvider? timeProvider = null)
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        if (string.IsNullOrWhiteSpace(root))
            throw new InvalidOperationException("The machine-wide ProgramData directory is not available.");
        _credentialPath = Path.GetFullPath(credentialPath ??
            Path.Combine(root, "GLAFIRA", "Agent", "enrollment.dpapi"));
        _timeProvider = timeProvider ?? TimeProvider.System;
    }

    public async Task SaveAsync(
        AgentEnrollmentCredential credential,
        CancellationToken cancellationToken)
    {
        AgentEnrollmentCredentialPolicy.Validate(credential, _timeProvider.GetUtcNow());
        var stored = new StoredCredential(
            credential.AgentId,
            credential.DesktopHubId,
            credential.Endpoint,
            credential.TlsCertificateSha256,
            credential.SharedSecretBase64,
            credential.Scopes.Order(StringComparer.Ordinal).ToArray(),
            credential.EnrolledAt,
            credential.ExpiresAt);
        var clear = JsonSerializer.SerializeToUtf8Bytes(stored);
        byte[] protectedBytes;
        try
        {
            protectedBytes = ProtectedData.Protect(clear, Entropy, DataProtectionScope.LocalMachine);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
        }

        var directory = Path.GetDirectoryName(_credentialPath)
            ?? throw new InvalidOperationException("Credential path has no parent directory.");
        Directory.CreateDirectory(directory);
        RestrictDirectoryAcl(directory);
        var temporaryPath = _credentialPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await File.WriteAllBytesAsync(temporaryPath, protectedBytes, cancellationToken);
            RestrictFileAcl(temporaryPath);
            cancellationToken.ThrowIfCancellationRequested();
            File.Move(temporaryPath, _credentialPath, overwrite: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    public async Task<AgentEnrollmentCredential?> LoadAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(_credentialPath))
            return null;

        var protectedBytes = await File.ReadAllBytesAsync(_credentialPath, cancellationToken);
        byte[] clear;
        try
        {
            clear = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.LocalMachine);
        }
        catch (CryptographicException error)
        {
            throw new InvalidDataException(
                "Agent enrollment credentials cannot be decrypted with this Windows machine identity.", error);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
        }

        try
        {
            var stored = JsonSerializer.Deserialize<StoredCredential>(clear)
                ?? throw new InvalidDataException("Agent enrollment credential file is empty.");
            var credential = new AgentEnrollmentCredential(
                stored.AgentId,
                stored.DesktopHubId,
                stored.Endpoint,
                stored.TlsCertificateSha256,
                stored.SharedSecretBase64,
                new HashSet<string>(stored.Scopes, StringComparer.Ordinal),
                stored.EnrolledAt,
                stored.ExpiresAt);
            AgentEnrollmentCredentialPolicy.Validate(credential, _timeProvider.GetUtcNow());
            return credential;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
        }
    }

    public Task DeleteAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (File.Exists(_credentialPath))
            File.Delete(_credentialPath);
        return Task.CompletedTask;
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
