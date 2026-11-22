using System.Security.Cryptography;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Drains one validated, durable artifact at a time. The store file is the outbox record:
/// it remains intact across process restarts and every transport or acknowledgement failure.
/// </summary>
public sealed class AgentResultArtifactOutbox(
    IAgentResultArtifactStore store,
    IAgentResultArtifactTransport transport)
{
    private readonly SemaphoreSlim _gate = new(1, 1);

    public async Task<bool> FlushOneAsync(
        AgentEnrollmentCredential credential,
        CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var pending = await store.ListPendingArtifactIdsAsync(cancellationToken).ConfigureAwait(false);
            if (pending.Count == 0) return false;
            await UploadAndDeleteAsync(
                credential, pending[0], expectedSha256: null, cancellationToken).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    /// <summary>
    /// Uploads the exact artifact referenced by a durable terminal result before that result
    /// can retire the Hub lease. A missing local record means an earlier exact ACK already
    /// removed it; the Hub independently verifies the reference before accepting completion.
    /// </summary>
    public async Task<bool> FlushRequiredAsync(
        AgentEnrollmentCredential credential,
        string artifactId,
        string expectedSha256,
        CancellationToken cancellationToken)
    {
        AgentResultArtifactPolicy.ValidateArtifactId(artifactId);
        if (expectedSha256.Length != 64 ||
            expectedSha256.Any(character => character is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new InvalidDataException("AGENT_ARTIFACT_RESULT_DIGEST_INVALID");

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var artifact = await store.ReadAsync(artifactId, cancellationToken).ConfigureAwait(false);
            if (artifact is null) return false;
            await UploadAndDeleteAsync(
                credential, artifactId, expectedSha256, cancellationToken, artifact).ConfigureAwait(false);
            return true;
        }
        finally { _gate.Release(); }
    }

    private async Task UploadAndDeleteAsync(
        AgentEnrollmentCredential credential,
        string artifactId,
        string? expectedSha256,
        CancellationToken cancellationToken,
        AgentResultArtifact? loaded = null)
    {
        var artifact = loaded ?? await store.ReadAsync(artifactId, cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidDataException("AGENT_ARTIFACT_OUTBOX_RECORD_MISSING");
        try
        {
            var metadata = artifact.Metadata;
            if (expectedSha256 is not null &&
                !StringComparer.Ordinal.Equals(metadata.Sha256, expectedSha256))
                throw new InvalidDataException("AGENT_ARTIFACT_RESULT_DIGEST_MISMATCH");

            var acknowledgement = await transport.UploadTaskArtifactAsync(
                credential, artifact, cancellationToken).ConfigureAwait(false);
            if (acknowledgement.ArtifactId != metadata.ArtifactId ||
                acknowledgement.TaskId != metadata.TaskId ||
                acknowledgement.OwnerCoreId != metadata.OwnerCoreId ||
                acknowledgement.OwnerGeneration != metadata.OwnerGeneration ||
                acknowledgement.LeaseId != metadata.LeaseId ||
                acknowledgement.FencingToken != metadata.FencingToken ||
                acknowledgement.SchemaId != metadata.SchemaId ||
                acknowledgement.SchemaVersion != metadata.SchemaVersion ||
                acknowledgement.ByteLength != metadata.ByteLength ||
                acknowledgement.Sha256 != metadata.Sha256)
                throw new InvalidDataException("TASK_ARTIFACT_ACK_IDENTITY_MISMATCH");

            await store.DeleteAsync(metadata.ArtifactId, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(artifact.Payload);
        }
    }
}
