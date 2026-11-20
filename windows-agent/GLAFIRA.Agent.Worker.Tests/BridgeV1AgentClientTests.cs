using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class BridgeV1AgentClientTests
{
    private const string Pin = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static readonly Uri Endpoint = new("https://192.168.10.5:8443/bridge/v1");
    private readonly DateTimeOffset _now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);

    [Fact]
    public async Task HeartbeatSignsCanonicalBridgeEnvelopeAndReturnsOwnerProfile()
    {
        SignedRequest? captured = null;
        var handler = new StubHandler(async request =>
        {
            var json = await request.Content!.ReadAsStringAsync();
            captured = JsonSerializer.Deserialize<SignedRequest>(json, JsonOptions);
            return JsonResponse(new
            {
                status = "ACCEPTED",
                errorCode = (string?)null,
                result = new Dictionary<string, string>
                {
                    ["agentId"] = "agent-01",
                    ["ownerApproved"] = "true",
                    ["capabilities"] = "[\"document.analysis\",\"web.research\"]",
                    ["maxParallelTasks"] = "3"
                }
            });
        });
        var client = CreateClient(handler);

        var heartbeat = await client.SendHeartbeatAsync(Credential());

        Assert.True(heartbeat.Accepted);
        Assert.Equal("agent-01", heartbeat.AgentId);
        Assert.Equal(["document.analysis", "web.research"], heartbeat.Capabilities.Order(StringComparer.Ordinal));
        Assert.Equal(3, heartbeat.MaxParallelTasks);
        Assert.NotNull(captured);
        Assert.Equal(1, captured.ProtocolVersion);
        Assert.Equal("agent-01", captured.DeviceId);
        Assert.Equal("default", captured.ProjectId);
        Assert.Equal("DESKTOP_AGENT_HEARTBEAT", captured.Operation);
        Assert.Empty(captured.Payload);
        Assert.Equal(32, captured.RequestId.Length);
        Assert.Equal(32, captured.IdempotencyKey.Length);
        Assert.True(captured.ExpiresAtEpochMs - captured.IssuedAtEpochMs <= 60_000);
        Assert.Equal("/bridge/v1", handler.LastRequestUri?.AbsolutePath);

        var canonical = BuildCanonical(captured);
        var expected = HMACSHA256.HashData(_secret, Encoding.UTF8.GetBytes(canonical));
        Assert.Equal(Convert.ToHexString(expected).ToLowerInvariant(), captured.SignatureHex);
        CryptographicOperations.ZeroMemory(expected);
    }

    [Fact]
    public async Task PendingOrRevokedAgentReceivesNoProfileAndIsNotTreatedAsConnected()
    {
        var handler = new StubHandler(_ => Task.FromResult(JsonResponse(new
        {
            status = "FAILED",
            errorCode = "AGENT_NOT_OWNER_APPROVED",
            result = new Dictionary<string, string>()
        }, HttpStatusCode.Conflict)));
        var client = CreateClient(handler);

        var heartbeat = await client.SendHeartbeatAsync(Credential());

        Assert.False(heartbeat.Accepted);
        Assert.Equal("AGENT_NOT_OWNER_APPROVED", heartbeat.ErrorCode);
        Assert.Empty(heartbeat.Capabilities);
        Assert.Equal(0, heartbeat.MaxParallelTasks);
    }

    [Fact]
    public async Task MismatchedOrNonCanonicalOwnerProfileFailsClosed()
    {
        foreach (var result in new[]
        {
            new Dictionary<string, string>
            {
                ["agentId"] = "other-agent",
                ["ownerApproved"] = "true",
                ["capabilities"] = "[\"web.research\"]",
                ["maxParallelTasks"] = "1"
            },
            new Dictionary<string, string>
            {
                ["agentId"] = "agent-01",
                ["ownerApproved"] = "true",
                ["capabilities"] = "[\"web.research\",\"web.research\"]",
                ["maxParallelTasks"] = "1"
            },
            new Dictionary<string, string>
            {
                ["agentId"] = "agent-01",
                ["ownerApproved"] = "true",
                ["capabilities"] = "[]",
                ["maxParallelTasks"] = "0"
            }
        })
        {
            var client = CreateClient(new StubHandler(_ => Task.FromResult(JsonResponse(new
            {
                status = "ACCEPTED",
                errorCode = (string?)null,
                result
            }))));
            await Assert.ThrowsAsync<InvalidDataException>(() => client.SendHeartbeatAsync(Credential()));
        }
    }

    [Fact]
    public async Task MissingHeartbeatScopeIsRejectedBeforeNetwork()
    {
        var handler = new StubHandler(_ => throw new Xunit.Sdk.XunitException("Network must not be called."));
        var client = CreateClient(handler);
        var credential = Credential() with
        {
            Scopes = new HashSet<string>(StringComparer.Ordinal) { "desktop.task.result" }
        };

        await Assert.ThrowsAsync<InvalidDataException>(() => client.SendHeartbeatAsync(credential));

        Assert.Null(handler.LastRequestUri);
    }

    private BridgeV1AgentClient CreateClient(StubHandler handler) =>
        new(new FixedTimeProvider(_now), _ => handler);

    private AgentEnrollmentCredential Credential() => new(
        "agent-01",
        "desktop-hub-01",
        Endpoint,
        Pin,
        Convert.ToBase64String(_secret),
        new HashSet<string>(StringComparer.Ordinal) { "desktop.task.claim", "desktop.task.result" },
        _now.AddMinutes(-1),
        _now.AddDays(30));

    private static string BuildCanonical(SignedRequest request)
    {
        var fields = new List<string>
        {
            request.ProtocolVersion.ToString(CultureInfo.InvariantCulture),
            request.RequestId,
            request.IdempotencyKey,
            request.DeviceId,
            request.ProjectId,
            request.Operation,
            request.IssuedAtEpochMs.ToString(CultureInfo.InvariantCulture),
            request.ExpiresAtEpochMs.ToString(CultureInfo.InvariantCulture)
        };
        foreach (var item in request.Payload.OrderBy(item => item.Key, StringComparer.Ordinal))
        {
            fields.Add(item.Key);
            fields.Add(item.Value);
        }
        return string.Concat(fields.Select(field =>
            field.Length.ToString(CultureInfo.InvariantCulture) + ":" + field));
    }

    private static HttpResponseMessage JsonResponse(object payload, HttpStatusCode status = HttpStatusCode.OK) =>
        new(status)
        {
            Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json")
        };

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    private sealed record SignedRequest(
        int ProtocolVersion,
        string RequestId,
        string IdempotencyKey,
        string DeviceId,
        string ProjectId,
        string Operation,
        long IssuedAtEpochMs,
        long ExpiresAtEpochMs,
        Dictionary<string, string> Payload,
        string SignatureHex);

    private sealed class StubHandler(Func<HttpRequestMessage, Task<HttpResponseMessage>> responder) : HttpMessageHandler
    {
        public Uri? LastRequestUri { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            LastRequestUri = request.RequestUri;
            return await responder(request).ConfigureAwait(false);
        }
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
