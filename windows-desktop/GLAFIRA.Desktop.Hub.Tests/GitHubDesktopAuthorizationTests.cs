using System.Net;
using System.Text;
using GLAFIRA.Desktop.Hub.Core;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class GitHubDesktopAuthorizationTests
{
    [Fact]
    public async Task DeviceFlowRequestsOfflineAccessAndValidatesVerificationUri()
    {
        string? submittedForm = null;
        using var http = new HttpClient(new StubHandler(async (request, cancellationToken) =>
        {
            submittedForm = await request.Content!.ReadAsStringAsync(cancellationToken);
            return Json("{\"device_code\":\"device-secret\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}");
        }));
        var client = new GitHubDesktopDeviceFlowClient(http);

        var result = await client.RequestDeviceCodeAsync();

        Assert.Equal("ABCD-EFGH", result.UserCode);
        Assert.Equal(new Uri("https://github.com/login/device"), result.VerificationUri);
        Assert.Contains("scope=offline_access", submittedForm);
        Assert.Contains("client_id=" + GitHubDesktopAppConfiguration.ClientId, submittedForm);
    }

    [Fact]
    public async Task DeviceFlowMapsPendingSlowDownAndAuthorizedResponses()
    {
        var responses = new Queue<string>(
        [
            "{\"error\":\"authorization_pending\"}",
            "{\"error\":\"slow_down\"}",
            "{\"access_token\":\"access-value\",\"refresh_token\":\"refresh-value\",\"expires_in\":28800,\"refresh_token_expires_in\":15897600}"
        ]);
        using var http = new HttpClient(new StubHandler((_, _) =>
            Task.FromResult(Json(responses.Dequeue()))));
        var client = new GitHubDesktopDeviceFlowClient(http);
        var deviceCode = new GitHubDesktopDeviceCode(
            "device-value", "ABCD-EFGH", new Uri("https://github.com/login/device"),
            DateTimeOffset.UtcNow.AddMinutes(10), 5);

        var pending = await client.PollAsync(deviceCode);
        var slowDown = await client.PollAsync(deviceCode);
        var authorized = await client.PollAsync(deviceCode);

        Assert.Equal(GitHubDesktopDevicePollStatus.Pending, pending.Status);
        Assert.Equal(GitHubDesktopDevicePollStatus.SlowDown, slowDown.Status);
        Assert.Equal(10, slowDown.NextPollIntervalSeconds);
        Assert.Equal(GitHubDesktopDevicePollStatus.Authorized, authorized.Status);
        Assert.Equal("access-value", authorized.Tokens!.AccessToken);
        Assert.Equal("refresh-value", authorized.Tokens.RefreshToken);
    }

    [Fact]
    public async Task RefreshPreservesExistingRefreshTokenWhenResponseOmitsRotationField()
    {
        using var http = new HttpClient(new StubHandler((_, _) =>
            Task.FromResult(Json("{\"access_token\":\"new-access\",\"expires_in\":3600}"))));
        var client = new GitHubDesktopDeviceFlowClient(http);
        var current = new GitHubDesktopAuthorizationTokens(
            "old-access", "old-refresh", DateTimeOffset.UtcNow.AddMinutes(-1), null);

        var refreshed = await client.RefreshAsync(current);

        Assert.Equal("new-access", refreshed.AccessToken);
        Assert.Equal("old-refresh", refreshed.RefreshToken);
        Assert.True(refreshed.AccessTokenExpiresAtUtc > DateTimeOffset.UtcNow);
    }

    [Fact]
    public async Task ApiClientRejectsRepositoryOutsideExactAllowlist()
    {
        using var http = new HttpClient(new StubHandler((_, _) =>
            Task.FromResult(Json("{\"repositories\":[{\"id\":42,\"full_name\":\"someone/other-project\",\"private\":false,\"default_branch\":\"main\"}]}"))));
        var client = new GitHubDesktopApiClient(http);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.GetAllowlistedRepositoryAsync("access-token", 123));
    }

    [Fact]
    public void TokenStoreRoundTripsOnlyWithinCurrentWindowsUserProfile()
    {
        if (!OperatingSystem.IsWindows()) return;
        var path = Path.Combine(Path.GetTempPath(), "glafira-github-" + Guid.NewGuid().ToString("N") + ".dpapi");
        var store = new GitHubDesktopTokenStore(path);
        var tokens = new GitHubDesktopAuthorizationTokens(
            "access-token", "refresh-token",
            DateTimeOffset.UtcNow.AddHours(1), DateTimeOffset.UtcNow.AddDays(30));
        try
        {
            store.Save(tokens);
            var restored = store.Load();
            Assert.Equal(tokens.AccessToken, restored!.AccessToken);
            Assert.Equal(tokens.RefreshToken, restored.RefreshToken);
            Assert.NotNull(restored.AccessTokenExpiresAtUtc);
            store.Clear();
            Assert.Null(store.Load());
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    private static HttpResponseMessage Json(string body) =>
        new(HttpStatusCode.OK)
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };

    private sealed class StubHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> handler)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            handler(request, cancellationToken);
    }
}
