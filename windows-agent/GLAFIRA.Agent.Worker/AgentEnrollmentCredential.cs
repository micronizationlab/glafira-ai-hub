namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Owner-approved Desktop Hub identity. The shared secret is stored only inside
/// the DPAPI-protected credential blob and is never written as plaintext.
/// </summary>
public sealed record AgentEnrollmentCredential(
    string AgentId,
    string DesktopHubId,
    Uri Endpoint,
    string SharedSecretBase64,
    IReadOnlySet<string> Scopes,
    DateTimeOffset EnrolledAt,
    DateTimeOffset ExpiresAt);

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
        if (string.IsNullOrWhiteSpace(credential.AgentId) || credential.AgentId.Length > 128)
            throw new ArgumentException("Agent ID must contain 1–128 characters.", nameof(credential));
        if (string.IsNullOrWhiteSpace(credential.DesktopHubId) || credential.DesktopHubId.Length > 128)
            throw new ArgumentException("Desktop Hub ID must contain 1–128 characters.", nameof(credential));
        if (credential.Endpoint is null || !credential.Endpoint.IsAbsoluteUri ||
            credential.Endpoint.Scheme != Uri.UriSchemeHttps ||
            string.IsNullOrWhiteSpace(credential.Endpoint.Host) ||
            !string.IsNullOrEmpty(credential.Endpoint.UserInfo) ||
            !string.IsNullOrEmpty(credential.Endpoint.Query) ||
            !string.IsNullOrEmpty(credential.Endpoint.Fragment))
            throw new ArgumentException("Agent enrollment requires a credential-free HTTPS endpoint.", nameof(credential));

        byte[] secret;
        try { secret = Convert.FromBase64String(credential.SharedSecretBase64); }
        catch (FormatException error)
        {
            throw new ArgumentException("Enrollment secret must be valid Base64.", nameof(credential), error);
        }
        if (secret.Length < 32)
            throw new ArgumentException("Enrollment secret must contain at least 256 bits.", nameof(credential));
        Array.Clear(secret);

        if (credential.Scopes is null || credential.Scopes.Count is < 1 or > 32 ||
            credential.Scopes.Any(scope => string.IsNullOrWhiteSpace(scope) || scope.Length > 128))
            throw new ArgumentException("Enrollment requires 1–32 valid capability scopes.", nameof(credential));
        if (credential.EnrolledAt > now.AddMinutes(5) || credential.ExpiresAt <= now ||
            credential.ExpiresAt <= credential.EnrolledAt)
            throw new ArgumentException("Enrollment credential is not currently valid.", nameof(credential));
    }
}
