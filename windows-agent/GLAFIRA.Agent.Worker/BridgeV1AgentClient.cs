using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Security;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GLAFIRA.Agent.Worker;

public sealed record AgentHeartbeatResult(
    bool Accepted,
    string? ErrorCode,
    string AgentId,
    IReadOnlySet<string> Capabilities,
    int MaxParallelTasks);

/// <summary>
/// Signs and sends the Windows Agent heartbeat using the same Bridge v1 canonical
/// envelope as Desktop Hub. TLS is pinned to the certificate delivered during pairing.
/// </summary>
public sealed class BridgeV1AgentClient
{
    private const int MaximumResponseBytes = 16 * 1024;
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan RequestLifetime = TimeSpan.FromMinutes(1);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };

    private sealed record SignedRequest(
        int ProtocolVersion,
        string RequestId,
        string IdempotencyKey,
        string DeviceId,
        string ProjectId,
        string Operation,
        long IssuedAtEpochMs,
        long ExpiresAtEpochMs,
        IReadOnlyDictionary<string, string> Payload,
        string SignatureHex);

    private sealed record Response(
        string Status,
        Dictionary<string, string>? Result,
        string? ErrorCode);

    private readonly TimeProvider _timeProvider;
    private readonly Func<string, HttpMessageHandler> _handlerFactory;

    public BridgeV1AgentClient(TimeProvider? timeProvider = null)
        : this(timeProvider, CreatePinnedHandler)
    {
    }

    internal BridgeV1AgentClient(
        TimeProvider? timeProvider,
        Func<string, HttpMessageHandler> handlerFactory)
    {
        _timeProvider = timeProvider ?? TimeProvider.System;
        _handlerFactory = handlerFactory ?? throw new ArgumentNullException(nameof(handlerFactory));
    }

    public async Task<AgentHeartbeatResult> SendHeartbeatAsync(
        AgentEnrollmentCredential credential,
        CancellationToken cancellationToken = default)
    {
        var now = _timeProvider.GetUtcNow();
        AgentEnrollmentCredentialPolicy.Validate(credential, now);
        if (!credential.Scopes.Contains("desktop.task.claim"))
            throw new InvalidDataException("Agent enrollment does not grant the heartbeat scope.");

        var issuedAt = now.ToUnixTimeMilliseconds();
        var expiresAt = now.Add(RequestLifetime).ToUnixTimeMilliseconds();
        var requestId = Guid.NewGuid().ToString("N");
        var idempotencyKey = Guid.NewGuid().ToString("N");
        var payload = new Dictionary<string, string>(StringComparer.Ordinal);
        var canonical = BuildCanonicalRequest(
            requestId, idempotencyKey, credential.AgentId, issuedAt, expiresAt, payload);
        var canonicalBytes = Encoding.UTF8.GetBytes(canonical);
        var secret = Convert.FromBase64String(credential.SharedSecretBase64);
        byte[] signature;
        try
        {
            if (secret.Length != 32)
                throw new InvalidDataException("Agent enrollment secret must contain 256 bits.");
            signature = HMACSHA256.HashData(secret, canonicalBytes);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(secret);
            CryptographicOperations.ZeroMemory(canonicalBytes);
        }

        var signedRequest = new SignedRequest(
            1,
            requestId,
            idempotencyKey,
            credential.AgentId,
            "default",
            "DESKTOP_AGENT_HEARTBEAT",
            issuedAt,
            expiresAt,
            payload,
            Convert.ToHexString(signature).ToLowerInvariant());
        CryptographicOperations.ZeroMemory(signature);

        var requestBytes = JsonSerializer.SerializeToUtf8Bytes(signedRequest, JsonOptions);
        try
        {
            using var handler = _handlerFactory(credential.TlsCertificateSha256);
            using var client = new HttpClient(handler, disposeHandler: false)
            {
                Timeout = Timeout.InfiniteTimeSpan
            };
            using var request = new HttpRequestMessage(HttpMethod.Post, credential.Endpoint);
            request.Content = new ByteArrayContent(requestBytes);
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json")
            {
                CharSet = "utf-8"
            };
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(RequestTimeout);
            using var httpResponse = await client.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
            if (httpResponse.Content.Headers.ContentType?.MediaType is not "application/json")
                throw new InvalidDataException("Desktop Hub heartbeat response is not JSON.");
            var responseBytes = await ReadBoundedAsync(httpResponse.Content, timeout.Token).ConfigureAwait(false);
            try
            {
                Response response;
                try
                {
                    response = JsonSerializer.Deserialize<Response>(responseBytes, JsonOptions)
                        ?? throw new InvalidDataException("Desktop Hub heartbeat response is empty.");
                }
                catch (JsonException error)
                {
                    throw new InvalidDataException("Desktop Hub heartbeat response is invalid.", error);
                }

                if (httpResponse.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.Conflict or HttpStatusCode.Unauthorized))
                    throw new HttpRequestException($"Desktop Hub heartbeat returned HTTP {(int)httpResponse.StatusCode}.");

                if (response.Status != "ACCEPTED" || response.Result is null)
                    return new AgentHeartbeatResult(
                        false, response.ErrorCode ?? "HEARTBEAT_REJECTED",
                        credential.AgentId, new HashSet<string>(StringComparer.Ordinal), 0);

                var result = response.Result;
                if (result.GetValueOrDefault("agentId") != credential.AgentId ||
                    result.GetValueOrDefault("ownerApproved") != "true" ||
                    !result.TryGetValue("capabilities", out var capabilitiesJson) ||
                    !result.TryGetValue("maxParallelTasks", out var parallelText) ||
                    !int.TryParse(parallelText, NumberStyles.None, CultureInfo.InvariantCulture, out var parallel) ||
                    parallel is < 1 or > 16)
                    throw new InvalidDataException("Desktop Hub heartbeat profile is incomplete or mismatched.");

                string[] capabilities;
                try
                {
                    capabilities = JsonSerializer.Deserialize<string[]>(capabilitiesJson, JsonOptions) ?? [];
                }
                catch (JsonException error)
                {
                    throw new InvalidDataException("Desktop Hub heartbeat capabilities are invalid.", error);
                }
                if (capabilities.Length is < 1 or > 16 ||
                    capabilities.Any(string.IsNullOrWhiteSpace) ||
                    capabilities.Distinct(StringComparer.Ordinal).Count() != capabilities.Length ||
                    !capabilities.SequenceEqual(capabilities.Order(StringComparer.Ordinal), StringComparer.Ordinal))
                    throw new InvalidDataException("Desktop Hub heartbeat capabilities are not canonical.");

                return new AgentHeartbeatResult(
                    true, null, credential.AgentId,
                    new HashSet<string>(capabilities, StringComparer.Ordinal), parallel);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(responseBytes);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(requestBytes);
        }
    }

    private static string BuildCanonicalRequest(
        string requestId,
        string idempotencyKey,
        string deviceId,
        long issuedAt,
        long expiresAt,
        IReadOnlyDictionary<string, string> payload)
    {
        var fields = new List<string>
        {
            "1",
            requestId,
            idempotencyKey,
            deviceId,
            "default",
            "DESKTOP_AGENT_HEARTBEAT",
            issuedAt.ToString(CultureInfo.InvariantCulture),
            expiresAt.ToString(CultureInfo.InvariantCulture)
        };
        foreach (var item in payload.OrderBy(item => item.Key, StringComparer.Ordinal))
        {
            fields.Add(item.Key);
            fields.Add(item.Value);
        }
        return string.Concat(fields.Select(field =>
            field.Length.ToString(CultureInfo.InvariantCulture) + ":" + field));
    }

    private static HttpMessageHandler CreatePinnedHandler(string expectedPin) => new HttpClientHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        UseProxy = false,
        ServerCertificateCustomValidationCallback = (_, certificate, _, errors) =>
            AgentTlsCertificatePinPolicy.Validate(expectedPin, certificate, errors)
    };

    private static async Task<byte[]> ReadBoundedAsync(
        HttpContent content,
        CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength is > MaximumResponseBytes)
            throw new InvalidDataException("Desktop Hub heartbeat response is too large.");
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
                    throw new InvalidDataException("Desktop Hub heartbeat response is too large.");
                buffer.Write(chunk, 0, read);
            }
            return buffer.ToArray();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(chunk);
        }
    }
}
