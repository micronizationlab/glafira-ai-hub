using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record BridgeV1HttpsHostOptions(
    string HubId,
    IReadOnlyList<IPAddress> BindAddresses,
    int Port = 8443)
{
    public void Validate()
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(HubId);
        if (HubId.Length is < 8 or > 128 ||
            HubId.Any(character => !(char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.')))
            throw new ArgumentException("Hub ID must be a stable 8–128 character identifier.", nameof(HubId));
        ArgumentNullException.ThrowIfNull(BindAddresses);
        if (BindAddresses.Count is < 1 or > 8 ||
            BindAddresses.Distinct().Count() != BindAddresses.Count ||
            BindAddresses.Any(address => !BridgeV1LanAddressPolicy.IsPrivateLanAddress(address)))
            throw new ArgumentException("Bridge host must bind only to unique private LAN addresses.", nameof(BindAddresses));
        if (Port is < 1024 or > 65535)
            throw new ArgumentOutOfRangeException(nameof(Port), "Bridge HTTPS port must be between 1024 and 65535.");
    }
}

/// <summary>
/// Explicitly-started, HTTPS-only Windows Desktop Bridge endpoint. It accepts only the
/// authenticated typed Bridge v1 envelope; it exposes no arbitrary HTTP proxy or shell route.
/// </summary>
public sealed class BridgeV1HttpsHost : IAsyncDisposable
{
    private const long MaxRequestBytes = 64 * 1024;
    private const long MaxPairingRequestBytes = 8 * 1024;
    private static readonly JsonSerializerOptions JsonOptions = CreateJsonOptions();

    private readonly BridgeV1HttpsHostOptions _options;
    private readonly BridgeV1TlsCertificateIdentity _tlsIdentity;
    private readonly BridgeV1RequestAuthenticator _authenticator;
    private readonly BridgeV1TaskOperationDispatcher _dispatcher;
    private readonly BridgeV1PairingEndpoint _pairingEndpoint;
    private readonly TimeProvider _timeProvider;
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private readonly SemaphoreSlim _pairingRequests = new(4, 4);
    private WebApplication? _application;
    private bool _disposed;

    public BridgeV1HttpsHost(
        BridgeV1HttpsHostOptions options,
        BridgeV1TlsCertificateIdentity tlsIdentity,
        BridgeV1RequestAuthenticator authenticator,
        BridgeV1TaskOperationDispatcher dispatcher,
        BridgeV1PairingEndpoint pairingEndpoint,
        TimeProvider? timeProvider = null)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentNullException.ThrowIfNull(tlsIdentity);
        ArgumentNullException.ThrowIfNull(authenticator);
        ArgumentNullException.ThrowIfNull(dispatcher);
        ArgumentNullException.ThrowIfNull(pairingEndpoint);
        options.Validate();

        var expectedAddresses = options.BindAddresses
            .OrderBy(address => address.ToString(), StringComparer.Ordinal).ToArray();
        var identityAddresses = tlsIdentity.BindAddresses
            .OrderBy(address => address.ToString(), StringComparer.Ordinal).ToArray();
        if (!StringComparer.Ordinal.Equals(options.HubId, tlsIdentity.HubId) ||
            !expectedAddresses.SequenceEqual(identityAddresses))
            throw new ArgumentException("TLS identity does not match the configured Hub ID and bind addresses.", nameof(tlsIdentity));
        if (!tlsIdentity.Certificate.HasPrivateKey ||
            tlsIdentity.Certificate.NotBefore > DateTimeOffset.UtcNow.AddMinutes(5) ||
            tlsIdentity.Certificate.NotAfter <= DateTimeOffset.UtcNow)
            throw new ArgumentException("A currently valid TLS certificate with a private key is required.", nameof(tlsIdentity));

        var actualFingerprint = Convert.ToHexString(SHA256.HashData(tlsIdentity.Certificate.RawData)).ToLowerInvariant();
        if (!StringComparer.Ordinal.Equals(actualFingerprint, tlsIdentity.FingerprintSha256))
            throw new ArgumentException("TLS certificate fingerprint does not match its identity record.", nameof(tlsIdentity));

        _options = options;
        _tlsIdentity = tlsIdentity;
        _authenticator = authenticator;
        _dispatcher = dispatcher;
        _pairingEndpoint = pairingEndpoint;
        _timeProvider = timeProvider ?? TimeProvider.System;
    }

    public bool IsRunning => _application is not null;
    public string HubId => _options.HubId;
    public string CertificateFingerprintSha256 => _tlsIdentity.FingerprintSha256;
    public IReadOnlyList<Uri> Endpoints => _options.BindAddresses
        .Select(address => new Uri($"https://{FormatHost(address)}:{_options.Port}/bridge/v1"))
        .ToArray();

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_application is not null)
                throw new InvalidOperationException("BRIDGE_HOST_ALREADY_RUNNING");

            var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions
            {
                EnvironmentName = Environments.Production
            });
            builder.Logging.ClearProviders();
            builder.WebHost.ConfigureKestrel(server =>
            {
                server.AddServerHeader = false;
                server.Limits.MaxRequestBodySize = MaxRequestBytes;
                server.Limits.MaxConcurrentConnections = 32;
                server.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
                server.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(30);
                foreach (var address in _options.BindAddresses)
                {
                    server.Listen(address, _options.Port, listen => listen.UseHttps(_tlsIdentity.Certificate));
                }
            });

            var application = builder.Build();
            application.MapPost("/bridge/v1", HandleRequestAsync);
            application.MapPost("/bridge/v1/pair", HandlePairingAsync);
            try
            {
                await application.StartAsync(cancellationToken).ConfigureAwait(false);
                _application = application;
            }
            catch
            {
                await application.DisposeAsync().ConfigureAwait(false);
                throw;
            }
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    private async Task HandlePairingAsync(HttpContext context)
    {
        SetNoStoreHeaders(context);
        if (!HttpMethods.IsPost(context.Request.Method) ||
            !StringComparer.OrdinalIgnoreCase.Equals(context.Request.ContentType?.Split(';')[0].Trim(), "application/json"))
        {
            context.Response.StatusCode = StatusCodes.Status415UnsupportedMediaType;
            return;
        }
        if (context.Request.ContentLength is > MaxPairingRequestBytes)
        {
            context.Response.StatusCode = StatusCodes.Status413PayloadTooLarge;
            return;
        }

        var bodySize = context.Features.Get<IHttpMaxRequestBodySizeFeature>();
        if (bodySize is { IsReadOnly: false }) bodySize.MaxRequestBodySize = MaxPairingRequestBytes;
        if (!await _pairingRequests.WaitAsync(0, context.RequestAborted).ConfigureAwait(false))
        {
            context.Response.StatusCode = StatusCodes.Status429TooManyRequests;
            return;
        }

        try
        {
            BridgeV1PairingRequest? request;
            try
            {
                request = await JsonSerializer.DeserializeAsync<BridgeV1PairingRequest>(
                    context.Request.Body, JsonOptions, context.RequestAborted).ConfigureAwait(false);
            }
            catch (Microsoft.AspNetCore.Http.BadHttpRequestException error)
                when (error.StatusCode == StatusCodes.Status413PayloadTooLarge)
            {
                context.Response.StatusCode = StatusCodes.Status413PayloadTooLarge;
                return;
            }
            catch (JsonException)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                await context.Response.WriteAsJsonAsync(
                    BridgeV1PairingResponse.Reject(), JsonOptions, context.RequestAborted).ConfigureAwait(false);
                return;
            }

            var localAddress = context.Connection.LocalIpAddress;
            if (localAddress is null || !BridgeV1LanAddressPolicy.IsPrivateLanAddress(localAddress) ||
                !_options.BindAddresses.Contains(localAddress))
            {
                context.Response.StatusCode = StatusCodes.Status500InternalServerError;
                return;
            }
            var localEndpoint = new Uri(
                $"https://{FormatHost(localAddress)}:{_options.Port}/bridge/v1");
            BridgeV1PairingResponse response;
            try
            {
                response = await _pairingEndpoint.RedeemAsync(
                    request, localEndpoint, context.RequestAborted).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception)
            {
                context.Response.StatusCode = StatusCodes.Status500InternalServerError;
                await context.Response.WriteAsJsonAsync(
                    new { status = "FAILED", errorCode = "PAIRING_SERVICE_FAILURE" },
                    JsonOptions, context.RequestAborted).ConfigureAwait(false);
                return;
            }
            context.Response.StatusCode = response.Status == "ENROLLED"
                ? StatusCodes.Status200OK
                : StatusCodes.Status401Unauthorized;
            await context.Response.WriteAsJsonAsync(response, JsonOptions, context.RequestAborted)
                .ConfigureAwait(false);
        }
        finally
        {
            _pairingRequests.Release();
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var application = _application;
            _application = null;
            if (application is not null)
            {
                await application.StopAsync(cancellationToken).ConfigureAwait(false);
                await application.DisposeAsync().ConfigureAwait(false);
            }
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    private async Task HandleRequestAsync(HttpContext context)
    {
        SetNoStoreHeaders(context);
        if (!HttpMethods.IsPost(context.Request.Method) ||
            !StringComparer.OrdinalIgnoreCase.Equals(context.Request.ContentType?.Split(';')[0].Trim(), "application/json"))
        {
            context.Response.StatusCode = StatusCodes.Status415UnsupportedMediaType;
            return;
        }
        if (context.Request.ContentLength is > MaxRequestBytes)
        {
            context.Response.StatusCode = StatusCodes.Status413PayloadTooLarge;
            return;
        }

        BridgeV1SignedRequest? request;
        try
        {
            request = await JsonSerializer.DeserializeAsync<BridgeV1SignedRequest>(
                context.Request.Body, JsonOptions, context.RequestAborted).ConfigureAwait(false);
        }
        catch (JsonException)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteAsJsonAsync(
                new { status = "FAILED", errorCode = "INVALID_BRIDGE_ENVELOPE" },
                JsonOptions, context.RequestAborted).ConfigureAwait(false);
            return;
        }

        if (request is null)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteAsJsonAsync(
                new { status = "FAILED", errorCode = "INVALID_BRIDGE_ENVELOPE" },
                JsonOptions, context.RequestAborted).ConfigureAwait(false);
            return;
        }

        var now = _timeProvider.GetUtcNow();
        var admission = await _authenticator.AuthenticateAsync(
            request, now, context.RequestAborted).ConfigureAwait(false);
        if (!admission.Accepted)
        {
            context.Response.StatusCode = StatusCodes.Status401Unauthorized;
            await context.Response.WriteAsJsonAsync(
                new { status = "FAILED", errorCode = admission.Failure.ToString() },
                JsonOptions, context.RequestAborted).ConfigureAwait(false);
            return;
        }

        BridgeV1DispatchResponse response;
        try
        {
            response = await _dispatcher.DispatchAsync(
                request, now, context.RequestAborted).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            // Never return internal exception text, payloads, paths or credential details.
            context.Response.StatusCode = StatusCodes.Status500InternalServerError;
            await context.Response.WriteAsJsonAsync(
                new { status = "FAILED", errorCode = "HUB_DISPATCH_FAILURE" },
                JsonOptions, context.RequestAborted).ConfigureAwait(false);
            return;
        }

        context.Response.StatusCode = StringComparer.Ordinal.Equals(response.Status, "FAILED")
            ? StatusCodes.Status409Conflict
            : StatusCodes.Status200OK;
        await context.Response.WriteAsJsonAsync(response, JsonOptions, context.RequestAborted)
            .ConfigureAwait(false);
    }

    private static void SetNoStoreHeaders(HttpContext context)
    {
        context.Response.Headers["Cache-Control"] = "no-store, max-age=0";
        context.Response.Headers["Pragma"] = "no-cache";
        context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    }

    private static string FormatHost(IPAddress address) =>
        address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6
            ? $"[{address}]"
            : address.ToString();

    private static JsonSerializerOptions CreateJsonOptions()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web)
        {
            MaxDepth = 8
        };
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        await StopAsync().ConfigureAwait(false);
        _disposed = true;
        _lifecycle.Dispose();
        _pairingRequests.Dispose();
    }
}
