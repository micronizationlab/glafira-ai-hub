using System.Globalization;
using System.Security.Cryptography;
using System.Text.Json;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record BridgeV1DispatchResponse(
    string Status,
    IReadOnlyDictionary<string, string> Result,
    string? ErrorCode = null)
{
    public int ProtocolVersion { get; init; } = 1;
    public string RequestId { get; init; } = string.Empty;
}

/// <summary>
/// Resolves a task payload from the Hub's internal content store. Implementations
/// must not interpret PayloadRef as an unrestricted filesystem path or URL.
/// </summary>
public interface IHubTaskPayloadResolver
{
    ValueTask<byte[]?> ResolveAsync(string payloadRef, CancellationToken cancellationToken);
}

/// <summary>
/// Dispatches the authenticated Bridge v1 task operations into the durable Desktop Hub.
/// Call only after BridgeV1RequestAuthenticator has accepted the signed request.
/// Capabilities and parallelism are checked against the owner's registered agent record;
/// the client cannot grant itself new capabilities by changing the signed payload.
/// </summary>
public sealed class BridgeV1TaskOperationDispatcher(
    DesktopHubStore store,
    IHubTaskPayloadResolver payloadResolver,
    TimeSpan? leaseDuration = null)
{
    private readonly TimeSpan _leaseDuration = leaseDuration ?? TimeSpan.FromMinutes(2);

    public async ValueTask<BridgeV1DispatchResponse> DispatchAsync(
        BridgeV1SignedRequest request,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        BridgeV1DispatchResponse Correlate(BridgeV1DispatchResponse response) => response with
        {
            ProtocolVersion = request.ProtocolVersion,
            RequestId = request.RequestId
        };

        if (request.Operation == BridgeV1Operation.OWNERSHIP_TRANSFER_DISCOVER)
            return Correlate(DiscoverOwnershipTransfers(request, now));
        if (request.Operation == BridgeV1Operation.OWNERSHIP_TRANSFER_READ)
            return Correlate(ReadOwnershipTransfer(request));
        if (request.Operation is not (BridgeV1Operation.DESKTOP_TASK_CLAIM or BridgeV1Operation.DESKTOP_TASK_RESULT or BridgeV1Operation.DESKTOP_TASK_ARTIFACT or BridgeV1Operation.DESKTOP_AGENT_HEARTBEAT))
            return Correlate(Failure("OPERATION_NOT_IMPLEMENTED"));

        var agents = store.GetAgents(now).Where(agent =>
            StringComparer.Ordinal.Equals(agent.DeviceId, request.DeviceId)).ToArray();
        if (agents.Length != 1) return Correlate(Failure("AGENT_NOT_REGISTERED"));
        var agent = agents[0];
        if (!agent.OwnerApproved) return Correlate(Failure("AGENT_NOT_OWNER_APPROVED"));

        // The authenticated device may refresh liveness, but cannot alter its approved
        // capabilities or concurrency ceiling through an ordinary task request.
        if (!store.Heartbeat(agent.AgentId, agent.Capabilities, agent.MaxParallelTasks, now))
            return Correlate(Failure("AGENT_NOT_REGISTERED"));

        if (request.Operation == BridgeV1Operation.DESKTOP_AGENT_HEARTBEAT)
        {
            return Correlate(Accepted(new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["agentId"] = agent.AgentId,
                ["ownerApproved"] = "true",
                ["capabilities"] = JsonSerializer.Serialize(agent.Capabilities),
                ["maxParallelTasks"] = agent.MaxParallelTasks.ToString(CultureInfo.InvariantCulture)
            }));
        }

        var dispatched = request.Operation switch
        {
            BridgeV1Operation.DESKTOP_TASK_CLAIM => await ClaimAsync(request, agent, now, cancellationToken),
            BridgeV1Operation.DESKTOP_TASK_ARTIFACT => AcceptArtifact(request, agent, now),
            _ => CompleteOrReport(request, agent, now)
        };
        return Correlate(dispatched);
    }

    private BridgeV1DispatchResponse ReadOwnershipTransfer(BridgeV1SignedRequest request)
    {
        if (!request.Payload.TryGetValue("transferId", out var transferId))
            return Failure("OWNERSHIP_TRANSFER_ID_REQUIRED");
        var transfer = store.GetOwnershipTransfer(transferId);
        if (transfer is null || !StringComparer.Ordinal.Equals(transfer.ProjectId, request.ProjectId))
            return Accepted(new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["available"] = "false"
            });
        return Accepted(new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["available"] = "true",
            ["envelope"] = transfer.EnvelopeJson
        });
    }

    private BridgeV1DispatchResponse DiscoverOwnershipTransfers(
        BridgeV1SignedRequest request,
        DateTimeOffset now)
    {
        var transferIds = store.GetDiscoverableOwnershipTransferIds(request.ProjectId, now);
        return Accepted(new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["transferIds"] = JsonSerializer.Serialize(transferIds)
        });
    }

    private async ValueTask<BridgeV1DispatchResponse> ClaimAsync(
        BridgeV1SignedRequest request,
        HubAgentSnapshot agent,
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        if (!request.Payload.TryGetValue("capabilities", out var capabilitiesJson) ||
            !request.Payload.TryGetValue("maxParallelTasks", out var parallelText) ||
            !int.TryParse(parallelText, NumberStyles.None, CultureInfo.InvariantCulture, out var requestedParallel) ||
            requestedParallel != agent.MaxParallelTasks)
            return Failure("AGENT_CAPABILITY_MISMATCH");

        string[] requestedCapabilities;
        try
        {
            requestedCapabilities = JsonSerializer.Deserialize<string[]>(capabilitiesJson) ?? [];
        }
        catch (JsonException)
        {
            return Failure("AGENT_CAPABILITY_MISMATCH");
        }

        if (requestedCapabilities.Length > 16 ||
            requestedCapabilities.Distinct(StringComparer.Ordinal).Count() != requestedCapabilities.Length ||
            !new HashSet<string>(requestedCapabilities, StringComparer.Ordinal)
                .SetEquals(agent.Capabilities))
            return Failure("AGENT_CAPABILITY_MISMATCH");

        if (!request.Payload.TryGetValue("taskTypes", out var taskTypesJson))
            return Failure("AGENT_TASK_TYPE_REQUIRED");
        string[] parsedTypes;
        try { parsedTypes = JsonSerializer.Deserialize<string[]>(taskTypesJson) ?? []; }
        catch (JsonException) { return Failure("AGENT_TASK_TYPE_MISMATCH"); }
        if (parsedTypes.Length is < 1 or > 64 ||
            parsedTypes.Any(type => string.IsNullOrWhiteSpace(type) || type.Length > 96 ||
                !System.Text.RegularExpressions.Regex.IsMatch(type, "^[a-z][a-z0-9_.-]*$")) ||
            parsedTypes.Distinct(StringComparer.Ordinal).Count() != parsedTypes.Length)
            return Failure("AGENT_TASK_TYPE_MISMATCH");

        var lease = store.ClaimNext(agent.AgentId, now, _leaseDuration, parsedTypes);
        if (lease is null)
            return Accepted(new Dictionary<string, string> { ["available"] = "false" });

        byte[]? payloadBytes = null;
        try
        {
            payloadBytes = await payloadResolver.ResolveAsync(lease.PayloadRef, cancellationToken);
            if (payloadBytes is null || payloadBytes.Length is 0 or > 65_536)
            {
                store.Complete(agent.AgentId, lease.TaskId, lease.LeaseId, lease.FencingToken,
                    succeeded: false, now: now, errorCode: "TASK_PAYLOAD_UNAVAILABLE");
                return Failure("TASK_PAYLOAD_UNAVAILABLE");
            }

            var actualHash = SHA256.HashData(payloadBytes);
            var expectedHash = Convert.FromHexString(lease.PayloadSha256);
            var hashMatches = CryptographicOperations.FixedTimeEquals(actualHash, expectedHash);
            CryptographicOperations.ZeroMemory(actualHash);
            CryptographicOperations.ZeroMemory(expectedHash);
            if (!hashMatches)
            {
                store.Complete(agent.AgentId, lease.TaskId, lease.LeaseId, lease.FencingToken,
                    succeeded: false, now, errorCode: "TASK_PAYLOAD_HASH_MISMATCH");
                return Failure("TASK_PAYLOAD_HASH_MISMATCH");
            }

            if (!TryDecodePayload(payloadBytes, lease.TaskType, agent.Capabilities, now, out var fields))
            {
                store.Complete(agent.AgentId, lease.TaskId, lease.LeaseId, lease.FencingToken,
                    succeeded: false, now, errorCode: "TASK_PAYLOAD_INVALID");
                return Failure("TASK_PAYLOAD_INVALID");
            }

            var result = new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["available"] = "true",
                ["taskId"] = lease.TaskId,
                ["projectId"] = lease.ProjectId,
                ["ownerCoreId"] = lease.OwnerCoreId,
                ["ownerGeneration"] = lease.OwnerGeneration.ToString(CultureInfo.InvariantCulture),
                ["taskType"] = lease.TaskType,
                ["idempotencyKey"] = lease.IdempotencyKey,
                ["payloadRef"] = lease.PayloadRef,
                ["payloadSha256"] = lease.PayloadSha256,
                ["effectRisk"] = lease.EffectRisk.ToString(),
                ["approvalRef"] = lease.ApprovalRef ?? string.Empty,
                ["leaseExpiresAtEpochMs"] = lease.ExpiresAt.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture),
                ["parentTaskId"] = fields.ParentTaskId,
                ["subtaskId"] = fields.SubtaskId,
                ["title"] = fields.Title,
                ["instruction"] = fields.Instruction,
                ["executor"] = fields.Executor,
                ["requiredCapabilities"] = JsonSerializer.Serialize(fields.RequiredCapabilities),
                ["createdAtEpochMs"] = fields.CreatedAtEpochMs.ToString(CultureInfo.InvariantCulture),
                ["expiresAtEpochMs"] = fields.ExpiresAtEpochMs.ToString(CultureInfo.InvariantCulture),
                ["priority"] = fields.Priority.ToString(CultureInfo.InvariantCulture),
                ["leaseId"] = lease.LeaseId,
                ["fencingToken"] = lease.FencingToken.ToString(CultureInfo.InvariantCulture)
            };
            return Accepted(result);
        }
        finally
        {
            if (payloadBytes is not null) CryptographicOperations.ZeroMemory(payloadBytes);
        }
    }

    private BridgeV1DispatchResponse CompleteOrReport(
        BridgeV1SignedRequest request,
        HubAgentSnapshot agent,
        DateTimeOffset now)
    {
        var payload = request.Payload;
        if (!payload.TryGetValue("taskId", out var taskId) ||
            !payload.TryGetValue("ownerCoreId", out var ownerCoreId) ||
            !long.TryParse(payload.GetValueOrDefault("ownerGeneration"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var ownerGeneration) || ownerGeneration <= 0 ||
            !payload.TryGetValue("leaseId", out var leaseId) ||
            !long.TryParse(payload.GetValueOrDefault("fencingToken"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var fencingToken) ||
            !payload.TryGetValue("state", out var state) ||
            !int.TryParse(payload.GetValueOrDefault("progressPercent"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var progress))
            return Failure("INVALID_TASK_RESULT");

        try
        {
            if (!store.MatchesTaskOwnership(taskId, ownerCoreId, ownerGeneration))
                return Failure("OWNER_GENERATION_REJECTED");
        }
        catch (ArgumentException)
        {
            return Failure("INVALID_TASK_RESULT");
        }

        bool accepted;
        if (state is "ACCEPTED" or "RUNNING")
        {
            accepted = store.ReportProgress(
                agent.AgentId, taskId, leaseId, fencingToken, progress,
                payload.GetValueOrDefault("progressMessage") ?? "working", now, _leaseDuration);
        }
        else if (state is "SUCCEEDED" or "FAILED")
        {
            var succeeded = state == "SUCCEEDED";
            string? result;
            if (payload.GetValueOrDefault("resultEncoding") == "json")
            {
                try
                {
                    using var encoded = JsonDocument.Parse(payload.GetValueOrDefault("result") ?? "null",
                        new JsonDocumentOptions { MaxDepth = 2 });
                    if (encoded.RootElement.ValueKind is not (JsonValueKind.String or JsonValueKind.Null))
                        return Failure("INVALID_TASK_RESULT");
                    result = encoded.RootElement.ValueKind == JsonValueKind.Null ? null : encoded.RootElement.GetString();
                }
                catch (JsonException) { return Failure("INVALID_TASK_RESULT"); }
            }
            else result = NormalizeNone(payload.GetValueOrDefault("result"));
            var errorCode = NormalizeNone(payload.GetValueOrDefault("errorCode"));
            if (!succeeded && errorCode is null) errorCode = "TASK_FAILED";
            if (succeeded && result is not null &&
                !HasRequiredTaskArtifact(
                    taskId, ownerCoreId, ownerGeneration, leaseId, fencingToken, result))
                return Failure("TASK_ARTIFACT_REQUIRED");
            accepted = store.Complete(agent.AgentId, taskId, leaseId, fencingToken,
                succeeded, now, result, errorCode);
        }
        else
        {
            return Failure("INVALID_TASK_RESULT");
        }

        if (!accepted) return Failure("LEASE_FENCING_REJECTED");
        var acknowledgement = new Dictionary<string, string>
        {
            ["accepted"] = "true", ["taskId"] = taskId, ["leaseId"] = leaseId,
            ["fencingToken"] = fencingToken.ToString(CultureInfo.InvariantCulture), ["state"] = state,
            ["resultSha256"] = Convert.ToHexString(SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes(payload.GetValueOrDefault("result") ?? "none"))).ToLowerInvariant()
        };
        if (state is "ACCEPTED" or "RUNNING")
            acknowledgement["leaseExpiresAtEpochMs"] =
                now.Add(_leaseDuration).ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
        return new BridgeV1DispatchResponse("SUCCEEDED", acknowledgement);
    }

    private bool HasRequiredTaskArtifact(
        string taskId,
        string ownerCoreId,
        long ownerGeneration,
        string leaseId,
        long fencingToken,
        string result)
    {
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(result, new JsonDocumentOptions
            {
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
                MaxDepth = 4
            });
        }
        catch (JsonException)
        {
            return true; // Ordinary non-artifact result text remains supported.
        }

        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object ||
                !root.TryGetProperty("ResultRef", out var referenceElement) ||
                referenceElement.ValueKind is JsonValueKind.Null)
                return true;
            if (referenceElement.ValueKind != JsonValueKind.String)
                return false;
            var reference = referenceElement.GetString();
            const string prefix = "agent-artifact:";
            if (reference is null || !reference.StartsWith(prefix, StringComparison.Ordinal))
                return true;

            var artifactId = reference[prefix.Length..];
            if (!IsArtifactIdentifier(artifactId) ||
                !root.TryGetProperty("TaskId", out var taskElement) ||
                taskElement.ValueKind != JsonValueKind.String ||
                !StringComparer.Ordinal.Equals(taskElement.GetString(), taskId) ||
                !root.TryGetProperty("Outcome", out var outcomeElement) ||
                outcomeElement.ValueKind != JsonValueKind.String ||
                !StringComparer.Ordinal.Equals(outcomeElement.GetString(), "SUCCEEDED") ||
                !root.TryGetProperty("ResultSha256", out var digestElement) ||
                digestElement.ValueKind != JsonValueKind.String)
                return false;
            var digest = digestElement.GetString();
            if (digest is null || !IsLowerHexSha256(digest)) return false;

            var artifact = store.GetTaskArtifact(artifactId);
            if (artifact is null) return false;
            try
            {
                var metadata = artifact.Metadata;
                return StringComparer.Ordinal.Equals(metadata.TaskId, taskId) &&
                    StringComparer.Ordinal.Equals(metadata.OwnerCoreId, ownerCoreId) &&
                    metadata.OwnerGeneration == ownerGeneration &&
                    StringComparer.Ordinal.Equals(metadata.LeaseId, leaseId) &&
                    metadata.FencingToken == fencingToken &&
                    StringComparer.Ordinal.Equals(metadata.Sha256, digest);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(artifact.Payload);
            }
        }
    }

    private BridgeV1DispatchResponse AcceptArtifact(
        BridgeV1SignedRequest request,
        HubAgentSnapshot agent,
        DateTimeOffset now)
    {
        var payload = request.Payload;
        if (!int.TryParse(payload.GetValueOrDefault("contractVersion"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var contractVersion) || contractVersion != 1 ||
            !payload.TryGetValue("artifactId", out var artifactId) || !IsArtifactIdentifier(artifactId) ||
            !payload.TryGetValue("taskId", out var taskId) ||
            !payload.TryGetValue("ownerCoreId", out var ownerCoreId) ||
            !long.TryParse(payload.GetValueOrDefault("ownerGeneration"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var ownerGeneration) || ownerGeneration <= 0 ||
            !payload.TryGetValue("leaseId", out var leaseId) ||
            !long.TryParse(payload.GetValueOrDefault("fencingToken"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var fencingToken) || fencingToken <= 0 ||
            !payload.TryGetValue("schemaId", out var schemaId) ||
            !int.TryParse(payload.GetValueOrDefault("schemaVersion"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var schemaVersion) || schemaVersion != 1 ||
            !payload.TryGetValue("contentType", out var contentType) ||
            !int.TryParse(payload.GetValueOrDefault("byteLength"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var byteLength) || byteLength is < 1 or > 32_768 ||
            !payload.TryGetValue("sha256", out var sha256) || !IsLowerHexSha256(sha256) ||
            !payload.TryGetValue("redactionStatus", out var redactionStatus) ||
            !long.TryParse(payload.GetValueOrDefault("createdAtEpochMs"), NumberStyles.None,
                CultureInfo.InvariantCulture, out var createdAtEpochMs) || createdAtEpochMs <= 0 ||
            !payload.TryGetValue("payloadBase64", out var payloadBase64) ||
            !StringComparer.Ordinal.Equals(schemaId, "glafira.agent.diagnostics.snapshot") ||
            !StringComparer.Ordinal.Equals(contentType, "application/json") ||
            !StringComparer.Ordinal.Equals(redactionStatus, "REDACTED"))
            return Failure("INVALID_TASK_ARTIFACT");

        DateTimeOffset createdAt;
        try { createdAt = DateTimeOffset.FromUnixTimeMilliseconds(createdAtEpochMs); }
        catch (ArgumentOutOfRangeException) { return Failure("INVALID_TASK_ARTIFACT"); }
        if (createdAt > now.AddSeconds(30)) return Failure("INVALID_TASK_ARTIFACT");

        byte[] artifactBytes;
        try { artifactBytes = Convert.FromBase64String(payloadBase64); }
        catch (FormatException) { return Failure("INVALID_TASK_ARTIFACT"); }
        try
        {
            if (artifactBytes.Length != byteLength ||
                !StringComparer.Ordinal.Equals(Convert.ToBase64String(artifactBytes), payloadBase64) ||
                !IsJsonObject(artifactBytes))
                return Failure("INVALID_TASK_ARTIFACT");

            byte[] expectedHash;
            try { expectedHash = Convert.FromHexString(sha256); }
            catch (FormatException) { return Failure("INVALID_TASK_ARTIFACT"); }
            var actualHash = SHA256.HashData(artifactBytes);
            var hashMatches = CryptographicOperations.FixedTimeEquals(expectedHash, actualHash);
            CryptographicOperations.ZeroMemory(expectedHash);
            CryptographicOperations.ZeroMemory(actualHash);
            if (!hashMatches) return Failure("INVALID_TASK_ARTIFACT");

            var metadata = new HubTaskArtifactMetadata(
                contractVersion, artifactId, taskId, ownerCoreId, ownerGeneration,
                leaseId, fencingToken, schemaId, schemaVersion, contentType,
                byteLength, sha256, redactionStatus, createdAt);
            HubTaskArtifactAcceptance acceptance;
            try
            {
                acceptance = store.AcceptTaskArtifact(
                    agent.AgentId, request.ProjectId, metadata, artifactBytes, now);
            }
            catch (ArgumentException)
            {
                return Failure("INVALID_TASK_ARTIFACT");
            }

            if (acceptance == HubTaskArtifactAcceptance.Conflict)
                return Failure("ARTIFACT_ID_CONFLICT");
            if (acceptance == HubTaskArtifactAcceptance.LeaseRejected)
                return Failure("LEASE_FENCING_REJECTED");

            return new BridgeV1DispatchResponse("SUCCEEDED", new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["accepted"] = "true",
                ["duplicate"] = (acceptance == HubTaskArtifactAcceptance.Duplicate).ToString().ToLowerInvariant(),
                ["artifactId"] = artifactId,
                ["taskId"] = taskId,
                ["ownerCoreId"] = ownerCoreId,
                ["ownerGeneration"] = ownerGeneration.ToString(CultureInfo.InvariantCulture),
                ["leaseId"] = leaseId,
                ["fencingToken"] = fencingToken.ToString(CultureInfo.InvariantCulture),
                ["schemaId"] = schemaId,
                ["schemaVersion"] = schemaVersion.ToString(CultureInfo.InvariantCulture),
                ["byteLength"] = byteLength.ToString(CultureInfo.InvariantCulture),
                ["sha256"] = sha256
            });
        }
        finally
        {
            CryptographicOperations.ZeroMemory(artifactBytes);
        }
    }

    private static bool IsJsonObject(byte[] bytes)
    {
        try
        {
            using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 8 });
            return document.RootElement.ValueKind == JsonValueKind.Object;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static bool IsArtifactIdentifier(string value) =>
        value is { Length: >= 1 and <= 128 } &&
        char.IsAsciiLetterOrDigit(value[0]) &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.' or ':');

    private static bool IsLowerHexSha256(string value) =>
        value is { Length: 64 } &&
        value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static bool TryDecodePayload(
        byte[] bytes,
        string taskType,
        IReadOnlyCollection<string> agentCapabilities,
        DateTimeOffset now,
        out DecodedTaskPayload fields)
    {
        fields = default!;
        try
        {
            using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 8 });
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return false;
            var allowed = new HashSet<string>([
                "parentTaskId", "subtaskId", "title", "instruction", "executor",
                "requiredCapabilities", "createdAtEpochMs", "expiresAtEpochMs", "priority"
            ], StringComparer.Ordinal);
            if (root.EnumerateObject().Any(property => !allowed.Contains(property.Name)) ||
                allowed.Any(name => !root.TryGetProperty(name, out _)))
                return false;

            var parentTaskId = root.GetProperty("parentTaskId").GetString();
            var subtaskId = root.GetProperty("subtaskId").GetString();
            var title = root.GetProperty("title").GetString();
            var instruction = root.GetProperty("instruction").GetString();
            var executor = root.GetProperty("executor").GetString();
            var required = root.GetProperty("requiredCapabilities");
            if (parentTaskId is null || subtaskId is null || title is null || instruction is null ||
                executor is null || required.ValueKind != JsonValueKind.Array ||
                parentTaskId.Length > 128 || subtaskId.Length > 128 ||
                title.Length is 0 or > 256 || instruction.Length is 0 or > 12_000)
                return false;

            var requiredCapabilities = required.EnumerateArray()
                .Select(value => value.ValueKind == JsonValueKind.String ? value.GetString() : null)
                .ToArray();
            if (requiredCapabilities.Any(value => string.IsNullOrWhiteSpace(value)) ||
                requiredCapabilities.Distinct(StringComparer.Ordinal).Count() != requiredCapabilities.Length ||
                requiredCapabilities.Any(value => !agentCapabilities.Contains(value!, StringComparer.Ordinal)))
                return false;

            var expectedExecutor = taskType switch
            {
                "core.orchestration" => "GLAFIRA",
                "analysis.document" => "ANALYST",
                "code.build" => "PROGRAMMER",
                "web.research" => "RESEARCHER",
                _ => null
            };
            if (expectedExecutor is null || !StringComparer.Ordinal.Equals(expectedExecutor, executor))
                return false;

            var created = root.GetProperty("createdAtEpochMs").GetInt64();
            var expires = root.GetProperty("expiresAtEpochMs").GetInt64();
            var priority = root.GetProperty("priority").GetInt32();
            if (created <= 0 || created > now.AddSeconds(30).ToUnixTimeMilliseconds() ||
                expires <= now.ToUnixTimeMilliseconds() || expires <= created ||
                priority is < -100 or > 100)
                return false;

            fields = new DecodedTaskPayload(parentTaskId, subtaskId, title, instruction,
                executor, requiredCapabilities.Select(value => value!).ToArray(), created, expires, priority);
            return true;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or OverflowException)
        {
            return false;
        }
    }

    private static string? NormalizeNone(string? value) =>
        string.IsNullOrWhiteSpace(value) || StringComparer.Ordinal.Equals(value, "none") ? null : value;

    private static BridgeV1DispatchResponse Accepted(IReadOnlyDictionary<string, string> result) =>
        new("ACCEPTED", result);

    private static BridgeV1DispatchResponse Failure(string errorCode) =>
        new("FAILED", new Dictionary<string, string>(), errorCode);

    private sealed record DecodedTaskPayload(
        string ParentTaskId,
        string SubtaskId,
        string Title,
        string Instruction,
        string Executor,
        IReadOnlyList<string> RequiredCapabilities,
        long CreatedAtEpochMs,
        long ExpiresAtEpochMs,
        int Priority);
}
