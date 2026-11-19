namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Minimal typed task metadata. Payload content is referenced by an authorized,
/// integrity-checked reference; credentials and arbitrary commands are not fields.
/// </summary>
public sealed record AgentTaskEnvelope(
    string TaskId,
    string ProjectId,
    string IdempotencyKey,
    string TaskType,
    string PayloadRef,
    string PayloadSha256,
    IReadOnlySet<string> RequiredCapabilities,
    string? ApprovalRef);

public sealed record AgentTaskResult(
    string TaskId,
    string Outcome,
    string? ResultRef = null,
    string? ResultSha256 = null,
    string? ErrorCode = null);

/// <summary>
/// Implementations must be explicitly registered for a stable task type.
/// There is deliberately no shell/process or arbitrary-code handler interface.
/// </summary>
public interface IAgentTaskHandler
{
    string TaskType { get; }

    Task<AgentTaskResult> ExecuteAsync(
        AgentTaskEnvelope task,
        CancellationToken cancellationToken);
}
