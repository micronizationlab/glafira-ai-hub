namespace GLAFIRA.Agent.Worker;

public interface IAgentResultArtifactTransport
{
    Task<AgentTaskArtifactAck> UploadTaskArtifactAsync(
        AgentEnrollmentCredential credential,
        AgentResultArtifact artifact,
        CancellationToken cancellationToken = default);
}
