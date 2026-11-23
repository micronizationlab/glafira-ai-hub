namespace GLAFIRA.Agent.Worker;

/// <summary>Bounded polling schedule: fast after work, slower while idle, capped higher after failures.</summary>
public static class AgentPollingBackoff
{
    public static TimeSpan MinimumDelay { get; } = TimeSpan.FromSeconds(5);
    public static TimeSpan MaximumIdleDelay { get; } = TimeSpan.FromSeconds(30);
    public static TimeSpan MaximumFailureDelay { get; } = TimeSpan.FromSeconds(60);

    public static TimeSpan NextDelay(TimeSpan current, bool worked, bool failed)
    {
        if (worked) return MinimumDelay;
        var maximum = failed ? MaximumFailureDelay : MaximumIdleDelay;
        var boundedCurrent = Math.Max(MinimumDelay.TotalMilliseconds, current.TotalMilliseconds);
        return TimeSpan.FromMilliseconds(Math.Min(maximum.TotalMilliseconds, boundedCurrent * 2));
    }
}
