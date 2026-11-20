using System.Net;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record BridgeV1PairingRequest(
    int Version,
    string TicketId,
    string PairingCode,
    string AgentId);

public sealed record BridgeV1PairingResponse(
    int Version,
    string Status,
    string? ErrorCode,
    string? AgentId,
    string? DesktopHubId,
    string? Endpoint,
    string? TlsCertificateSha256,
    string? SharedSecretBase64,
    IReadOnlyList<string> Scopes,
    long? EnrolledAtEpochMs,
    long? ExpiresAtEpochMs)
{
    public static BridgeV1PairingResponse Reject() => new(
        1,
        "FAILED",
        "PAIRING_REJECTED",
        null,
        null,
        null,
        null,
        null,
        [],
        null,
        null);

    public static BridgeV1PairingResponse Enrolled(BridgeV1AgentEnrollmentCredential credential) => new(
        1,
        "ENROLLED",
        null,
        credential.AgentId,
        credential.DesktopHubId,
        credential.Endpoint.AbsoluteUri,
        credential.TlsCertificateSha256,
        credential.SharedSecretBase64,
        credential.Scopes.Order(StringComparer.Ordinal).ToArray(),
        credential.EnrolledAt.ToUnixTimeMilliseconds(),
        credential.ExpiresAt.ToUnixTimeMilliseconds());
}

/// <summary>
/// Narrow unauthenticated bootstrap surface. Possession of the owner-created
/// 256-bit pairing code is the only application credential accepted here. The
/// caller must pin the advertised Hub TLS certificate before sending that code.
/// Every denial is deliberately collapsed to PAIRING_REJECTED so the endpoint
/// does not disclose ticket existence, expiry, replay or Agent registration state.
/// </summary>
public sealed class BridgeV1PairingEndpoint
{
    private static readonly TimeSpan DefaultCredentialLifetime = TimeSpan.FromDays(90);
    private readonly BridgeV1PairingTicketStore _tickets;
    private readonly DesktopHubStore _hubStore;
    private readonly TimeProvider _timeProvider;
    private readonly string _desktopHubId;
    private readonly string _tlsCertificateSha256;
    private readonly TimeSpan _credentialLifetime;

    public BridgeV1PairingEndpoint(
        BridgeV1PairingTicketStore tickets,
        DesktopHubStore hubStore,
        string desktopHubId,
        string tlsCertificateSha256,
        TimeSpan? credentialLifetime = null,
        TimeProvider? timeProvider = null)
    {
        ArgumentNullException.ThrowIfNull(tickets);
        ArgumentNullException.ThrowIfNull(hubStore);
        ArgumentException.ThrowIfNullOrWhiteSpace(desktopHubId);
        ArgumentException.ThrowIfNullOrWhiteSpace(tlsCertificateSha256);
        if (desktopHubId.Length > 128 ||
            !desktopHubId.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_'))
            throw new ArgumentException("Desktop Hub ID must be a bounded ASCII identifier.", nameof(desktopHubId));
        if (tlsCertificateSha256.Length != 64 || tlsCertificateSha256.Any(character =>
                character is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new ArgumentException("TLS pin must be canonical lowercase SHA-256 hex.", nameof(tlsCertificateSha256));
        var lifetime = credentialLifetime ?? DefaultCredentialLifetime;
        if (lifetime < TimeSpan.FromHours(1) || lifetime > TimeSpan.FromDays(365))
            throw new ArgumentOutOfRangeException(nameof(credentialLifetime));
        _tickets = tickets;
        _hubStore = hubStore;
        _timeProvider = timeProvider ?? TimeProvider.System;
        _desktopHubId = desktopHubId;
        _tlsCertificateSha256 = tlsCertificateSha256;
        _credentialLifetime = lifetime;
    }

    public async ValueTask<BridgeV1PairingResponse> RedeemAsync(
        BridgeV1PairingRequest? request,
        Uri localEndpoint,
        CancellationToken cancellationToken = default)
    {
        if (request is null || request.Version != 1 ||
            string.IsNullOrWhiteSpace(request.TicketId) ||
            string.IsNullOrWhiteSpace(request.PairingCode) ||
            string.IsNullOrWhiteSpace(request.AgentId) ||
            localEndpoint is null || localEndpoint.Scheme != Uri.UriSchemeHttps ||
            localEndpoint.AbsolutePath != "/bridge/v1" ||
            !IPAddress.TryParse(localEndpoint.Host, out var localAddress) ||
            !BridgeV1LanAddressPolicy.IsPrivateLanAddress(localAddress))
            return BridgeV1PairingResponse.Reject();

        try
        {
            // Do not let a pairing ticket replace an already owner-approved Agent identity.
            if (_hubStore.IsAgentOwnerApproved(request.AgentId))
                return BridgeV1PairingResponse.Reject();

            var result = await _tickets.EnrollAgentAsync(
                request.TicketId,
                request.PairingCode,
                request.AgentId,
                _desktopHubId,
                localEndpoint,
                _tlsCertificateSha256,
                _credentialLifetime,
                cancellationToken).ConfigureAwait(false);
            if (!result.Accepted || result.Credential is null)
                return BridgeV1PairingResponse.Reject();

            // Enrollment is deliberately not approval. New devices enter the scheduler
            // with no capabilities and cannot claim tasks until the owner assigns scopes.
            try
            {
                _hubStore.RegisterPendingAgent(
                    request.AgentId, request.AgentId, _timeProvider.GetUtcNow());
            }
            catch
            {
                _hubStore.RevokeAgent(request.AgentId, _timeProvider.GetUtcNow());
                return BridgeV1PairingResponse.Reject();
            }
            return BridgeV1PairingResponse.Enrolled(result.Credential);
        }
        catch (ArgumentException)
        {
            return BridgeV1PairingResponse.Reject();
        }
    }
}
