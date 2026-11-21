namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Resolves only explicitly registered task handlers. Unknown task types fail closed.
/// </summary>
public sealed class AgentTaskHandlerRegistry
{
    private readonly IReadOnlyDictionary<string, IAgentTaskHandler> _handlers;

    public AgentTaskHandlerRegistry(IEnumerable<IAgentTaskHandler> handlers)
    {
        var registered = new Dictionary<string, IAgentTaskHandler>(
            StringComparer.Ordinal);

        foreach (var handler in handlers)
        {
            if (string.IsNullOrWhiteSpace(handler.TaskType))
            {
                throw new ArgumentException("Task handler type must not be empty.");
            }

            if (!registered.TryAdd(handler.TaskType, handler))
            {
                throw new ArgumentException(
                    $"Duplicate task handler registration: {handler.TaskType}");
            }
        }

        _handlers = registered;
    }

    /// <summary>Stable, sorted task-type allowlist that this Agent can actually execute.</summary>
    public IReadOnlyList<string> RegisteredTaskTypes =>
        _handlers.Keys.Order(StringComparer.Ordinal).ToArray();

    public bool TryResolve(string taskType, out IAgentTaskHandler? handler)
    {
        if (string.IsNullOrWhiteSpace(taskType))
        {
            handler = null;
            return false;
        }

        return _handlers.TryGetValue(taskType, out handler);
    }
}
