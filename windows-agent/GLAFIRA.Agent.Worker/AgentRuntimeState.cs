namespace GLAFIRA.Agent.Worker;

public enum AgentServiceStatus
{
    Starting,
    AwaitingOwnerEnrollment,
    Ready,
    Paused,
    Draining,
    Stopped,
    Faulted
}

/// <summary>
/// In-memory service status only. Durable identity and enrollment arrive in a later
/// security-reviewed increment; this class stores no credentials.
/// </summary>
public sealed class AgentRuntimeState
{
    private int _status = (int)AgentServiceStatus.Starting;

    public AgentServiceStatus Status =>
        (AgentServiceStatus)Volatile.Read(ref _status);

    public void SetStatus(AgentServiceStatus status) =>
        Interlocked.Exchange(ref _status, (int)status);
}
