using GLAFIRA.Agent.Worker;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentTaskHandlerRegistryTests
{
    [Fact]
    public void UnknownTaskType_IsRejected()
    {
        var registry = new AgentTaskHandlerRegistry([]);

        Assert.False(registry.TryResolve("process.shell", out var handler));
        Assert.Null(handler);
    }

    [Fact]
    public void ExplicitlyRegisteredTaskType_CanBeResolved()
    {
        var expected = new TestHandler("research.search");
        var registry = new AgentTaskHandlerRegistry([expected]);

        Assert.True(registry.TryResolve("research.search", out var actual));
        Assert.Same(expected, actual);
    }

    [Fact]
    public void DuplicateTaskType_Throws()
    {
        var handlers = new IAgentTaskHandler[]
        {
            new TestHandler("research.search"),
            new TestHandler("research.search")
        };

        Assert.Throws<ArgumentException>(() => new AgentTaskHandlerRegistry(handlers));
    }

    [Fact]
    public void BlankTaskType_IsRejected()
    {
        var registry = new AgentTaskHandlerRegistry([new TestHandler("research.search")]);

        Assert.False(registry.TryResolve(" ", out var handler));
        Assert.Null(handler);
    }

    private sealed class TestHandler(string taskType) : IAgentTaskHandler
    {
        public string TaskType { get; } = taskType;

        public Task<AgentTaskResult> ExecuteAsync(
            AgentTaskEnvelope task,
            CancellationToken cancellationToken) =>
            Task.FromResult(new AgentTaskResult(task.TaskId, "SUCCEEDED"));
    }
}
