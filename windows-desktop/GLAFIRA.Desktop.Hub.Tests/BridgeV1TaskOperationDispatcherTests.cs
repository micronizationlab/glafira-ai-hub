using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Desktop.Hub.Core;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class BridgeV1TaskOperationDispatcherTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-bridge-dispatch-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly DesktopHubStore _store;
    private readonly byte[] _payload;

    public BridgeV1TaskOperationDispatcherTests()
    {
        _store = new DesktopHubStore(_databasePath);
        _payload = JsonSerializer.SerializeToUtf8Bytes(new
        {
            parentTaskId = "parent-0001",
            subtaskId = "subtask-0001",
            title = "Исследование",
            instruction = "Подготовить краткий проверяемый обзор.",
            executor = "RESEARCHER",
            requiredCapabilities = new[] { "web.research" },
            createdAtEpochMs = DateTimeOffset.UtcNow.AddSeconds(-1).ToUnixTimeMilliseconds(),
            expiresAtEpochMs = DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeMilliseconds(),
            priority = 5
        });
    }

    [Fact]
    public async Task ClaimReturnsOnlyOwnerRegisteredCapabilitiesAndServerIssuedFence()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();

        var dispatcher = CreateDispatcher(_payload);
        var response = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        Assert.Equal("ACCEPTED", response.Status);
        Assert.Equal("true", response.Result["available"]);
        Assert.Equal("task-0001", response.Result["taskId"]);
        Assert.Equal("RESEARCHER", response.Result["executor"]);
        Assert.Equal("web.research", Assert.Single(JsonSerializer.Deserialize<string[]>(response.Result["requiredCapabilities"])!));
        Assert.False(string.IsNullOrWhiteSpace(response.Result["leaseId"]));
        Assert.True(long.Parse(response.Result["fencingToken"]) > 0);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("task-0001"));
    }

    [Fact]
    public async Task DeviceCannotEscalateCapabilitiesOrConcurrencyInClaimPayload()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();

        var response = await CreateDispatcher(_payload).DispatchAsync(
            ClaimRequest(["shell.execute"], 2), now.AddSeconds(1));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("AGENT_CAPABILITY_MISMATCH", response.ErrorCode);
        Assert.Equal(HubTaskState.Queued, _store.GetTaskState("task-0001"));
    }

    [Fact]
    public async Task CurrentFencedResultIsStoredEncryptedAndDuplicateDeliveryIsIdempotent()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));
        var leaseId = claim.Result["leaseId"];
        var fencingToken = long.Parse(claim.Result["fencingToken"]);

        var completed = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", leaseId, fencingToken, "private result payload"), now.AddSeconds(2));
        var duplicate = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", leaseId, fencingToken, "private result payload"), now.AddSeconds(3));

        Assert.Equal("SUCCEEDED", completed.Status);
        Assert.Equal("SUCCEEDED", duplicate.Status);
        Assert.Equal(HubTaskState.Succeeded, _store.GetTaskState("task-0001"));
        Assert.Equal("private result payload", _store.GetTaskResult("task-0001")!.Result);

        var databaseFiles = Directory.GetFiles(Path.GetDirectoryName(_databasePath)!, Path.GetFileName(_databasePath) + "*");
        Assert.DoesNotContain(databaseFiles, file =>
            Encoding.UTF8.GetString(File.ReadAllBytes(file)).Contains("private result payload", StringComparison.Ordinal));
    }

    [Fact]
    public async Task StaleFencingTokenCannotCompleteCurrentLease()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var dispatcher = CreateDispatcher(_payload);
        var claim = await dispatcher.DispatchAsync(ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        var response = await dispatcher.DispatchAsync(
            ResultRequest("SUCCEEDED", claim.Result["leaseId"], long.Parse(claim.Result["fencingToken"]) + 1, "stale"),
            now.AddSeconds(2));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("LEASE_FENCING_REJECTED", response.ErrorCode);
        Assert.Equal(HubTaskState.Leased, _store.GetTaskState("task-0001"));
        Assert.Null(_store.GetTaskResult("task-0001"));
    }

    [Fact]
    public async Task PayloadHashMismatchFailsClosedAndQuarantinesClaimedTask()
    {
        var now = DateTimeOffset.UtcNow;
        RegisterAgent(now, ["web.research"], 1);
        EnqueueTask();
        var altered = Encoding.UTF8.GetBytes("{\"instruction\":\"tampered\"}");

        var response = await CreateDispatcher(altered).DispatchAsync(
            ClaimRequest(["web.research"], 1), now.AddSeconds(1));

        Assert.Equal("FAILED", response.Status);
        Assert.Equal("TASK_PAYLOAD_HASH_MISMATCH", response.ErrorCode);
        Assert.Equal(HubTaskState.Failed, _store.GetTaskState("task-0001"));
        Assert.Equal("TASK_PAYLOAD_HASH_MISMATCH", _store.GetTaskResult("task-0001")!.ErrorCode);
    }

    private BridgeV1TaskOperationDispatcher CreateDispatcher(byte[] payload) =>
        new(_store, new FakePayloadResolver(payload));

    private void RegisterAgent(DateTimeOffset now, IReadOnlyCollection<string> capabilities, int parallel) =>
        _store.RegisterAgent(new HubAgentRegistration(
            "agent-0001", "device-0001", capabilities, parallel, true), now);

    private void EnqueueTask()
    {
        _store.Enqueue(new HubTaskRequest(
            "task-0001", "project-0001", "idem-task-01", "web.research",
            "payload://task-0001", Convert.ToHexString(SHA256.HashData(_payload)).ToLowerInvariant(),
            ["web.research"], [], HubTaskEffectRisk.ReadOnly, null, 5), DateTimeOffset.UtcNow);
    }

    private static BridgeV1SignedRequest ClaimRequest(IReadOnlyCollection<string> capabilities, int parallel) =>
        Request(BridgeV1Operation.DESKTOP_TASK_CLAIM, new Dictionary<string, string>
        {
            ["capabilities"] = JsonSerializer.Serialize(capabilities),
            ["maxParallelTasks"] = parallel.ToString()
        });

    private static BridgeV1SignedRequest ResultRequest(
        string state, string leaseId, long fencingToken, string result) =>
        Request(BridgeV1Operation.DESKTOP_TASK_RESULT, new Dictionary<string, string>
        {
            ["taskId"] = "task-0001",
            ["leaseId"] = leaseId,
            ["fencingToken"] = fencingToken.ToString(),
            ["state"] = state,
            ["progressPercent"] = state == "SUCCEEDED" ? "100" : "0",
            ["progressMessage"] = "completed",
            ["result"] = result,
            ["errorCode"] = "none"
        });

    private static BridgeV1SignedRequest Request(
        BridgeV1Operation operation, IReadOnlyDictionary<string, string> payload) =>
        new(1, "request-0001", "idem-0001", "device-0001", "project-0001",
            operation, 1_730_000_000_000, 1_730_000_060_000, payload, new string('0', 64));

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var file in Directory.GetFiles(
            Path.GetDirectoryName(_databasePath)!, Path.GetFileName(_databasePath) + "*"))
            File.Delete(file);
        CryptographicOperations.ZeroMemory(_payload);
    }

    private sealed class FakePayloadResolver(byte[] payload) : IHubTaskPayloadResolver
    {
        public ValueTask<byte[]?> ResolveAsync(string payloadRef, CancellationToken cancellationToken) =>
            ValueTask.FromResult<byte[]?>(payload.ToArray());
    }
}
