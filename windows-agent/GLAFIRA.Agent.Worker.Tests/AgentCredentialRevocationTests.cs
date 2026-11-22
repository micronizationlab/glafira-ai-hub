using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentCredentialRevocationTests
{
    [Theory]
    [InlineData("CredentialNotFound")]
    [InlineData("AGENT_REVOKED")]
    public async Task DefinitivePinnedHubRejectionDeletesLocalCredential(string errorCode)
    {
        var store = new RecordingStore();

        var deleted = await AgentCredentialRevocation.DeleteIfDefinitivelyRevokedAsync(
            errorCode, store, CancellationToken.None);

        Assert.True(deleted);
        Assert.Equal(1, store.DeleteCalls);
    }

    [Theory]
    [InlineData("AGENT_NOT_OWNER_APPROVED")]
    [InlineData("SignatureInvalid")]
    [InlineData("HEARTBEAT_REJECTED")]
    [InlineData(null)]
    public async Task AmbiguousOrTemporaryRejectionPreservesLocalCredential(string? errorCode)
    {
        var store = new RecordingStore();

        var deleted = await AgentCredentialRevocation.DeleteIfDefinitivelyRevokedAsync(
            errorCode, store, CancellationToken.None);

        Assert.False(deleted);
        Assert.Equal(0, store.DeleteCalls);
    }

    private sealed class RecordingStore : IAgentCredentialStore
    {
        public int DeleteCalls { get; private set; }
        public Task SaveAsync(AgentEnrollmentCredential credential, CancellationToken cancellationToken) =>
            Task.CompletedTask;
        public Task<AgentEnrollmentCredential?> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult<AgentEnrollmentCredential?>(null);
        public Task DeleteAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            DeleteCalls++;
            return Task.CompletedTask;
        }
    }
}
