using System.Security.Cryptography;
using System.Text;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record HubRequestCredential(
    string DeviceId,
    byte[] HmacSecret,
    IReadOnlySet<string> AllowedScopes);

public sealed record HubSignedRequest(
    string DeviceId,
    string Method,
    string Path,
    long TimestampUnixSeconds,
    string Nonce,
    string BodySha256,
    IReadOnlyCollection<string> Scopes,
    string SignatureHex);

public enum HubAdmissionFailure
{
    None,
    InvalidEnvelope,
    CredentialNotFound,
    TimestampOutsideWindow,
    BodyDigestMismatch,
    ScopeDenied,
    SignatureInvalid,
    ReplayDetected
}

public sealed record HubAdmissionResult(bool Accepted, HubAdmissionFailure Failure)
{
    public static HubAdmissionResult Allow() => new(true, HubAdmissionFailure.None);
    public static HubAdmissionResult Deny(HubAdmissionFailure failure) => new(false, failure);
}

public interface IHubCredentialResolver
{
    ValueTask<HubRequestCredential?> ResolveAsync(string deviceId, CancellationToken cancellationToken);
}

/// <summary>
/// Implementations must atomically and durably claim (deviceId, nonce) until expiry.
/// An in-memory-only implementation is not suitable for a network listener.
/// </summary>
public interface IHubReplayGuard
{
    ValueTask<bool> TryClaimAsync(
        string deviceId,
        string nonce,
        DateTimeOffset expiresAt,
        CancellationToken cancellationToken);
}

/// <summary>
/// Verifies the request envelope before any operation reaches DesktopHubStore.
/// This class intentionally has no permissive default credential or replay store.
/// </summary>
public sealed class HubRequestAuthenticator(
    IHubCredentialResolver credentials,
    IHubReplayGuard replayGuard,
    TimeSpan? allowedClockSkew = null)
{
    private readonly TimeSpan _allowedClockSkew = allowedClockSkew ?? TimeSpan.FromMinutes(5);

    public async ValueTask<HubAdmissionResult> AuthenticateAsync(
        HubSignedRequest request,
        ReadOnlyMemory<byte> body,
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        if (!IsValidEnvelope(request))
            return HubAdmissionResult.Deny(HubAdmissionFailure.InvalidEnvelope);

        var credential = await credentials.ResolveAsync(request.DeviceId, cancellationToken);
        if (credential is null || credential.HmacSecret.Length < 32)
            return HubAdmissionResult.Deny(HubAdmissionFailure.CredentialNotFound);

        DateTimeOffset requestTime;
        try
        {
            requestTime = DateTimeOffset.FromUnixTimeSeconds(request.TimestampUnixSeconds);
        }
        catch (ArgumentOutOfRangeException)
        {
            return HubAdmissionResult.Deny(HubAdmissionFailure.InvalidEnvelope);
        }

        if ((now - requestTime).Duration() > _allowedClockSkew)
            return HubAdmissionResult.Deny(HubAdmissionFailure.TimestampOutsideWindow);

        var bodyDigest = Convert.ToHexString(SHA256.HashData(body.Span)).ToLowerInvariant();
        if (!CryptographicOperations.FixedTimeEquals(
                Encoding.ASCII.GetBytes(bodyDigest),
                Encoding.ASCII.GetBytes(request.BodySha256.ToLowerInvariant())))
            return HubAdmissionResult.Deny(HubAdmissionFailure.BodyDigestMismatch);

        var requestedScopes = request.Scopes.Distinct(StringComparer.Ordinal).ToArray();
        if (requestedScopes.Length != request.Scopes.Count ||
            requestedScopes.Any(scope => !credential.AllowedScopes.Contains(scope)))
            return HubAdmissionResult.Deny(HubAdmissionFailure.ScopeDenied);

        byte[] suppliedSignature;
        try
        {
            suppliedSignature = Convert.FromHexString(request.SignatureHex);
        }
        catch (FormatException)
        {
            return HubAdmissionResult.Deny(HubAdmissionFailure.InvalidEnvelope);
        }

        var canonical = BuildCanonicalRequest(request, bodyDigest, requestedScopes);
        var expectedSignature = HMACSHA256.HashData(credential.HmacSecret, Encoding.UTF8.GetBytes(canonical));
        if (suppliedSignature.Length != expectedSignature.Length ||
            !CryptographicOperations.FixedTimeEquals(suppliedSignature, expectedSignature))
            return HubAdmissionResult.Deny(HubAdmissionFailure.SignatureInvalid);

        var nonceExpiry = requestTime.Add(_allowedClockSkew);
        if (!await replayGuard.TryClaimAsync(request.DeviceId, request.Nonce, nonceExpiry, cancellationToken))
            return HubAdmissionResult.Deny(HubAdmissionFailure.ReplayDetected);

        return HubAdmissionResult.Allow();
    }

    public static string BuildCanonicalRequest(
        HubSignedRequest request,
        string normalizedBodySha256,
        IEnumerable<string> normalizedScopes)
    {
        var scopes = string.Join(",", normalizedScopes.Order(StringComparer.Ordinal));
        return string.Join("\n",
            request.Method.ToUpperInvariant(),
            request.Path,
            request.TimestampUnixSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture),
            request.Nonce,
            normalizedBodySha256.ToLowerInvariant(),
            scopes);
    }

    private static bool IsValidEnvelope(HubSignedRequest request)
    {
        return !string.IsNullOrWhiteSpace(request.DeviceId) &&
               request.DeviceId.Length <= 128 &&
               request.Method.Length is > 0 and <= 12 &&
               request.Method.All(char.IsAsciiLetter) &&
               request.Path.StartsWith('/', StringComparison.Ordinal) &&
               !request.Path.Contains('\n') &&
               request.Path.Length <= 2048 &&
               request.Nonce.Length is >= 16 and <= 128 &&
               request.Nonce.All(char.IsAsciiLetterOrDigit) &&
               System.Text.RegularExpressions.Regex.IsMatch(request.BodySha256, "^[a-fA-F0-9]{64}$") &&
               request.Scopes.Count is > 0 and <= 32 &&
               request.Scopes.All(scope => !string.IsNullOrWhiteSpace(scope) && scope.Length <= 128) &&
               request.SignatureHex.Length == 64;
    }
}
