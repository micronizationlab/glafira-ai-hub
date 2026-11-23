namespace GLAFIRA.Agent.Worker;

public interface IAgentTaskTransport
{
    Task<AgentTaskLease?> ClaimNextAsync(
        AgentEnrollmentCredential credential,
        IReadOnlyCollection<string> ownerApprovedCapabilities,
        int maxParallelTasks,
        IReadOnlyCollection<string> supportedTaskTypes,
        CancellationToken cancellationToken = default);

    Task<AgentTaskUpdateAck> SendTaskUpdateAsync(
        AgentEnrollmentCredential credential,
        AgentTaskLease lease,
        string state,
        int progressPercent,
        string progressMessage,
        string resultJson = "null",
        string errorCode = "none",
        CancellationToken cancellationToken = default);
}
