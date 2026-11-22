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

            var artifact = await store.ReadAsync(pending[0], cancellationToken).ConfigureAwait(false)
                ?? throw new InvalidDataException("AGENT_ARTIFACT_OUTBOX_RECORD_MISSING");
            try
            {
                var acknowledgement = await transport.UploadTaskArtifactAsync(
                    credential, artifact, cancellationToken).ConfigureAwait(false);
                var metadata = artifact.Metadata;
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
                return true;
            }
            finally
            {
                CryptographicOperations.ZeroMemory(artifact.Payload);
            }
        }
        finally { _gate.Release(); }
    }
}
