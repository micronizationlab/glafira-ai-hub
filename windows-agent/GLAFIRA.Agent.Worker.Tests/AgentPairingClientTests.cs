using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentPairingClientTests
{
    private const string Pin = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static readonly Uri Endpoint = new("https://192.168.10.5:8443/bridge/v1");
    private readonly DateTimeOffset _now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);

    [Fact]
    public async Task ValidPinnedBundleIsSavedOnlyAfterCompleteValidation()
    {
        var store = new RecordingStore();
        var handler = new StubHandler(_ => JsonResponse(Bundle()));
        var client = CreateClient(store, handler);

        var credential = await client.EnrollAsync(Attempt());

        Assert.Same(credential, store.Saved);
        Assert.Equal("desktop-hub-01", credential.DesktopHubId);
        Assert.Equal(32, Convert.FromBase64String(credential.SharedSecretBase64).Length);
        Assert.Equal(["desktop.task.claim", "desktop.task.result"], credential.Scopes.Order());
        Assert.Equal("/bridge/v1/pair", handler.LastRequestUri?.AbsolutePath);
    }

    [Theory]
    [InlineData("agentId", "other-agent")]
    [InlineData("endpoint", "https://192.168.10.6:8443/bridge/v1")]
    [InlineData("tlsCertificateSha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")]
    public async Task IdentityEndpointOrPinMismatchIsNeverSaved(string field, string replacement)
    {
        var store = new RecordingStore();
        var bundle = Bundle();
        bundle[field] = replacement;
        var client = CreateClient(store, new StubHandler(_ => JsonResponse(bundle)));

        await Assert.ThrowsAsync<InvalidDataException>(() => client.EnrollAsync(Attempt()));

        Assert.Null(store.Saved);
    }

    [Fact]
    public async Task NonCanonicalScopesOrWrongSecretSizeAreNeverSaved()
    {
        foreach (var bundle in new[]
        {
            Bundle(scopes: ["desktop.task.result", "desktop.task.claim"]),
            Bundle(scopes: ["desktop.task.claim", "desktop.task.claim"]),
            Bundle(secretBytes: 64)
        })
        {
            var store = new RecordingStore();
            var client = CreateClient(store, new StubHandler(_ => JsonResponse(bundle)));

            await Assert.ThrowsAsync<InvalidDataException>(() => client.EnrollAsync(Attempt()));
            Assert.Null(store.Saved);
        }
    }

    [Fact]
    public async Task FailedNonJsonOrOversizedResponseIsNeverSaved()
    {
        var responses = new[]
        {
            new HttpResponseMessage(HttpStatusCode.Forbidden),
            new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent("not-json", Encoding.UTF8, "text/plain")
            },
            new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(new string('x', 16 * 1024 + 1), Encoding.UTF8, "application/json")
            }
        };
        foreach (var response in responses)
        {
            var store = new RecordingStore();
            var client = CreateClient(store, new StubHandler(_ => response));

            await Assert.ThrowsAsync<InvalidDataException>(() => client.EnrollAsync(Attempt()));
            Assert.Null(store.Saved);
        }
    }

    [Fact]
    public async Task UnknownResponseFieldFailsClosed()
    {
        var store = new RecordingStore();
        var bundle = Bundle();
        bundle["unexpectedSecretHint"] = "must-not-be-accepted";
        var client = CreateClient(store, new StubHandler(_ => JsonResponse(bundle)));

        await Assert.ThrowsAsync<InvalidDataException>(() => client.EnrollAsync(Attempt()));

        Assert.Null(store.Saved);
    }

    [Theory]
    [InlineData("http://192.168.10.5:8443/bridge/v1")]
    [InlineData("https://127.0.0.1:8443/bridge/v1")]
    [InlineData("https://8.8.8.8:8443/bridge/v1")]
    [InlineData("https://192.168.10.5:8443/not-bridge")]
    public async Task NonPrivateOrNonCanonicalEndpointIsRejectedBeforeNetwork(string endpoint)
    {
        var handler = new StubHandler(_ => throw new Xunit.Sdk.XunitException("Network must not be called."));
        var client = CreateClient(new RecordingStore(), handler);

        await Assert.ThrowsAsync<ArgumentException>(() =>
            client.EnrollAsync(Attempt() with { Endpoint = new Uri(endpoint) }));

        Assert.Null(handler.LastRequestUri);
    }

    [Fact]
    public void CertificatePinOverloadRejectsMalformedExpectedPin()
    {
        Assert.False(AgentTlsCertificatePinPolicy.Validate(
            "not-a-pin", null, System.Net.Security.SslPolicyErrors.RemoteCertificateNotAvailable));
    }

    private AgentPairingClient CreateClient(RecordingStore store, HttpMessageHandler handler) =>
        new(store, new FixedTimeProvider(_now), _ => handler);

    private AgentPairingAttempt Attempt() => new(
        Endpoint,
        Pin,
        "0123456789abcdef0123456789abcdef",
        "abcdefghijklmnopqrstuvwxyz0123456789-_ABCDE",
        "agent-01");

    private Dictionary<string, object?> Bundle(
        string[]? scopes = null,
        int secretBytes = 32) => new()
    {
        ["version"] = 1,
        ["status"] = "ENROLLED",
        ["errorCode"] = null,
        ["agentId"] = "agent-01",
        ["desktopHubId"] = "desktop-hub-01",
        ["endpoint"] = Endpoint.AbsoluteUri,
        ["tlsCertificateSha256"] = Pin,
        ["sharedSecretBase64"] = Convert.ToBase64String(RandomNumberGenerator.GetBytes(secretBytes)),
        ["scopes"] = scopes ?? ["desktop.task.claim", "desktop.task.result"],
        ["enrolledAtEpochMs"] = _now.ToUnixTimeMilliseconds(),
        ["expiresAtEpochMs"] = _now.AddDays(30).ToUnixTimeMilliseconds()
    };

    private static HttpResponseMessage JsonResponse(object payload)
    {
        var response = new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json")
        };
        response.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json")
        {
            CharSet = "utf-8"
        };
        return response;
    }

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        public Uri? LastRequestUri { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            LastRequestUri = request.RequestUri;
            return Task.FromResult(responder(request));
        }
    }

    private sealed class RecordingStore : IAgentCredentialStore
    {
        public AgentEnrollmentCredential? Saved { get; private set; }
        public Task SaveAsync(AgentEnrollmentCredential credential, CancellationToken cancellationToken)
        {
            Saved = credential;
            return Task.CompletedTask;
        }
        public Task<AgentEnrollmentCredential?> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(Saved);
        public Task DeleteAsync(CancellationToken cancellationToken)
        {
            Saved = null;
            return Task.CompletedTask;
        }
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
