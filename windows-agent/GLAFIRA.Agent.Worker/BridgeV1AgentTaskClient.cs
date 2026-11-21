using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GLAFIRA.Agent.Worker;

public sealed record AgentTaskLease(
    string TaskId,
    string ProjectId,
    string OwnerCoreId,
    long OwnerGeneration,
    string TaskType,
    string IdempotencyKey,
    string PayloadRef,
    string PayloadSha256,
    string EffectRisk,
    string? ApprovalRef,
    long LeaseExpiresAtEpochMs,
    string ParentTaskId,
    string SubtaskId,
    string Title,
    string Instruction,
    string Executor,
    IReadOnlySet<string> RequiredCapabilities,
    long CreatedAtEpochMs,
    long ExpiresAtEpochMs,
    int Priority,
    string LeaseId,
    long FencingToken)
{
    public AgentTaskEnvelope ToEnvelope() => new(
        TaskId, ProjectId, OwnerCoreId, OwnerGeneration,
        IdempotencyKey, TaskType, PayloadRef, PayloadSha256,
        RequiredCapabilities, ApprovalRef, Title, Instruction, Executor,
        ParentTaskId, SubtaskId, CreatedAtEpochMs, ExpiresAtEpochMs,
        Priority, LeaseId, FencingToken, EffectRisk);
}

public sealed record AgentTaskUpdateAck(
    string TaskId,
    string LeaseId,
    long FencingToken,
    string State,
    string ResultSha256,
    long LeaseExpiresAtEpochMs);

/// <summary>
/// Signed Bridge v1 task-claim and result transport for the Windows Agent.
/// This class transports typed task metadata only; it never executes instructions.
/// </summary>
public sealed class BridgeV1AgentTaskClient : IAgentTaskTransport
{
    private const int MaximumResponseBytes = 64 * 1024;
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan RequestLifetime = TimeSpan.FromMinutes(1);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };

    private sealed record SignedRequest(
        int ProtocolVersion, string RequestId, string IdempotencyKey, string DeviceId,
        string ProjectId, string Operation, long IssuedAtEpochMs, long ExpiresAtEpochMs,
        IReadOnlyDictionary<string, string> Payload, string SignatureHex);
    private sealed record Response(string Status, Dictionary<string, string>? Result, string? ErrorCode);

    private readonly TimeProvider _timeProvider;
    private readonly Func<string, HttpMessageHandler> _handlerFactory;

    public BridgeV1AgentTaskClient(TimeProvider? timeProvider = null)
        : this(timeProvider, CreatePinnedHandler) { }

    internal BridgeV1AgentTaskClient(
        TimeProvider? timeProvider,
        Func<string, HttpMessageHandler> handlerFactory)
    {
        _timeProvider = timeProvider ?? TimeProvider.System;
        _handlerFactory = handlerFactory ?? throw new ArgumentNullException(nameof(handlerFactory));
    }

    public async Task<AgentTaskLease?> ClaimNextAsync(
        AgentEnrollmentCredential credential,
        IReadOnlyCollection<string> ownerApprovedCapabilities,
        int maxParallelTasks,
        IReadOnlyCollection<string> supportedTaskTypes,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(supportedTaskTypes);
        ValidateOwnerProfile(credential, ownerApprovedCapabilities, maxParallelTasks, "desktop.task.claim");
        string[]? normalizedTaskTypes = null;
        if (supportedTaskTypes is not null)
        {
            normalizedTaskTypes = supportedTaskTypes.Order(StringComparer.Ordinal).ToArray();
            if (normalizedTaskTypes.Length is < 1 or > 64 ||
                normalizedTaskTypes.Any(type => string.IsNullOrWhiteSpace(type) || type.Length > 96 ||
                    !System.Text.RegularExpressions.Regex.IsMatch(type, "^[a-z][a-z0-9_.-]*$")) ||
                normalizedTaskTypes.Distinct(StringComparer.Ordinal).Count() != normalizedTaskTypes.Length)
                throw new ArgumentException("Supported task types must be 1–64 unique bounded identifiers.", nameof(supportedTaskTypes));
        }
        var payload = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["capabilities"] = JsonSerializer.Serialize(ownerApprovedCapabilities.Order(StringComparer.Ordinal)),
            ["maxParallelTasks"] = maxParallelTasks.ToString(CultureInfo.InvariantCulture)
        };
        if (normalizedTaskTypes is not null)
            payload["taskTypes"] = JsonSerializer.Serialize(normalizedTaskTypes);
        var response = await SendAsync(credential, "DESKTOP_TASK_CLAIM", payload, cancellationToken).ConfigureAwait(false);
        if (response.Status != "ACCEPTED" || response.Result is null)
            throw new InvalidDataException(response.ErrorCode ?? "TASK_CLAIM_REJECTED");
        var result = response.Result;
        if (!result.TryGetValue("available", out var available))
            throw new InvalidDataException("TASK_CLAIM_RESPONSE_INVALID");
        if (available == "false") return null;
        if (available != "true") throw new InvalidDataException("TASK_CLAIM_RESPONSE_INVALID");

        var taskId = Required(result, "taskId");
        var projectId = Required(result, "projectId");
        var ownerCoreId = Required(result, "ownerCoreId");
        var taskType = Required(result, "taskType");
        var idempotencyKey = Required(result, "idempotencyKey");
        var payloadRef = Required(result, "payloadRef");
        var payloadSha256 = Required(result, "payloadSha256");
        var effectRisk = Required(result, "effectRisk");
        var approvalText = result.GetValueOrDefault("approvalRef") ?? string.Empty;
        var approvalRef = string.IsNullOrEmpty(approvalText) ? null : approvalText;
        var parentTaskId = Required(result, "parentTaskId");
        var subtaskId = Required(result, "subtaskId");
        var title = Required(result, "title");
        var instruction = Required(result, "instruction");
        var executor = Required(result, "executor");
        var leaseId = Required(result, "leaseId");
        if (!long.TryParse(Required(result, "ownerGeneration"), NumberStyles.None, CultureInfo.InvariantCulture, out var ownerGeneration) || ownerGeneration <= 0 ||
            !long.TryParse(Required(result, "leaseExpiresAtEpochMs"), NumberStyles.None, CultureInfo.InvariantCulture, out var leaseExpires) ||
            !long.TryParse(Required(result, "fencingToken"), NumberStyles.None, CultureInfo.InvariantCulture, out var fencing) || fencing <= 0 ||
            !long.TryParse(Required(result, "createdAtEpochMs"), NumberStyles.None, CultureInfo.InvariantCulture, out var created) ||
            !long.TryParse(Required(result, "expiresAtEpochMs"), NumberStyles.None, CultureInfo.InvariantCulture, out var expires) ||
            !int.TryParse(Required(result, "priority"), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var priority))
            throw new InvalidDataException("TASK_CLAIM_RESPONSE_INVALID");

        string[] requiredCapabilities;
        try { requiredCapabilities = JsonSerializer.Deserialize<string[]>(Required(result, "requiredCapabilities"), JsonOptions) ?? []; }
        catch (JsonException error) { throw new InvalidDataException("TASK_CLAIM_CAPABILITIES_INVALID", error); }
        if (taskId.Length is < 1 or > 64 || projectId.Length is < 1 or > 128 ||
            ownerCoreId.Length is < 8 or > 128 ||
            !System.Text.RegularExpressions.Regex.IsMatch(ownerCoreId, "^[A-Za-z0-9._-]+$") ||
            taskType.Length is < 1 or > 96 || idempotencyKey.Length is < 1 or > 128 ||
            payloadRef.Length is < 1 or > 512 || !System.Text.RegularExpressions.Regex.IsMatch(payloadSha256, "^[a-fA-F0-9]{64}$") ||
            effectRisk is not ("ReadOnly" or "LocalWrite" or "ExternalSideEffect") ||
            approvalText.Length > 128 || leaseExpires <= _timeProvider.GetUtcNow().ToUnixTimeMilliseconds() ||
            parentTaskId.Length > 128 || subtaskId.Length > 128 || title.Length is < 1 or > 256 ||
            instruction.Length is < 1 or > 12_000 || leaseId.Length is < 8 or > 128 ||
            created <= 0 || expires <= _timeProvider.GetUtcNow().ToUnixTimeMilliseconds() ||
            priority is < -100 or > 100 || requiredCapabilities.Length > 16 ||
            requiredCapabilities.Any(string.IsNullOrWhiteSpace) ||
            requiredCapabilities.Distinct(StringComparer.Ordinal).Count() != requiredCapabilities.Length ||
            requiredCapabilities.Any(capability => !ownerApprovedCapabilities.Contains(capability, StringComparer.Ordinal)))
            throw new InvalidDataException("TASK_CLAIM_RESPONSE_INVALID");

        return new AgentTaskLease(taskId, projectId, ownerCoreId, ownerGeneration,
            taskType, idempotencyKey, payloadRef,
            payloadSha256.ToLowerInvariant(), effectRisk, approvalRef, leaseExpires,
            parentTaskId, subtaskId, title, instruction, executor,
            new HashSet<string>(requiredCapabilities, StringComparer.Ordinal),
            created, expires, priority, leaseId, fencing);
    }

    public async Task<AgentTaskUpdateAck> SendTaskUpdateAsync(
        AgentEnrollmentCredential credential,
        AgentTaskLease lease,
        string state,
        int progressPercent,
        string progressMessage,
        string resultJson = "null",
        string errorCode = "none",
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(lease);
        if (!credential.Scopes.Contains("desktop.task.result"))
            throw new InvalidDataException("Agent enrollment does not grant the task-result scope.");
        if (state is not ("ACCEPTED" or "RUNNING" or "SUCCEEDED" or "FAILED") ||
            progressPercent is < 0 or > 100 || string.IsNullOrWhiteSpace(progressMessage) ||
            progressMessage.Length > 512 || string.IsNullOrWhiteSpace(resultJson) ||
            resultJson.Length > 12_000 || string.IsNullOrWhiteSpace(errorCode) || errorCode.Length > 128)
            throw new ArgumentException("Task update fields are invalid.");

        if (state is "SUCCEEDED" or "FAILED")
        {
            using var parsed = JsonDocument.Parse(resultJson, new JsonDocumentOptions { MaxDepth = 2 });
            if (parsed.RootElement.ValueKind is not (JsonValueKind.String or JsonValueKind.Null))
                throw new ArgumentException("Terminal result must be a JSON string or null.", nameof(resultJson));
        }

        var payload = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["taskId"] = lease.TaskId,
            ["ownerCoreId"] = lease.OwnerCoreId,
            ["ownerGeneration"] = lease.OwnerGeneration.ToString(CultureInfo.InvariantCulture),
            ["leaseId"] = lease.LeaseId,
            ["fencingToken"] = lease.FencingToken.ToString(CultureInfo.InvariantCulture),
            ["state"] = state,
            ["progressPercent"] = progressPercent.ToString(CultureInfo.InvariantCulture),
            ["progressMessage"] = progressMessage,
            ["result"] = resultJson,
            ["errorCode"] = errorCode,
            ["resultEncoding"] = "json"
        };
        var response = await SendAsync(credential, "DESKTOP_TASK_RESULT", payload, cancellationToken).ConfigureAwait(false);
        if (response.Status != "SUCCEEDED" || response.Result is null ||
            response.Result.GetValueOrDefault("accepted") != "true")
            throw new InvalidDataException(response.ErrorCode ?? "TASK_RESULT_REJECTED");

        var expectedDigest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resultJson))).ToLowerInvariant();
        var ack = response.Result;
        if (ack.GetValueOrDefault("taskId") != lease.TaskId ||
            ack.GetValueOrDefault("leaseId") != lease.LeaseId ||
            ack.GetValueOrDefault("fencingToken") != lease.FencingToken.ToString(CultureInfo.InvariantCulture) ||
            ack.GetValueOrDefault("state") != state ||
            ack.GetValueOrDefault("resultSha256") != expectedDigest)
            throw new InvalidDataException("TASK_RESULT_ACK_MISMATCH");
        var renewedExpiry = 0L;
        if (state is "ACCEPTED" or "RUNNING")
        {
            if (!long.TryParse(ack.GetValueOrDefault("leaseExpiresAtEpochMs"), NumberStyles.None,
                    CultureInfo.InvariantCulture, out renewedExpiry) ||
                renewedExpiry <= _timeProvider.GetUtcNow().ToUnixTimeMilliseconds())
                throw new InvalidDataException("TASK_LEASE_RENEWAL_ACK_INVALID");
        }
        return new AgentTaskUpdateAck(
            lease.TaskId, lease.LeaseId, lease.FencingToken, state, expectedDigest, renewedExpiry);
    }

    private async Task<Response> SendAsync(
        AgentEnrollmentCredential credential,
        string operation,
        IReadOnlyDictionary<string, string> payload,
        CancellationToken cancellationToken)
    {
        var now = _timeProvider.GetUtcNow();
        AgentEnrollmentCredentialPolicy.Validate(credential, now);
        var issued = now.ToUnixTimeMilliseconds();
        var expires = now.Add(RequestLifetime).ToUnixTimeMilliseconds();
        var requestId = Guid.NewGuid().ToString("N");
        var idempotency = Guid.NewGuid().ToString("N");
        var unsigned = new SignedRequest(1, requestId, idempotency, credential.AgentId, "default",
            operation, issued, expires, payload, string.Empty);
        var canonical = Encoding.UTF8.GetBytes(BuildCanonical(unsigned));
        var secret = Convert.FromBase64String(credential.SharedSecretBase64);
        byte[] signature;
        try { signature = HMACSHA256.HashData(secret, canonical); }
        finally
        {
            CryptographicOperations.ZeroMemory(secret);
            CryptographicOperations.ZeroMemory(canonical);
        }
        var signed = unsigned with { SignatureHex = Convert.ToHexString(signature).ToLowerInvariant() };
        CryptographicOperations.ZeroMemory(signature);
        var requestBytes = JsonSerializer.SerializeToUtf8Bytes(signed, JsonOptions);
        try
        {
            using var handler = _handlerFactory(credential.TlsCertificateSha256);
            using var client = new HttpClient(handler, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan };
            using var request = new HttpRequestMessage(HttpMethod.Post, credential.Endpoint)
            {
                Content = new ByteArrayContent(requestBytes)
            };
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json") { CharSet = "utf-8" };
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(RequestTimeout);
            using var httpResponse = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
            if (httpResponse.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.Conflict or HttpStatusCode.Unauthorized) ||
                httpResponse.Content.Headers.ContentType?.MediaType is not "application/json")
                throw new HttpRequestException("Desktop Hub returned an unexpected task response.");
            var bytes = await ReadBoundedAsync(httpResponse.Content, timeout.Token).ConfigureAwait(false);
            try
            {
                return JsonSerializer.Deserialize<Response>(bytes, JsonOptions)
                    ?? throw new InvalidDataException("Desktop Hub task response is empty.");
            }
            catch (JsonException error) { throw new InvalidDataException("Desktop Hub task response is invalid.", error); }
            finally { CryptographicOperations.ZeroMemory(bytes); }
        }
        finally { CryptographicOperations.ZeroMemory(requestBytes); }
    }

    private static void ValidateOwnerProfile(
        AgentEnrollmentCredential credential,
        IReadOnlyCollection<string> capabilities,
        int parallelism,
        string scope)
    {
        ArgumentNullException.ThrowIfNull(capabilities);
        if (!credential.Scopes.Contains(scope) || parallelism is < 1 or > 16 ||
            capabilities.Count is < 1 or > 16 ||
            capabilities.Any(string.IsNullOrWhiteSpace) ||
            capabilities.Distinct(StringComparer.Ordinal).Count() != capabilities.Count)
            throw new InvalidDataException("Agent owner-approved task profile is invalid.");
    }

    private static string Required(IReadOnlyDictionary<string, string> values, string key) =>
        values.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value)
            ? value : throw new InvalidDataException("TASK_CLAIM_RESPONSE_INVALID");

    private static string BuildCanonical(SignedRequest request)
    {
        var fields = new List<string>
        {
            request.ProtocolVersion.ToString(CultureInfo.InvariantCulture), request.RequestId,
            request.IdempotencyKey, request.DeviceId, request.ProjectId, request.Operation,
            request.IssuedAtEpochMs.ToString(CultureInfo.InvariantCulture),
            request.ExpiresAtEpochMs.ToString(CultureInfo.InvariantCulture)
        };
        foreach (var item in request.Payload.OrderBy(item => item.Key, StringComparer.Ordinal))
        { fields.Add(item.Key); fields.Add(item.Value); }
        return string.Concat(fields.Select(field =>
            field.Length.ToString(CultureInfo.InvariantCulture) + ":" + field));
    }

    private static HttpMessageHandler CreatePinnedHandler(string pin) => new HttpClientHandler
    {
        AllowAutoRedirect = false, UseCookies = false, UseProxy = false,
        ServerCertificateCustomValidationCallback = (_, certificate, _, errors) =>
            AgentTlsCertificatePinPolicy.Validate(pin, certificate, errors)
    };

    private static async Task<byte[]> ReadBoundedAsync(HttpContent content, CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength is > MaximumResponseBytes)
            throw new InvalidDataException("Desktop Hub task response is too large.");
        await using var stream = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var chunk = new byte[4096];
        try
        {
            while (true)
            {
                var read = await stream.ReadAsync(chunk, cancellationToken).ConfigureAwait(false);
                if (read == 0) break;
                if (buffer.Length + read > MaximumResponseBytes)
                    throw new InvalidDataException("Desktop Hub task response is too large.");
                buffer.Write(chunk, 0, read);
            }
            return buffer.ToArray();
        }
        finally { CryptographicOperations.ZeroMemory(chunk); }
    }
}
