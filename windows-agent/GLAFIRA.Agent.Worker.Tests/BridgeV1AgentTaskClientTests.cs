using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Agent.Worker;
using GLAFIRA.Contracts.V1.Runtime;
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
                result = ValidClaimResult()
            });
        });
        var client = Client(handler);

        var lease = await client.ClaimNextAsync(Credential(),
            new[] { "web.research", "document.analysis" }, 2,
            new[] { "web.research", "analysis.document" });

        Assert.NotNull(lease);
        Assert.Equal("task-001", lease.TaskId);
        Assert.Equal("windows-core-0001", lease.OwnerCoreId);
        Assert.Equal(3, lease.OwnerGeneration);
        Assert.Equal("web.research", lease.TaskType);
        Assert.Equal("idem-task-001", lease.IdempotencyKey);
        Assert.Equal("payload://task-001", lease.PayloadRef);
        Assert.Equal(new string('a', 64), lease.PayloadSha256);
        Assert.Equal("ReadOnly", lease.EffectRisk);
        Assert.Null(lease.ApprovalRef);
        Assert.Equal(_now.AddMinutes(2).ToUnixTimeMilliseconds(), lease.LeaseExpiresAtEpochMs);
        var envelope = lease.ToEnvelope();
        Assert.Equal("web.research", envelope.TaskType);
        Assert.Equal(lease.OwnerCoreId, envelope.OwnerCoreId);
        Assert.Equal(lease.OwnerGeneration, envelope.OwnerGeneration);
        Assert.Equal("idem-task-001", envelope.IdempotencyKey);
        Assert.Equal("payload://task-001", envelope.PayloadRef);
        Assert.Equal("Analyze supplied material", envelope.Instruction);
        Assert.Equal("RESEARCHER", envelope.Executor);
        Assert.Equal("lease-0001", envelope.LeaseId);
        Assert.Equal(42, envelope.FencingToken);
        Assert.Equal("lease-0001", lease.LeaseId);
        Assert.Equal(42, lease.FencingToken);
        Assert.Equal("DESKTOP_TASK_CLAIM", operation);
        Assert.NotNull(captured);
        Assert.Equal("[\"document.analysis\",\"web.research\"]", captured["capabilities"]);
        Assert.Equal("2", captured["maxParallelTasks"]);
    }

    [Theory]
    [InlineData("ownerGeneration", null)]
    [InlineData("ownerGeneration", "0")]
    [InlineData("ownerCoreId", "bad owner")]
    public async Task ClaimRejectsMissingOrMalformedOwnershipIdentity(string field, string? replacement)
    {
        var result = ValidClaimResult();
        if (replacement is null) result.Remove(field);
        else result[field] = replacement;
        var handler = new StubHandler(_ => Task.FromResult(Json(new { status = "ACCEPTED", result })));

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            Client(handler).ClaimNextAsync(Credential(), ["web.research"], 1, ["web.research"]));
    }

    [Fact]
    public async Task ClaimRejectsUnknownCanonicalContractVersion()
    {
        var result = ValidClaimResult();
        result["taskEnvelope"] = result["taskEnvelope"].Replace("\"schemaVersion\":1", "\"schemaVersion\":2", StringComparison.Ordinal);
        var handler = new StubHandler(_ => Task.FromResult(Json(new { status = "ACCEPTED", result })));

        var error = await Assert.ThrowsAsync<InvalidDataException>(() =>
            Client(handler).ClaimNextAsync(Credential(), ["web.research"], 1, ["web.research"]));

        Assert.Equal("TASK_CLAIM_CONTRACT_INVALID", error.Message);
    }

    [Fact]
    public async Task ClaimSendsOnlyRegisteredSupportedTaskTypes()
    {
        Dictionary<string, string>? captured = null;
        var handler = new StubHandler(async request =>
        {
            using var doc = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            captured = JsonSerializer.Deserialize<Dictionary<string, string>>(
                doc.RootElement.GetProperty("payload").GetRawText());
            return Json(new { status = "ACCEPTED", result = new Dictionary<string, string> { ["available"] = "false" } });
        });

        await Client(handler).ClaimNextAsync(Credential(), new[] { "web.research" }, 1,
            new[] { "web.research", "analysis.document" });

        Assert.NotNull(captured);
        Assert.Equal("[\"analysis.document\",\"web.research\"]", captured["taskTypes"]);
    }

    [Fact]
    public async Task EmptyQueueReturnsNull()
    {
        var client = Client(new StubHandler(_ => Task.FromResult(Json(new
        {
            status = "ACCEPTED",
            result = new Dictionary<string, string> { ["available"] = "false" }
        }))));
        Assert.Null(await client.ClaimNextAsync(Credential(), new[] { "web.research" }, 1, new[] { "web.research" }));
    }

    [Fact]
    public async Task TerminalUpdateRequiresExactServerAcknowledgement()
    {
        var resultJson = "\"done\"";
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
        Dictionary<string, string>? captured = null;
        var handler = new StubHandler(async request =>
        {
            using var document = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            captured = JsonSerializer.Deserialize<Dictionary<string, string>>(
                document.RootElement.GetProperty("payload").GetRawText());
            return Json(new
            {
                status = "SUCCEEDED",
                result = new Dictionary<string, string>
                {
                    ["accepted"] = "true", ["taskId"] = "task-001", ["leaseId"] = "lease-0001",
                    ["fencingToken"] = "42", ["state"] = "SUCCEEDED", ["resultSha256"] = digest
                }
            });
        });
        var client = Client(handler);
        var lease = Lease();

        var ack = await client.SendTaskUpdateAsync(Credential(), lease,
            "SUCCEEDED", 100, "completed", resultJson);

        Assert.Equal("task-001", ack.TaskId);
        Assert.Equal(digest, ack.ResultSha256);
        Assert.Equal(0L, ack.LeaseExpiresAtEpochMs);
        Assert.NotNull(captured);
        Assert.Equal("windows-core-0001", captured["ownerCoreId"]);
        Assert.Equal("3", captured["ownerGeneration"]);
        var canonical = CanonicalTaskContractJson.ParseTaskResult(captured["canonicalResult"]);
        Assert.Equal("task-001", canonical.TaskId);
        Assert.Equal("agent-01", canonical.AgentId);
        Assert.Equal("lease-0001", canonical.LeaseId);
        Assert.Equal(42, canonical.FencingToken);
        Assert.Equal("hub-task-result:task-001", canonical.ResultRef);
        Assert.Equal(digest, canonical.ResultSha256);
    }

    [Fact]
    public async Task TaskUpdateUsesLeaseProjectInSignedEnvelope()
    {
        string? projectId = null;
        var resultJson = "\"done\"";
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
        var handler = new StubHandler(async request =>
        {
            using var document = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            projectId = document.RootElement.GetProperty("projectId").GetString();
            return Json(new
            {
                status = "SUCCEEDED",
                result = new Dictionary<string, string>
                {
                    ["accepted"] = "true", ["taskId"] = "task-001", ["leaseId"] = "lease-0001",
                    ["fencingToken"] = "42", ["state"] = "SUCCEEDED", ["resultSha256"] = digest
                }
            });
        });

        await Client(handler).SendTaskUpdateAsync(
            Credential(), Lease() with { ProjectId = "project-001" }, "SUCCEEDED", 100, "done", resultJson);

        Assert.Equal("project-001", projectId);
    }

    [Fact]
    public async Task ArtifactUploadSignsExactBindingAndAcceptsDuplicateAcknowledgement()
    {
        Dictionary<string, string>? captured = null;
        string? operation = null;
        string? projectId = null;
        var artifact = Artifact();
        var handler = new StubHandler(async request =>
        {
            using var document = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            operation = document.RootElement.GetProperty("operation").GetString();
            projectId = document.RootElement.GetProperty("projectId").GetString();
            captured = JsonSerializer.Deserialize<Dictionary<string, string>>(
                document.RootElement.GetProperty("payload").GetRawText());
            return Json(new
            {
                status = "SUCCEEDED",
                result = ArtifactAcknowledgement(artifact.Metadata, duplicate: true)
            });
        });

        var acknowledgement = await Client(handler).UploadTaskArtifactAsync(Credential(), artifact);

        Assert.True(acknowledgement.Duplicate);
        Assert.Equal("DESKTOP_TASK_ARTIFACT", operation);
        Assert.Equal("project-001", projectId);
        Assert.NotNull(captured);
        Assert.Equal(Convert.ToBase64String(artifact.Payload), captured["payloadBase64"]);
        Assert.Equal(artifact.Metadata.Sha256, captured["sha256"]);
        Assert.Equal("REDACTED", captured["redactionStatus"]);
    }

    [Fact]
    public async Task ArtifactUploadRejectsMismatchedAcknowledgement()
    {
        var artifact = Artifact();
        var acknowledgement = ArtifactAcknowledgement(artifact.Metadata, duplicate: false);
        acknowledgement["fencingToken"] = "43";
        var handler = new StubHandler(_ => Task.FromResult(Json(new
        {
            status = "SUCCEEDED",
            result = acknowledgement
        })));

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            Client(handler).UploadTaskArtifactAsync(Credential(), artifact));
    }

    [Fact]
    public async Task ArtifactUploadRejectsNonCanonicalDuplicateFlag()
    {
        var artifact = Artifact();
        var acknowledgement = ArtifactAcknowledgement(artifact.Metadata, duplicate: false);
        acknowledgement["duplicate"] = "False";
        var handler = new StubHandler(_ => Task.FromResult(Json(new
        {
            status = "SUCCEEDED",
            result = acknowledgement
        })));

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            Client(handler).UploadTaskArtifactAsync(Credential(), artifact));
    }

    [Fact]
    public async Task RunningProgressRequiresAndReturnsServerLeaseExpiry()
    {
        var renewedExpiry = _now.AddMinutes(2).ToUnixTimeMilliseconds();
        var handler = new StubHandler(_ => Task.FromResult(Json(new
        {
            status = "SUCCEEDED",
            result = new Dictionary<string, string>
            {
                ["accepted"] = "true", ["taskId"] = "task-001", ["leaseId"] = "lease-0001",
                ["fencingToken"] = "42", ["state"] = "RUNNING",
                ["resultSha256"] = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("null"))).ToLowerInvariant(),
                ["leaseExpiresAtEpochMs"] = renewedExpiry.ToString(CultureInfo.InvariantCulture)
            }
        })));

        var ack = await Client(handler).SendTaskUpdateAsync(
            Credential(), Lease(), "RUNNING", 25, "still working");

        Assert.Equal(renewedExpiry, ack.LeaseExpiresAtEpochMs);
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
            Client(handler).ClaimNextAsync(credential, new[] { "web.research" }, 1, new[] { "web.research" }));
        Assert.Null(handler.LastRequestUri);
    }

    private AgentTaskLease Lease() => new(
        "task-001", "default", "windows-core-0001", 3,
        "web.research", "idem-task-001",
        "payload://task-001", new string('a', 64), "ReadOnly", null,
        _now.AddMinutes(2).ToUnixTimeMilliseconds(), "parent-001", "sub-001",
        "Research", "Analyze", "RESEARCHER", new HashSet<string> { "web.research" },
        _now.ToUnixTimeMilliseconds(), _now.AddMinutes(5).ToUnixTimeMilliseconds(),
        10, "lease-0001", 42);

    private AgentResultArtifact Artifact()
    {
        var payload = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var metadata = new AgentResultArtifactMetadata(
            1, "artifact-0001", "task-001", "project-001", "windows-core-0001", 3,
            "lease-0001", 42, AgentResultArtifactPolicy.DiagnosticsSchemaId,
            AgentResultArtifactPolicy.DiagnosticsSchemaVersion, AgentResultArtifactPolicy.JsonContentType,
            payload.Length, AgentResultArtifactPolicy.ComputeSha256(payload),
            AgentResultArtifactPolicy.Redacted, _now.ToUnixTimeMilliseconds());
        return new AgentResultArtifact(metadata, payload);
    }

    private static Dictionary<string, string> ArtifactAcknowledgement(
        AgentResultArtifactMetadata metadata,
        bool duplicate) => new(StringComparer.Ordinal)
        {
            ["accepted"] = "true",
            ["duplicate"] = duplicate.ToString().ToLowerInvariant(),
            ["artifactId"] = metadata.ArtifactId,
            ["taskId"] = metadata.TaskId,
            ["ownerCoreId"] = metadata.OwnerCoreId,
            ["ownerGeneration"] = metadata.OwnerGeneration.ToString(CultureInfo.InvariantCulture),
            ["leaseId"] = metadata.LeaseId,
            ["fencingToken"] = metadata.FencingToken.ToString(CultureInfo.InvariantCulture),
            ["schemaId"] = metadata.SchemaId,
            ["schemaVersion"] = metadata.SchemaVersion.ToString(CultureInfo.InvariantCulture),
            ["byteLength"] = metadata.ByteLength.ToString(CultureInfo.InvariantCulture),
            ["sha256"] = metadata.Sha256
        };

    private Dictionary<string, string> ValidClaimResult()
    {
        var result = new Dictionary<string, string>
        {
        ["available"] = "true", ["taskId"] = "task-001", ["projectId"] = "default",
        ["ownerCoreId"] = "windows-core-0001", ["ownerGeneration"] = "3",
        ["taskType"] = "web.research", ["idempotencyKey"] = "idem-task-001",
        ["payloadRef"] = "payload://task-001", ["payloadSha256"] = new string('a', 64),
        ["effectRisk"] = "ReadOnly", ["approvalRef"] = "",
        ["leaseExpiresAtEpochMs"] = _now.AddMinutes(2).ToUnixTimeMilliseconds().ToString(),
        ["parentTaskId"] = "parent-001", ["subtaskId"] = "sub-001",
        ["title"] = "Research", ["instruction"] = "Analyze supplied material",
        ["executor"] = "RESEARCHER", ["requiredCapabilities"] = "[\"web.research\"]",
        ["dependencyTaskIds"] = "[]",
        ["createdAtEpochMs"] = _now.ToUnixTimeMilliseconds().ToString(),
        ["expiresAtEpochMs"] = _now.AddMinutes(5).ToUnixTimeMilliseconds().ToString(),
        ["priority"] = "10", ["leaseId"] = "lease-0001", ["fencingToken"] = "42",
        ["contractVersion"] = "1", ["attempt"] = "1"
        };
        result["taskEnvelope"] = CanonicalTaskContractJson.Serialize(new TaskEnvelopeV1(
            1, "task-001", "default", "idem-task-001", "web.research",
            "payload://task-001", new string('a', 64), "windows-core-0001", "agent-01",
            ["web.research"], [], "READ_ONLY", null, _now, _now.AddMinutes(5)));
        result["taskLease"] = CanonicalTaskContractJson.Serialize(new TaskLeaseV1(
            1, "lease-0001", "task-001", "agent-01", 42, _now, _now.AddMinutes(2), 60));
        return result;
    }

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
