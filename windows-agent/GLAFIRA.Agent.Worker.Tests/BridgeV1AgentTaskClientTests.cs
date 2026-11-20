using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class BridgeV1AgentTaskClientTests
{
    private const string Pin = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static readonly Uri Endpoint = new("https://192.168.10.5:8443/bridge/v1");
    private readonly DateTimeOffset _now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);

    [Fact]
    public async Task ClaimSignsOwnerProfileAndDecodesFencedLease()
    {
        Dictionary<string, string>? captured = null;
        string? operation = null;
        var handler = new StubHandler(async request =>
        {
            using var doc = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            operation = doc.RootElement.GetProperty("operation").GetString();
            captured = JsonSerializer.Deserialize<Dictionary<string, string>>(
                doc.RootElement.GetProperty("payload").GetRawText());
            return Json(new
            {
                status = "ACCEPTED",
                result = new Dictionary<string, string>
                {
                    ["available"] = "true", ["taskId"] = "task-001", ["projectId"] = "default",
                    ["parentTaskId"] = "parent-001", ["subtaskId"] = "sub-001",
                    ["title"] = "Research", ["instruction"] = "Analyze supplied material",
                    ["executor"] = "RESEARCHER", ["requiredCapabilities"] = "[\"web.research\"]",
                    ["createdAtEpochMs"] = _now.ToUnixTimeMilliseconds().ToString(),
                    ["expiresAtEpochMs"] = _now.AddMinutes(5).ToUnixTimeMilliseconds().ToString(),
                    ["priority"] = "10", ["leaseId"] = "lease-0001", ["fencingToken"] = "42"
                }
            });
        });
        var client = Client(handler);

        var lease = await client.ClaimNextAsync(Credential(),
            new[] { "web.research", "document.analysis" }, 2);

        Assert.NotNull(lease);
        Assert.Equal("task-001", lease.TaskId);
        Assert.Equal("lease-0001", lease.LeaseId);
        Assert.Equal(42, lease.FencingToken);
        Assert.Equal("DESKTOP_TASK_CLAIM", operation);
        Assert.NotNull(captured);
        Assert.Equal("[\"document.analysis\",\"web.research\"]", captured["capabilities"]);
        Assert.Equal("2", captured["maxParallelTasks"]);
    }

    [Fact]
    public async Task EmptyQueueReturnsNull()
    {
        var client = Client(new StubHandler(_ => Task.FromResult(Json(new
        {
            status = "ACCEPTED",
            result = new Dictionary<string, string> { ["available"] = "false" }
        }))));
        Assert.Null(await client.ClaimNextAsync(Credential(), new[] { "web.research" }, 1));
    }

    [Fact]
    public async Task TerminalUpdateRequiresExactServerAcknowledgement()
    {
        var resultJson = "\"done\"";
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
        var handler = new StubHandler(_ => Task.FromResult(Json(new
        {
            status = "SUCCEEDED",
            result = new Dictionary<string, string>
            {
                ["accepted"] = "true", ["taskId"] = "task-001", ["leaseId"] = "lease-0001",
                ["fencingToken"] = "42", ["state"] = "SUCCEEDED", ["resultSha256"] = digest
            }
        })));
        var client = Client(handler);
        var lease = Lease();

        var ack = await client.SendTaskUpdateAsync(Credential(), lease,
            "SUCCEEDED", 100, "completed", resultJson);

        Assert.Equal("task-001", ack.TaskId);
        Assert.Equal(digest, ack.ResultSha256);
    }

    [Fact]
    public async Task MismatchedAcknowledgementIsRejected()
    {
        var handler = new StubHandler(_ => Task.FromResult(Json(new
        {
            status = "SUCCEEDED",
            result = new Dictionary<string, string>
            {
                ["accepted"] = "true", ["taskId"] = "other-task", ["leaseId"] = "lease-0001",
                ["fencingToken"] = "42", ["state"] = "SUCCEEDED", ["resultSha256"] = new string('0', 64)
            }
        })));
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            Client(handler).SendTaskUpdateAsync(Credential(), Lease(), "SUCCEEDED", 100, "done", "\"ok\""));
    }

    [Fact]
    public async Task ClaimRequiresOwnerGrantedScopeBeforeNetwork()
    {
        var handler = new StubHandler(_ => throw new Xunit.Sdk.XunitException("Unexpected network call."));
        var credential = Credential() with { Scopes = new HashSet<string> { "desktop.task.result" } };
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            Client(handler).ClaimNextAsync(credential, new[] { "web.research" }, 1));
        Assert.Null(handler.LastRequestUri);
    }

    private AgentTaskLease Lease() => new("task-001", "default", "parent-001", "sub-001",
        "Research", "Analyze", "RESEARCHER", new HashSet<string> { "web.research" },
        _now.ToUnixTimeMilliseconds(), _now.AddMinutes(5).ToUnixTimeMilliseconds(),
        10, "lease-0001", 42);

    private AgentEnrollmentCredential Credential() => new("agent-01", "desktop-hub-01",
        Endpoint, Pin, Convert.ToBase64String(_secret),
        new HashSet<string> { "desktop.task.claim", "desktop.task.result" },
        _now.AddMinutes(-1), _now.AddDays(30));

    private BridgeV1AgentTaskClient Client(StubHandler handler) =>
        new(new FixedTimeProvider(_now), _ => handler);

    private static HttpResponseMessage Json(object payload) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json")
    };

    private sealed class StubHandler(Func<HttpRequestMessage, Task<HttpResponseMessage>> responder) : HttpMessageHandler
    {
        public Uri? LastRequestUri { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequestUri = request.RequestUri;
            return await responder(request);
        }
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
