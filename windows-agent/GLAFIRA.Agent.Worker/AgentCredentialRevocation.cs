namespace GLAFIRA.Agent.Worker;

internal static class AgentCredentialRevocation
{
    internal static bool IsDefinitive(string? errorCode) =>
        errorCode is "CredentialNotFound" or "AGENT_REVOKED";

    internal static async Task<bool> DeleteIfDefinitivelyRevokedAsync(
        string? errorCode,
        IAgentCredentialStore credentialStore,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(credentialStore);
        if (!IsDefinitive(errorCode)) return false;
        await credentialStore.DeleteAsync(cancellationToken).ConfigureAwait(false);
        return true;
    }
}
