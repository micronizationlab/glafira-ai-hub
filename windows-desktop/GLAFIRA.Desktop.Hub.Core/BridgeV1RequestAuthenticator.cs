using System.Security.Cryptography;
using System.Text;

namespace GLAFIRA.Desktop.Hub.Core;

public enum BridgeV1Operation
{
    PROJECT_READ,
    CONVERSATION_READ,
    CONVERSATION_APPEND,
    APPROVAL_REQUEST,
    OWNERSHIP_TRANSFER_READ,
    DESKTOP_TASK_CLAIM,
    DESKTOP_TASK_RESULT,
    DESKTOP_TASK_ARTIFACT,
    DESKTOP_AGENT_HEARTBEAT
}

public sealed record BridgeV1SignedRequest(
    int ProtocolVersion,
    string RequestId,
    string IdempotencyKey,
    string DeviceId,
    string ProjectId,
    BridgeV1Operation Operation,
    long IssuedAtEpochMs,
    long ExpiresAtEpochMs,
    IReadOnlyDictionary<string, string> Payload,
    string SignatureHex);

public enum BridgeV1AdmissionFailure
{
    None,
    InvalidEnvelope,
    CredentialNotFound,
    DeviceMismatch,
    RequestExpired,
    TimestampOutsideWindow,
    ScopeDenied,
    SignatureInvalid,
    ReplayDetected
}

public sealed record BridgeV1AdmissionResult(bool Accepted, BridgeV1AdmissionFailure Failure)
{
    public static BridgeV1AdmissionResult Allow() => new(true, BridgeV1AdmissionFailure.None);
    public static BridgeV1AdmissionResult Deny(BridgeV1AdmissionFailure failure) => new(false, failure);
}

/// <summary>
/// Authenticates the actual Kotlin/Swift Bridge v1 request envelope.
/// Canonicalization intentionally matches contracts/v1/fixtures/bridge-hmac-unicode.json.
/// Replay claims use the signed idempotency key and are durable through IHubReplayGuard.
/// </summary>
public sealed class BridgeV1RequestAuthenticator(
    IHubCredentialResolver credentials,
    IHubReplayGuard replayGuard)
{
    private static readonly TimeSpan MaxTtl = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan MaxFutureSkew = TimeSpan.FromSeconds(30);

    private static readonly IReadOnlyDictionary<BridgeV1Operation, string> RequiredScopes =
        new Dictionary<BridgeV1Operation, string>
        {
            [BridgeV1Operation.PROJECT_READ] = "project.read",
            [BridgeV1Operation.CONVERSATION_READ] = "conversation.read",
            [BridgeV1Operation.CONVERSATION_APPEND] = "conversation.append",
            [BridgeV1Operation.APPROVAL_REQUEST] = "approval.request",
            [BridgeV1Operation.OWNERSHIP_TRANSFER_READ] = "ownership.read",
            [BridgeV1Operation.DESKTOP_TASK_CLAIM] = "desktop.task.claim",
            [BridgeV1Operation.DESKTOP_TASK_RESULT] = "desktop.task.result",
            [BridgeV1Operation.DESKTOP_TASK_ARTIFACT] = "desktop.task.result",
            [BridgeV1Operation.DESKTOP_AGENT_HEARTBEAT] = "desktop.task.claim"
        };

    private static readonly IReadOnlyDictionary<BridgeV1Operation, IReadOnlySet<string>> PayloadFields =
        new Dictionary<BridgeV1Operation, IReadOnlySet<string>>
        {
            [BridgeV1Operation.PROJECT_READ] = new HashSet<string>(StringComparer.Ordinal),
            [BridgeV1Operation.CONVERSATION_READ] = new HashSet<string>(["conversationId"], StringComparer.Ordinal),
            [BridgeV1Operation.CONVERSATION_APPEND] = new HashSet<string>(["conversationId", "messageText"], StringComparer.Ordinal),
            [BridgeV1Operation.APPROVAL_REQUEST] = new HashSet<string>(["toolId", "actionSummary"], StringComparer.Ordinal),
            [BridgeV1Operation.OWNERSHIP_TRANSFER_READ] = new HashSet<string>(["transferId"], StringComparer.Ordinal),
            [BridgeV1Operation.DESKTOP_TASK_CLAIM] = new HashSet<string>(["capabilities", "maxParallelTasks", "taskTypes"], StringComparer.Ordinal),
            [BridgeV1Operation.DESKTOP_TASK_RESULT] = new HashSet<string>(
                ["taskId", "ownerCoreId", "ownerGeneration", "leaseId", "fencingToken", "state", "progressPercent", "progressMessage", "result", "errorCode"],
                StringComparer.Ordinal),
            [BridgeV1Operation.DESKTOP_TASK_ARTIFACT] = new HashSet<string>(
                ["contractVersion", "artifactId", "taskId", "ownerCoreId", "ownerGeneration", "leaseId", "fencingToken", "schemaId", "schemaVersion", "contentType", "byteLength", "sha256", "redactionStatus", "createdAtEpochMs", "payloadBase64"],
                StringComparer.Ordinal),
            [BridgeV1Operation.DESKTOP_AGENT_HEARTBEAT] = new HashSet<string>(StringComparer.Ordinal)
        };

    public async ValueTask<BridgeV1AdmissionResult> AuthenticateAsync(
        BridgeV1SignedRequest request,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        var validation = ValidateEnvelope(request, now);
        if (validation != BridgeV1AdmissionFailure.None)
            return BridgeV1AdmissionResult.Deny(validation);

        var credential = await credentials.ResolveAsync(request.DeviceId, cancellationToken);
        if (credential is null || credential.HmacSecret.Length != 32)
            return BridgeV1AdmissionResult.Deny(BridgeV1AdmissionFailure.CredentialNotFound);
        if (!StringComparer.Ordinal.Equals(credential.DeviceId, request.DeviceId))
            return BridgeV1AdmissionResult.Deny(BridgeV1AdmissionFailure.DeviceMismatch);

        var requiredScope = RequiredScopes[request.Operation];
        if (!credential.AllowedScopes.Contains(requiredScope))
            return BridgeV1AdmissionResult.Deny(BridgeV1AdmissionFailure.ScopeDenied);

        byte[] supplied;
        try
        {
            supplied = Convert.FromHexString(request.SignatureHex);
        }
        catch (FormatException)
        {
            return BridgeV1AdmissionResult.Deny(BridgeV1AdmissionFailure.InvalidEnvelope);
        }

        var canonical = Encoding.UTF8.GetBytes(BuildCanonicalRequest(request));
        var expected = HMACSHA256.HashData(credential.HmacSecret, canonical);
        var signatureValid = supplied.Length == expected.Length &&
            CryptographicOperations.FixedTimeEquals(supplied, expected);
        CryptographicOperations.ZeroMemory(supplied);
        CryptographicOperations.ZeroMemory(expected);
        CryptographicOperations.ZeroMemory(canonical);
        if (!signatureValid)
            return BridgeV1AdmissionResult.Deny(BridgeV1AdmissionFailure.SignatureInvalid);

        var expiresAt = DateTimeOffset.FromUnixTimeMilliseconds(request.ExpiresAtEpochMs);
        // RequestId is the one-shot transport nonce. IdempotencyKey is the stable
        // operation identity: retries after a lost ACK use a fresh RequestId but retain
        // the same IdempotencyKey so the durable dispatcher can return the prior commit.
        var requestIdBytes = Encoding.UTF8.GetBytes(request.RequestId);
        var replayKeyBytes = SHA256.HashData(requestIdBytes);
        var replayNonce = Convert.ToHexString(replayKeyBytes).ToLowerInvariant();
        CryptographicOperations.ZeroMemory(requestIdBytes);
        CryptographicOperations.ZeroMemory(replayKeyBytes);
        if (!await replayGuard.TryClaimAsync(
                request.DeviceId, replayNonce, expiresAt, cancellationToken))
            return BridgeV1AdmissionResult.Deny(BridgeV1AdmissionFailure.ReplayDetected);

        return BridgeV1AdmissionResult.Allow();
    }

    public static string BuildCanonicalRequest(BridgeV1SignedRequest request)
    {
        var fields = new List<string>
        {
            request.ProtocolVersion.ToString(System.Globalization.CultureInfo.InvariantCulture),
            request.RequestId,
            request.IdempotencyKey,
            request.DeviceId,
            request.ProjectId,
            request.Operation.ToString(),
            request.IssuedAtEpochMs.ToString(System.Globalization.CultureInfo.InvariantCulture),
            request.ExpiresAtEpochMs.ToString(System.Globalization.CultureInfo.InvariantCulture)
        };
        foreach (var item in request.Payload.OrderBy(item => item.Key, StringComparer.Ordinal))
        {
            fields.Add(item.Key);
            fields.Add(item.Value);
        }

        // .NET string.Length counts UTF-16 code units, matching Kotlin String.length.
        return string.Concat(fields.Select(field => field.Length.ToString(
            System.Globalization.CultureInfo.InvariantCulture) + ":" + field));
    }

    private static BridgeV1AdmissionFailure ValidateEnvelope(
        BridgeV1SignedRequest request,
        DateTimeOffset now)
    {
        if (request.ProtocolVersion != 1 ||
            !IsIdentifier(request.RequestId) ||
            !IsIdentifier(request.IdempotencyKey) ||
            !IsIdentifier(request.DeviceId) ||
            !(request.ProjectId == "default" || IsIdentifier(request.ProjectId)) ||
            request.SignatureHex is not { Length: 64 } ||
            request.SignatureHex.Any(character => !Uri.IsHexDigit(character)) ||
            request.Payload is null ||
            !PayloadFields.TryGetValue(request.Operation, out var allowed))
            return BridgeV1AdmissionFailure.InvalidEnvelope;

        if (request.IssuedAtEpochMs <= 0 || request.ExpiresAtEpochMs <= request.IssuedAtEpochMs)
            return BridgeV1AdmissionFailure.InvalidEnvelope;

        DateTimeOffset issuedAt;
        DateTimeOffset expiresAt;
        try
        {
            issuedAt = DateTimeOffset.FromUnixTimeMilliseconds(request.IssuedAtEpochMs);
            expiresAt = DateTimeOffset.FromUnixTimeMilliseconds(request.ExpiresAtEpochMs);
        }
        catch (ArgumentOutOfRangeException)
        {
            return BridgeV1AdmissionFailure.InvalidEnvelope;
        }

        if (expiresAt - issuedAt > MaxTtl)
            return BridgeV1AdmissionFailure.InvalidEnvelope;
        if (issuedAt > now.Add(MaxFutureSkew))
            return BridgeV1AdmissionFailure.TimestampOutsideWindow;
        if (expiresAt <= now)
            return BridgeV1AdmissionFailure.RequestExpired;

        var maxValueLength = request.Operation == BridgeV1Operation.DESKTOP_TASK_ARTIFACT ? 44_000 : 16_384;
        var maxPayloadLength = request.Operation == BridgeV1Operation.DESKTOP_TASK_ARTIFACT ? 65_536 : 16_384;
        if (request.Payload.Count > 16 ||
            request.Payload.Any(item =>
                string.IsNullOrWhiteSpace(item.Key) ||
                item.Key.Length > 64 ||
                string.IsNullOrWhiteSpace(item.Value) ||
                item.Value.Length > maxValueLength ||
                (!allowed.Contains(item.Key) && !(request.Operation == BridgeV1Operation.DESKTOP_TASK_RESULT && item.Key == "resultEncoding"))) ||
            request.Payload.Sum(item => (long)item.Key.Length + item.Value.Length) > maxPayloadLength)
            return BridgeV1AdmissionFailure.InvalidEnvelope;

        if (!allowed.All(request.Payload.ContainsKey))
            return BridgeV1AdmissionFailure.InvalidEnvelope;

        if (request.Operation == BridgeV1Operation.DESKTOP_TASK_RESULT)
        {
            if (request.Payload.TryGetValue("resultEncoding", out var encoding) && encoding != "json")
                return BridgeV1AdmissionFailure.InvalidEnvelope;
            if (!IsTaskIdentifier(request.Payload["taskId"]) ||
                !IsIdentifier(request.Payload["ownerCoreId"]) ||
                !long.TryParse(request.Payload["ownerGeneration"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var ownerGeneration) ||
                ownerGeneration <= 0 ||
                !IsIdentifier(request.Payload["leaseId"]) ||
                !long.TryParse(request.Payload["fencingToken"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var fencingToken) ||
                fencingToken <= 0 ||
                !int.TryParse(request.Payload["progressPercent"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var progress) ||
                progress is < 0 or > 100 ||
                request.Payload["state"] is not ("ACCEPTED" or "RUNNING" or "SUCCEEDED" or "FAILED"))
                return BridgeV1AdmissionFailure.InvalidEnvelope;
        }
        else if (request.Operation == BridgeV1Operation.DESKTOP_TASK_ARTIFACT)
        {
            if (!int.TryParse(request.Payload["contractVersion"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var contractVersion) ||
                contractVersion != 1 ||
                !IsArtifactIdentifier(request.Payload["artifactId"]) ||
                !IsTaskIdentifier(request.Payload["taskId"]) ||
                !IsIdentifier(request.Payload["ownerCoreId"]) ||
                !long.TryParse(request.Payload["ownerGeneration"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var ownerGeneration) ||
                ownerGeneration <= 0 ||
                !IsIdentifier(request.Payload["leaseId"]) ||
                !long.TryParse(request.Payload["fencingToken"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var fencingToken) ||
                fencingToken <= 0 ||
                !int.TryParse(request.Payload["schemaVersion"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var schemaVersion) ||
                schemaVersion != 1 ||
                !int.TryParse(request.Payload["byteLength"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var byteLength) ||
                byteLength is < 1 or > 32_768 ||
                !IsLowerHexSha256(request.Payload["sha256"]) ||
                !long.TryParse(request.Payload["createdAtEpochMs"],
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var createdAt) ||
                createdAt <= 0)
                return BridgeV1AdmissionFailure.InvalidEnvelope;
        }
        else if (request.Operation == BridgeV1Operation.OWNERSHIP_TRANSFER_READ &&
                 !IsIdentifier(request.Payload["transferId"]))
        {
            return BridgeV1AdmissionFailure.InvalidEnvelope;
        }

        return BridgeV1AdmissionFailure.None;
    }

    private static bool IsTaskIdentifier(string value) =>
        value is { Length: >= 1 and <= 64 } &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_');

    private static bool IsArtifactIdentifier(string value) =>
        value is { Length: >= 1 and <= 128 } &&
        char.IsAsciiLetterOrDigit(value[0]) &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.' or ':');

    private static bool IsLowerHexSha256(string value) =>
        value is { Length: 64 } &&
        value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static bool IsIdentifier(string value) =>
        value is { Length: >= 8 and <= 128 } &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.');
}
