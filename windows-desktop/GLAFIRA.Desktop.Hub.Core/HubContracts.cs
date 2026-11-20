namespace GLAFIRA.Desktop.Hub.Core;

public enum HubTaskEffectRisk
{
    ReadOnly = 0,
    LocalWrite = 1,
    ExternalSideEffect = 2
}

public enum HubTaskState
{
    Queued,
    Leased,
    Succeeded,
    Failed,
    OutcomeUnknown
}

public sealed record HubAgentRegistration(
    string AgentId,
    string DeviceId,
    IReadOnlyCollection<string> Capabilities,
    int MaxParallelTasks,
    bool OwnerApproved);

public sealed record HubTaskRequest(
    string TaskId,
    string ProjectId,
    string IdempotencyKey,
    string TaskType,
    string PayloadRef,
    string PayloadSha256,
    IReadOnlyCollection<string> RequiredCapabilities,
    IReadOnlyCollection<string> Dependencies,
    HubTaskEffectRisk EffectRisk,
    string? ApprovalRef,
    int Priority = 0);

public sealed record HubTaskLease(
    string TaskId,
    string ProjectId,
    string AgentId,
    string LeaseId,
    long FencingToken,
    DateTimeOffset ExpiresAt,
    string TaskType,
    string PayloadRef,
    string PayloadSha256,
    HubTaskEffectRisk EffectRisk,
    string? ApprovalRef);

public sealed record HubTaskRecovery(
    string TaskId,
    HubTaskState State,
    string Reason);

public sealed record HubAgentSnapshot(
    string AgentId, string DeviceId, IReadOnlyList<string> Capabilities,
    int MaxParallelTasks, bool OwnerApproved, DateTimeOffset LastHeartbeat, bool IsOnline);

public sealed record HubTaskSnapshot(
    string TaskId, string ProjectId, string TaskType, HubTaskState State,
    int Priority, HubTaskEffectRisk EffectRisk, int Attempts,
    DateTimeOffset CreatedAt, string? ApprovalRef);

public sealed record HubTaskEventSnapshot(
    long EventId, string TaskId, HubTaskState State, DateTimeOffset OccurredAt,
    string? AgentId, string? LeaseId, long? FencingToken, string EventCode,
    int? ProgressPercent = null, string? ProgressMessage = null);
