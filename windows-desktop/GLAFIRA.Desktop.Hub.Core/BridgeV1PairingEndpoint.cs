using System.Net;
using System.Security.Cryptography;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record BridgeV1PairingRequest(
    int Version,
    string TicketId,
    string PairingCode,
    string AgentId);

/// <summary>
/// Public ownership-signing identity distributed only inside the owner-created,
/// TLS-pinned pairing flow. It is not accepted from ownership-transfer responses.
/// </summary>
public sealed class BridgeV1OwnershipTrustAnchor
{
    public string OwnerCoreId { get; }
    public long SigningKeyEpoch { get; }
    public string SigningPublicKeySpkiBase64 { get; }
    public string SigningPublicKeySha256 { get; }

    public BridgeV1OwnershipTrustAnchor(
        string ownerCoreId,
        long signingKeyEpoch,
        string signingPublicKeySpkiBase64,
        string signingPublicKeySha256)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(ownerCoreId);
        ArgumentException.ThrowIfNullOrWhiteSpace(signingPublicKeySpkiBase64);
        ArgumentException.ThrowIfNullOrWhiteSpace(signingPublicKeySha256);
        if (ownerCoreId.Length is < 8 or > 128 ||
            !ownerCoreId.All(character =>
                char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.'))
            throw new ArgumentException("Owner Core ID must be a bounded ASCII identifier.", nameof(ownerCoreId));
        if (signingKeyEpoch <= 0)
            throw new ArgumentOutOfRangeException(nameof(signingKeyEpoch));
        if (signingPublicKeySha256.Length != 64 || signingPublicKeySha256.Any(character =>
                character is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new ArgumentException("Signing-key fingerprint must be canonical SHA-256 hex.",
                nameof(signingPublicKeySha256));

        byte[] publicKey;
        try { publicKey = Convert.FromBase64String(signingPublicKeySpkiBase64); }
        catch (FormatException ex)
        {
            throw new ArgumentException("Signing public key must be canonical Base64 SPKI.",
                nameof(signingPublicKeySpkiBase64), ex);
        }
        if (publicKey.Length is < 80 or > 256 ||
            !StringComparer.Ordinal.Equals(Convert.ToBase64String(publicKey), signingPublicKeySpkiBase64) ||
            !StringComparer.Ordinal.Equals(
                Convert.ToHexString(SHA256.HashData(publicKey)).ToLowerInvariant(),
                signingPublicKeySha256))
            throw new ArgumentException("Signing public key does not match its fingerprint.",
                nameof(signingPublicKeySpkiBase64));
        try
        {
            using var verifier = ECDsa.Create();
            verifier.ImportSubjectPublicKeyInfo(publicKey, out var bytesRead);
            if (bytesRead != publicKey.Length || verifier.KeySize != 256)
                throw new CryptographicException("Signing key must be ECDSA P-256.");
        }
        catch (Exception ex) when (ex is CryptographicException or ArgumentException)
        {
            throw new ArgumentException("Signing public key must be an ECDSA P-256 SPKI.",
                nameof(signingPublicKeySpkiBase64), ex);
        }

        OwnerCoreId = ownerCoreId;
        SigningKeyEpoch = signingKeyEpoch;
        SigningPublicKeySpkiBase64 = signingPublicKeySpkiBase64;
        SigningPublicKeySha256 = signingPublicKeySha256;
    }
}

public sealed record BridgeV1PairingResponse(
    int Version,
    string Status,
    string? ErrorCode,
    string? AgentId,
    string? EnrollmentId,
    string? DesktopHubId,
    string? Endpoint,
    string? TlsCertificateSha256,
    string? OwnerCoreId,
    long? OwnershipSigningKeyEpoch,
    string? OwnershipSigningPublicKeySpkiBase64,
    string? OwnershipSigningPublicKeySha256,
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
        null,
        null,
        null,
        null,
        null,
        [],
        null,
        null);

    public static BridgeV1PairingResponse Enrolled(
        BridgeV1AgentEnrollmentCredential credential,
        BridgeV1OwnershipTrustAnchor ownershipTrustAnchor) => new(
        1,
        "ENROLLED",
        null,
        credential.AgentId,
        credential.EnrollmentId,
        credential.DesktopHubId,
        credential.Endpoint.AbsoluteUri,
        credential.TlsCertificateSha256,
        ownershipTrustAnchor.OwnerCoreId,
        ownershipTrustAnchor.SigningKeyEpoch,
        ownershipTrustAnchor.SigningPublicKeySpkiBase64,
        ownershipTrustAnchor.SigningPublicKeySha256,
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
    private readonly BridgeV1OwnershipTrustAnchor _ownershipTrustAnchor;
    private readonly TimeSpan _credentialLifetime;

    public BridgeV1PairingEndpoint(
        BridgeV1PairingTicketStore tickets,
        DesktopHubStore hubStore,
        string desktopHubId,
        string tlsCertificateSha256,
        BridgeV1OwnershipTrustAnchor ownershipTrustAnchor,
        TimeSpan? credentialLifetime = null,
        TimeProvider? timeProvider = null)
    {
        ArgumentNullException.ThrowIfNull(tickets);
        ArgumentNullException.ThrowIfNull(hubStore);
        ArgumentException.ThrowIfNullOrWhiteSpace(desktopHubId);
        ArgumentException.ThrowIfNullOrWhiteSpace(tlsCertificateSha256);
        ArgumentNullException.ThrowIfNull(ownershipTrustAnchor);
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
        _ownershipTrustAnchor = ownershipTrustAnchor;
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
            return BridgeV1PairingResponse.Enrolled(result.Credential, _ownershipTrustAnchor);
        }
        catch (ArgumentException)
        {
            return BridgeV1PairingResponse.Reject();
        }
    }
}
