using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Owner-approved Desktop Hub identity. The shared secret is stored only inside
/// the DPAPI-protected credential blob and is never written as plaintext.
/// </summary>
public sealed record AgentEnrollmentCredential(
    string AgentId,
    string DesktopHubId,
    Uri Endpoint,
    string TlsCertificateSha256,
    string SharedSecretBase64,
    IReadOnlySet<string> Scopes,
    DateTimeOffset EnrolledAt,
    DateTimeOffset ExpiresAt)
{
    public string EnrollmentId { get; init; } = AgentId;
}

public interface IAgentCredentialStore
{
    Task SaveAsync(AgentEnrollmentCredential credential, CancellationToken cancellationToken);
    Task<AgentEnrollmentCredential?> LoadAsync(CancellationToken cancellationToken);
    Task DeleteAsync(CancellationToken cancellationToken);
}

public static class AgentEnrollmentCredentialPolicy
{
    public static void Validate(AgentEnrollmentCredential credential, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(credential);
        if (!IsIdentity(credential.AgentId))
            throw new ArgumentException("Agent ID must be a bounded ASCII identifier.", nameof(credential));
        if (!IsIdentity(credential.EnrollmentId))
            throw new ArgumentException("Enrollment ID must be a bounded ASCII identifier.", nameof(credential));
        if (!IsIdentity(credential.DesktopHubId))
            throw new ArgumentException("Desktop Hub ID must be a bounded ASCII identifier.", nameof(credential));
        if (credential.Endpoint is null || !credential.Endpoint.IsAbsoluteUri ||
            credential.Endpoint.Scheme != Uri.UriSchemeHttps ||
            string.IsNullOrWhiteSpace(credential.Endpoint.Host) ||
            !string.IsNullOrEmpty(credential.Endpoint.UserInfo) ||
            !string.IsNullOrEmpty(credential.Endpoint.Query) ||
            !string.IsNullOrEmpty(credential.Endpoint.Fragment))
            throw new ArgumentException("Agent enrollment requires a credential-free HTTPS endpoint.", nameof(credential));
        if (!IsCanonicalSha256(credential.TlsCertificateSha256))
            throw new ArgumentException(
                "Enrollment requires the Desktop Hub TLS certificate SHA-256 pin as 64 lowercase hexadecimal characters.",
                nameof(credential));

        byte[] secret;
        try { secret = Convert.FromBase64String(credential.SharedSecretBase64); }
        catch (FormatException error)
        {
            throw new ArgumentException("Enrollment secret must be valid Base64.", nameof(credential), error);
        }
        try
        {
            if (secret.Length != 32)
                throw new ArgumentException("Enrollment secret must contain exactly 256 bits.", nameof(credential));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(secret);
        }

        if (credential.Scopes is null || credential.Scopes.Count is < 1 or > 32 ||
            credential.Scopes.Any(scope => !IsScope(scope)))
            throw new ArgumentException("Enrollment requires 1–32 valid capability scopes.", nameof(credential));
        if (credential.EnrolledAt > now.AddMinutes(5) || credential.ExpiresAt <= now ||
            credential.ExpiresAt <= credential.EnrolledAt)
            throw new ArgumentException("Enrollment credential is not currently valid.", nameof(credential));
    }

    private static bool IsCanonicalSha256(string value) =>
        value is { Length: 64 } && value.All(character =>
            character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static bool IsIdentity(string value) => value is { Length: >= 1 and <= 128 } &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_');

    private static bool IsScope(string value) => value is { Length: >= 1 and <= 128 } &&
        value.All(character => character is >= '!' and <= '~');
}

/// <summary>
/// Fail-closed certificate callback for the self-signed Desktop Hub identity.
/// A pinned self-signed certificate may have chain errors, but a missing certificate,
/// endpoint-name mismatch or a different certificate is always rejected.
/// </summary>
public static class AgentTlsCertificatePinPolicy
{
    public static bool Validate(
        AgentEnrollmentCredential credential,
        X509Certificate2? certificate,
        SslPolicyErrors policyErrors)
    {
        ArgumentNullException.ThrowIfNull(credential);
        return Validate(credential.TlsCertificateSha256, certificate, policyErrors);
    }

    public static bool Validate(
        string expectedSha256,
        X509Certificate2? certificate,
        SslPolicyErrors policyErrors)
    {
        if (certificate is null ||
            (policyErrors & (SslPolicyErrors.RemoteCertificateNotAvailable |
                             SslPolicyErrors.RemoteCertificateNameMismatch)) != 0)
            return false;

        byte[] expected;
        try
        {
            expected = Convert.FromHexString(expectedSha256);
        }
        catch (Exception error) when (error is FormatException or ArgumentNullException)
        {
            return false;
        }

        var actual = SHA256.HashData(certificate.RawData);
        try
        {
            return expected.Length == 32 &&
                CryptographicOperations.FixedTimeEquals(expected, actual);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(expected);
            CryptographicOperations.ZeroMemory(actual);
        }
    }
}
