using Xunit;
using System.Security.Cryptography;
using System.Text;
using GLAFIRA.Desktop.Hub.Core;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class HubRequestAuthenticatorTests
{
    private static readonly byte[] Secret = Enumerable.Range(0, 32).Select(value => (byte)value).ToArray();

    [Fact]
    public async Task AcceptsValidSignedRequestAndClaimsNonce()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var body = Encoding.UTF8.GetBytes("{\"task\":\"status\"}");
        var request = Sign(body, now);

        var result = await authenticator.AuthenticateAsync(request, body, now);

        Assert.True(result.Accepted);
        Assert.Equal(HubAdmissionFailure.None, result.Failure);
        Assert.Equal(1, replay.Claims);
    }

    [Fact]
    public async Task RejectsRequestWhenBodyWasChangedAfterSigning()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var authenticator = CreateAuthenticator(new FakeReplayGuard());
        var body = Encoding.UTF8.GetBytes("{\"task\":\"status\"}");
        var request = Sign(body, now);

        var result = await authenticator.AuthenticateAsync(request, Encoding.UTF8.GetBytes("{}"), now);

        Assert.False(result.Accepted);
        Assert.Equal(HubAdmissionFailure.BodyDigestMismatch, result.Failure);
    }

    [Fact]
    public async Task RejectsTimestampOutsideAllowedWindow()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var authenticator = CreateAuthenticator(new FakeReplayGuard());
        var body = Encoding.UTF8.GetBytes("{}");
        var request = Sign(body, now.AddMinutes(-6));

        var result = await authenticator.AuthenticateAsync(request, body, now);

        Assert.Equal(HubAdmissionFailure.TimestampOutsideWindow, result.Failure);
    }

    [Fact]
    public async Task RejectsScopeEscalationBeforeReplayClaim()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var body = Encoding.UTF8.GetBytes("{}");
        var request = Sign(body, now, ["hub.read", "hub.admin"]);

        var result = await authenticator.AuthenticateAsync(request, body, now);

        Assert.Equal(HubAdmissionFailure.ScopeDenied, result.Failure);
        Assert.Equal(0, replay.Claims);
    }

    [Fact]
    public async Task RejectsReplayedNonce()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var replay = new FakeReplayGuard();
        var authenticator = CreateAuthenticator(replay);
        var body = Encoding.UTF8.GetBytes("{}");
        var request = Sign(body, now);

        Assert.True((await authenticator.AuthenticateAsync(request, body, now)).Accepted);
        var second = await authenticator.AuthenticateAsync(request, body, now);

        Assert.Equal(HubAdmissionFailure.ReplayDetected, second.Failure);
    }

    [Fact]
    public async Task RejectsMissingCredential()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var authenticator = new HubRequestAuthenticator(new FakeCredentialResolver(null), new FakeReplayGuard());
        var body = Encoding.UTF8.GetBytes("{}");
        var request = Sign(body, now);

        var result = await authenticator.AuthenticateAsync(request, body, now);

        Assert.Equal(HubAdmissionFailure.CredentialNotFound, result.Failure);
    }

    private static HubRequestAuthenticator CreateAuthenticator(FakeReplayGuard replay) =>
        new(new FakeCredentialResolver(new HubRequestCredential("device-1", Secret, new HashSet<string> { "hub.read" })), replay);

    private static HubSignedRequest Sign(byte[] body, DateTimeOffset timestamp, string[]? scopes = null)
    {
        var digest = Convert.ToHexString(SHA256.HashData(body)).ToLowerInvariant();
        var request = new HubSignedRequest(
            "device-1", "post", "/bridge/v1/status", timestamp.ToUnixTimeSeconds(),
            "0123456789abcdef0123456789abcdef", digest,
            scopes ?? ["hub.read"], new string('0', 64));
        var canonical = HubRequestAuthenticator.BuildCanonicalRequest(request, digest, request.Scopes);
        var signature = Convert.ToHexString(HMACSHA256.HashData(Secret, Encoding.UTF8.GetBytes(canonical)));
        return request with { SignatureHex = signature };
    }

    private sealed class FakeCredentialResolver(HubRequestCredential? credential) : IHubCredentialResolver
    {
        public ValueTask<HubRequestCredential?> ResolveAsync(string deviceId, CancellationToken cancellationToken) =>
            ValueTask.FromResult(credential?.DeviceId == deviceId ? credential : null);
    }

    private sealed class FakeReplayGuard : IHubReplayGuard
    {
        private readonly HashSet<string> _claimed = [];
        public int Claims { get; private set; }

        public ValueTask<bool> TryClaimAsync(string deviceId, string nonce, DateTimeOffset expiresAt, CancellationToken cancellationToken)
        {
            Claims++;
            return ValueTask.FromResult(_claimed.Add($"{deviceId}:{nonce}"));
        }
    }
}
