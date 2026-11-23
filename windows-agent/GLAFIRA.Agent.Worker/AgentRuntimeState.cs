namespace GLAFIRA.Agent.Worker;

public enum AgentServiceStatus
{
    Starting,
    AwaitingOwnerEnrollment,
    Enrolled,
    AwaitingOwnerApproval,
    Connected,
    Ready,
    Paused,
    Draining,
    Stopped,
    Faulted
}

/// <summary>
/// In-memory service status and the latest owner-approved execution profile.
/// Credentials remain exclusively in the OS-protected credential store.
/// </summary>
public sealed class AgentRuntimeState
{
    private int _status = (int)AgentServiceStatus.Starting;
    private string[] _capabilities = [];
    private int _maxParallelTasks;

    public AgentServiceStatus Status =>
        (AgentServiceStatus)Volatile.Read(ref _status);

    public IReadOnlyList<string> Capabilities => Volatile.Read(ref _capabilities);
    public int MaxParallelTasks => Volatile.Read(ref _maxParallelTasks);

    public void SetStatus(AgentServiceStatus status) =>
        Interlocked.Exchange(ref _status, (int)status);

    public void SetOwnerProfile(IEnumerable<string> capabilities, int maxParallelTasks)
    {
        ArgumentNullException.ThrowIfNull(capabilities);
        if (maxParallelTasks is < 1 or > 16)
            throw new ArgumentOutOfRangeException(nameof(maxParallelTasks));
        var copy = capabilities.Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal).ToArray();
        if (copy.Length is < 1 or > 16)
            throw new ArgumentException("Owner profile must contain 1–16 capabilities.", nameof(capabilities));
        Volatile.Write(ref _capabilities, copy);
        Volatile.Write(ref _maxParallelTasks, maxParallelTasks);
    }

    public void ClearOwnerProfile()
    {
        Volatile.Write(ref _capabilities, []);
        Volatile.Write(ref _maxParallelTasks, 0);
    }
}
