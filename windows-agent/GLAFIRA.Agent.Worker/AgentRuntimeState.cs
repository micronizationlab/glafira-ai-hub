namespace GLAFIRA.Agent.Worker;

public enum AgentServiceStatus
{
    Starting,
    AwaitingOwnerEnrollment,
    Enrolled,
    Ready,
    Paused,
    Draining,
    Stopped,
    Faulted
}

/// <summary>
/// In-memory service status only. Credentials remain in the OS-protected credential store.
/// </summary>
public sealed class AgentRuntimeState
{
    private int _status = (int)AgentServiceStatus.Starting;

    public AgentServiceStatus Status =>
        (AgentServiceStatus)Volatile.Read(ref _status);

    public void SetStatus(AgentServiceStatus status) =>
        Interlocked.Exchange(ref _status, (int)status);
}
