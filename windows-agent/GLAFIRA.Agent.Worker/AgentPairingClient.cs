using System.Net;
using System.Net.Http.Headers;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GLAFIRA.Agent.Worker;

public sealed record AgentPairingAttempt(
    Uri Endpoint,
    string ExpectedTlsCertificateSha256,
    string TicketId,
    string PairingCode,
    string AgentId);

/// <summary>
/// Redeems an owner-created pairing ticket only through a TLS connection whose
/// certificate matches the out-of-band Hub pin. A response is persisted only
/// after the complete credential bundle has passed strict validation.
/// </summary>
public sealed class AgentPairingClient
{
    private const int MaximumResponseBytes = 16 * 1024;
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(15);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };

    private sealed record PairingRequest(
        int Version,
        string TicketId,
        string PairingCode,
        string AgentId);

    private sealed record PairingResponse(
        int Version,
        string Status,
        string? ErrorCode,
        string? AgentId,
        string? DesktopHubId,
        string? Endpoint,
        string? TlsCertificateSha256,
        string? SharedSecretBase64,
        string[]? Scopes,
        long? EnrolledAtEpochMs,
        long? ExpiresAtEpochMs);

    private readonly IAgentCredentialStore _credentialStore;
    private readonly TimeProvider _timeProvider;
    private readonly Func<string, HttpMessageHandler> _handlerFactory;

    public AgentPairingClient(
        IAgentCredentialStore credentialStore,
        TimeProvider? timeProvider = null)
        : this(credentialStore, timeProvider, CreatePinnedHandler)
    {
    }

    internal AgentPairingClient(
        IAgentCredentialStore credentialStore,
        TimeProvider? timeProvider,
        Func<string, HttpMessageHandler> handlerFactory)
    {
        _credentialStore = credentialStore ?? throw new ArgumentNullException(nameof(credentialStore));
        _timeProvider = timeProvider ?? TimeProvider.System;
        _handlerFactory = handlerFactory ?? throw new ArgumentNullException(nameof(handlerFactory));
    }

    public async Task<AgentEnrollmentCredential> EnrollAsync(
        AgentPairingAttempt attempt,
        CancellationToken cancellationToken = default)
    {
        ValidateAttempt(attempt);
        using var handler = _handlerFactory(attempt.ExpectedTlsCertificateSha256);
        using var client = new HttpClient(handler, disposeHandler: false)
        {
            Timeout = Timeout.InfiniteTimeSpan
        };
        using var request = new HttpRequestMessage(
            HttpMethod.Post, new Uri(attempt.Endpoint.AbsoluteUri + "/pair"));
        var requestBytes = JsonSerializer.SerializeToUtf8Bytes(
            new PairingRequest(1, attempt.TicketId, attempt.PairingCode, attempt.AgentId),
            JsonOptions);
        request.Content = new ByteArrayContent(requestBytes);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json")
        {
            CharSet = "utf-8"
        };
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(RequestTimeout);
        byte[] responseBytes;
        try
        {
            using var response = await client.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK)
                throw new InvalidDataException("Desktop Hub rejected the pairing request.");
            if (response.Content.Headers.ContentType?.MediaType is not "application/json")
                throw new InvalidDataException("Desktop Hub pairing response is not JSON.");
            responseBytes = await ReadBoundedAsync(response.Content, timeout.Token).ConfigureAwait(false);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(requestBytes);
        }

        try
        {
            PairingResponse response;
            try
            {
                response = JsonSerializer.Deserialize<PairingResponse>(responseBytes, JsonOptions)
                    ?? throw new InvalidDataException("Desktop Hub pairing response is empty.");
            }
            catch (JsonException error)
            {
                throw new InvalidDataException("Desktop Hub pairing response is invalid.", error);
            }

            var credential = ValidateResponse(attempt, response);
            await _credentialStore.SaveAsync(credential, cancellationToken).ConfigureAwait(false);
            return credential;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(responseBytes);
        }
    }

    private AgentEnrollmentCredential ValidateResponse(
        AgentPairingAttempt attempt,
        PairingResponse response)
    {
        if (response.Version != 1 || response.Status != "ENROLLED" || response.ErrorCode is not null ||
            response.AgentId != attempt.AgentId || response.DesktopHubId is null ||
            response.Endpoint is null || response.TlsCertificateSha256 != attempt.ExpectedTlsCertificateSha256 ||
            response.SharedSecretBase64 is null || response.Scopes is null ||
            response.EnrolledAtEpochMs is null || response.ExpiresAtEpochMs is null)
            throw new InvalidDataException("Desktop Hub pairing response does not match this enrollment attempt.");

        if (!Uri.TryCreate(response.Endpoint, UriKind.Absolute, out var responseEndpoint) ||
            responseEndpoint != attempt.Endpoint)
            throw new InvalidDataException("Desktop Hub pairing response changed the pinned endpoint.");
        if (response.Scopes.Length is < 1 or > 32 ||
            response.Scopes.Distinct(StringComparer.Ordinal).Count() != response.Scopes.Length ||
            !response.Scopes.SequenceEqual(response.Scopes.Order(StringComparer.Ordinal), StringComparer.Ordinal))
            throw new InvalidDataException("Desktop Hub pairing response contains non-canonical scopes.");

        DateTimeOffset enrolledAt;
        DateTimeOffset expiresAt;
        try
        {
            enrolledAt = DateTimeOffset.FromUnixTimeMilliseconds(response.EnrolledAtEpochMs.Value);
            expiresAt = DateTimeOffset.FromUnixTimeMilliseconds(response.ExpiresAtEpochMs.Value);
        }
        catch (ArgumentOutOfRangeException error)
        {
            throw new InvalidDataException("Desktop Hub pairing response contains invalid timestamps.", error);
        }

        var credential = new AgentEnrollmentCredential(
            response.AgentId,
            response.DesktopHubId,
            responseEndpoint,
            response.TlsCertificateSha256,
            response.SharedSecretBase64,
            new HashSet<string>(response.Scopes, StringComparer.Ordinal),
            enrolledAt,
            expiresAt);
        try
        {
            AgentEnrollmentCredentialPolicy.Validate(credential, _timeProvider.GetUtcNow());
        }
        catch (ArgumentException error)
        {
            throw new InvalidDataException("Desktop Hub returned an invalid enrollment credential.", error);
        }
        return credential;
    }

    private static void ValidateAttempt(AgentPairingAttempt attempt)
    {
        ArgumentNullException.ThrowIfNull(attempt);
        if (attempt.Endpoint is null || !attempt.Endpoint.IsAbsoluteUri ||
            attempt.Endpoint.Scheme != Uri.UriSchemeHttps ||
            attempt.Endpoint.AbsolutePath != "/bridge/v1" ||
            !string.IsNullOrEmpty(attempt.Endpoint.UserInfo) ||
            !string.IsNullOrEmpty(attempt.Endpoint.Query) ||
            !string.IsNullOrEmpty(attempt.Endpoint.Fragment) ||
            !IPAddress.TryParse(attempt.Endpoint.Host, out var address) || !IsPrivateLanAddress(address))
            throw new ArgumentException("Pairing requires the private-LAN HTTPS Bridge endpoint.", nameof(attempt));
        if (attempt.ExpectedTlsCertificateSha256 is not { Length: 64 } ||
            attempt.ExpectedTlsCertificateSha256.Any(character =>
                character is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new ArgumentException("Pairing requires a canonical TLS SHA-256 pin.", nameof(attempt));
        if (attempt.TicketId is not { Length: 32 } ||
            attempt.TicketId.Any(character => character is not (>= '0' and <= '9' or >= 'a' and <= 'f')) ||
            attempt.PairingCode is not { Length: 43 } ||
            attempt.PairingCode.Any(character => !char.IsAsciiLetterOrDigit(character) && character is not '-' and not '_') ||
            attempt.AgentId is not { Length: >= 1 and <= 128 } ||
            attempt.AgentId.Any(character => !char.IsAsciiLetterOrDigit(character) && character is not '-' and not '_'))
            throw new ArgumentException("Pairing ticket or Agent identity is invalid.", nameof(attempt));
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
            throw new InvalidDataException("Desktop Hub pairing response is too large.");
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
                    throw new InvalidDataException("Desktop Hub pairing response is too large.");
                buffer.Write(chunk, 0, read);
            }
            return buffer.ToArray();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(chunk);
        }
    }

    private static bool IsPrivateLanAddress(IPAddress address)
    {
        if (IPAddress.IsLoopback(address) || address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.IPv6Any) || address.IsIPv6Multicast ||
            address.IsIPv4MappedToIPv6)
            return false;
        var bytes = address.GetAddressBytes();
        if (address.AddressFamily == AddressFamily.InterNetwork)
            return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) ||
                (bytes[0] == 192 && bytes[1] == 168);
        return address.AddressFamily == AddressFamily.InterNetworkV6 && (bytes[0] & 0xFE) == 0xFC;
    }
}
