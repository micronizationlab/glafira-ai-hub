namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Minimal typed task metadata. Payload content is referenced by an authorized,
/// integrity-checked reference; credentials and arbitrary commands are not fields.
/// </summary>
public sealed record AgentTaskEnvelope(
    string TaskId,
    string ProjectId,
    string OwnerCoreId,
    long OwnerGeneration,
    string IdempotencyKey,
    string TaskType,
    string PayloadRef,
    string PayloadSha256,
    IReadOnlySet<string> RequiredCapabilities,
    string? ApprovalRef,
    string Title = "",
    string Instruction = "",
    string Executor = "",
    string ParentTaskId = "",
    string SubtaskId = "",
    long CreatedAtEpochMs = 0,
    long ExpiresAtEpochMs = 0,
    int Priority = 0,
    string LeaseId = "",
    long FencingToken = 0,
    string EffectRisk = "ReadOnly");

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
