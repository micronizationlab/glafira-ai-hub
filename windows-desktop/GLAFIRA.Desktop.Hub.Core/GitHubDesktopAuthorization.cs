using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Public OAuth client identifier shared with the Android Device Flow implementation.
/// This identifier is not a secret. Never add a Client Secret or App private key here.
/// </summary>
public static class GitHubDesktopAppConfiguration
{
    public const string ClientId = "Iv23liZKylYOtML2Alm2";
    public const string AllowedRepository = "micronizationlab/glafira-ai-hub";
}

public sealed record GitHubDesktopDeviceCode(
    string DeviceCode,
    string UserCode,
    Uri VerificationUri,
    DateTimeOffset ExpiresAtUtc,
    int PollIntervalSeconds);

public sealed record GitHubDesktopAuthorizationTokens(
    string AccessToken,
    string? RefreshToken,
    DateTimeOffset? AccessTokenExpiresAtUtc,
    DateTimeOffset? RefreshTokenExpiresAtUtc);

public enum GitHubDesktopDevicePollStatus
{
    Pending,
    SlowDown,
    Authorized,
    Denied,
    Expired
}

public sealed record GitHubDesktopDevicePollResult(
    GitHubDesktopDevicePollStatus Status,
    GitHubDesktopAuthorizationTokens? Tokens = null,
    int? NextPollIntervalSeconds = null);

public sealed record GitHubDesktopAuthenticatedUser(long Id, string Login, string? Name);
public sealed record GitHubDesktopInstallation(long Id, string AccountLogin, string RepositorySelection);
public sealed record GitHubDesktopRepository(long Id, string FullName, bool IsPrivate, string DefaultBranch);

/// <summary>
/// GitHub OAuth Device Flow client. All endpoints are fixed HTTPS GitHub endpoints;
/// no caller-controlled URL or arbitrary API path is accepted.
/// </summary>
public sealed class GitHubDesktopDeviceFlowClient
{
    private static readonly Uri DeviceCodeEndpoint = new("https://github.com/login/device/code");
    private static readonly Uri AccessTokenEndpoint = new("https://github.com/login/oauth/access_token");
    private static readonly Uri VerificationEndpoint = new("https://github.com/login/device");
    private const string DeviceGrantType = "urn:ietf:params:oauth:grant-type:device_code";
    private readonly HttpClient _httpClient;
    private readonly string _clientId;

    public GitHubDesktopDeviceFlowClient(HttpClient httpClient, string? clientId = null)
    {
        _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));
        _clientId = string.IsNullOrWhiteSpace(clientId)
            ? GitHubDesktopAppConfiguration.ClientId
            : clientId.Trim();
        if (_clientId.Length > 128)
            throw new ArgumentException("Invalid GitHub App Client ID.", nameof(clientId));
    }

    public async Task<GitHubDesktopDeviceCode> RequestDeviceCodeAsync(
        CancellationToken cancellationToken = default)
    {
        using var response = await PostFormAsync(
            DeviceCodeEndpoint,
            new Dictionary<string, string>
            {
                ["client_id"] = _clientId,
                ["scope"] = "offline_access"
            },
            cancellationToken).ConfigureAwait(false);
        using var json = await ReadJsonAsync(response, cancellationToken).ConfigureAwait(false);

        var verificationText = GetRequiredString(json.RootElement, "verification_uri");
        if (!Uri.TryCreate(verificationText, UriKind.Absolute, out var verificationUri) ||
            verificationUri != VerificationEndpoint)
            throw new InvalidOperationException("GitHub returned an unexpected device verification address.");

        var deviceCode = GetRequiredString(json.RootElement, "device_code");
        var userCode = GetRequiredString(json.RootElement, "user_code");
        var expiresIn = GetRequiredInt64(json.RootElement, "expires_in");
        var interval = TryGetInt64(json.RootElement, "interval") ?? 5;
        if (expiresIn is < 1 or > 1800)
            throw new InvalidOperationException("GitHub returned an invalid device-code lifetime.");

        return new GitHubDesktopDeviceCode(
            deviceCode,
            userCode,
            verificationUri,
            DateTimeOffset.UtcNow.AddSeconds(expiresIn),
            (int)Math.Clamp(interval, 1, 60));
    }

    public async Task<GitHubDesktopDevicePollResult> PollAsync(
        GitHubDesktopDeviceCode deviceCode,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(deviceCode);
        if (string.IsNullOrWhiteSpace(deviceCode.DeviceCode))
            throw new ArgumentException("Device code is required.", nameof(deviceCode));
        if (deviceCode.ExpiresAtUtc <= DateTimeOffset.UtcNow)
            return new GitHubDesktopDevicePollResult(GitHubDesktopDevicePollStatus.Expired);

        using var response = await PostFormAsync(
            AccessTokenEndpoint,
            new Dictionary<string, string>
            {
                ["client_id"] = _clientId,
                ["device_code"] = deviceCode.DeviceCode,
                ["grant_type"] = DeviceGrantType
            },
            cancellationToken).ConfigureAwait(false);
        using var json = await ReadJsonAsync(response, cancellationToken).ConfigureAwait(false);
        var root = json.RootElement;
        var error = TryGetString(root, "error");
        switch (error)
        {
            case "authorization_pending":
                return new GitHubDesktopDevicePollResult(GitHubDesktopDevicePollStatus.Pending);
            case "slow_down":
                return new GitHubDesktopDevicePollResult(
                    GitHubDesktopDevicePollStatus.SlowDown,
                    NextPollIntervalSeconds: Math.Min(deviceCode.PollIntervalSeconds + 5, 60));
            case "access_denied":
                return new GitHubDesktopDevicePollResult(GitHubDesktopDevicePollStatus.Denied);
            case "expired_token":
                return new GitHubDesktopDevicePollResult(GitHubDesktopDevicePollStatus.Expired);
        }

        var tokens = ParseTokens(root, null);
        return new GitHubDesktopDevicePollResult(
            GitHubDesktopDevicePollStatus.Authorized,
            tokens);
    }

    public async Task<GitHubDesktopAuthorizationTokens> RefreshAsync(
        GitHubDesktopAuthorizationTokens current,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(current);
        if (string.IsNullOrWhiteSpace(current.RefreshToken))
            throw new InvalidOperationException("GitHub refresh token is unavailable.");

        using var response = await PostFormAsync(
            AccessTokenEndpoint,
            new Dictionary<string, string>
            {
                ["client_id"] = _clientId,
                ["grant_type"] = "refresh_token",
                ["refresh_token"] = current.RefreshToken
            },
            cancellationToken).ConfigureAwait(false);
        using var json = await ReadJsonAsync(response, cancellationToken).ConfigureAwait(false);
        return ParseTokens(json.RootElement, current.RefreshToken);
    }

    private async Task<HttpResponseMessage> PostFormAsync(
        Uri endpoint,
        IReadOnlyDictionary<string, string> values,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, endpoint)
        {
            Content = new FormUrlEncodedContent(values)
        };
        request.Headers.Accept.ParseAdd("application/json");
        request.Headers.UserAgent.ParseAdd("GLAFIRA-AI-HUB-Windows-Desktop");
        var response = await _httpClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var status = (int)response.StatusCode;
            response.Dispose();
            throw new HttpRequestException($"GitHub Device Flow request failed (HTTP {status}).");
        }
        return response;
    }

    private static async Task<JsonDocument> ReadJsonAsync(
        HttpResponseMessage response,
        CancellationToken cancellationToken)
    {
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false);
        return await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken)
            .ConfigureAwait(false);
    }

    private static GitHubDesktopAuthorizationTokens ParseTokens(
        JsonElement root,
        string? previousRefreshToken)
    {
        var accessToken = GetRequiredString(root, "access_token");
        var refreshToken = TryGetString(root, "refresh_token");
        var expiresIn = TryGetInt64(root, "expires_in");
        var refreshExpiresIn = TryGetInt64(root, "refresh_token_expires_in");
        var now = DateTimeOffset.UtcNow;
        return new GitHubDesktopAuthorizationTokens(
            accessToken,
            string.IsNullOrWhiteSpace(refreshToken) ? previousRefreshToken : refreshToken,
            expiresIn is > 0 ? now.AddSeconds(expiresIn.Value) : null,
            refreshExpiresIn is > 0 ? now.AddSeconds(refreshExpiresIn.Value) : null);
    }

    private static string GetRequiredString(JsonElement element, string name) =>
        TryGetString(element, name) is { Length: > 0 } value
            ? value
            : throw new InvalidOperationException($"GitHub response is missing {name}.");

    private static string? TryGetString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static long GetRequiredInt64(JsonElement element, string name) =>
        TryGetInt64(element, name) is { } value
            ? value
            : throw new InvalidOperationException($"GitHub response is missing {name}.");

    private static long? TryGetInt64(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.TryGetInt64(out var number)
            ? number
            : null;
}

/// <summary>
/// Stores OAuth tokens encrypted with Windows DPAPI CurrentUser. The file is bound to
/// the current Windows profile and cannot be decrypted by another Windows account.
/// </summary>
public sealed class GitHubDesktopTokenStore
{
    private static readonly byte[] Entropy =
        Encoding.UTF8.GetBytes("GLAFIRA.Desktop.GitHubTokenStore.v1");
    private readonly string _path;

    public GitHubDesktopTokenStore(string? path = null)
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(root))
            throw new InvalidOperationException("Windows user profile is unavailable.");
        _path = Path.GetFullPath(path ??
            Path.Combine(root, "GLAFIRA", "GitHub", "session.dpapi"));
    }

    public void Save(GitHubDesktopAuthorizationTokens tokens)
    {
        EnsureWindows();
        ArgumentNullException.ThrowIfNull(tokens);
        if (string.IsNullOrWhiteSpace(tokens.AccessToken))
            throw new ArgumentException("An access token is required.", nameof(tokens));

        var plaintext = JsonSerializer.SerializeToUtf8Bytes(tokens);
        byte[]? protectedBytes = null;
        var temporaryPath = _path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            protectedBytes = ProtectedData.Protect(
                plaintext, Entropy, DataProtectionScope.CurrentUser);
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            using (var stream = new FileStream(
                temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                4096, FileOptions.WriteThrough))
            {
                stream.Write(protectedBytes);
                stream.Flush(true);
            }
            File.Move(temporaryPath, _path, true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
            if (protectedBytes is not null)
                CryptographicOperations.ZeroMemory(protectedBytes);
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    public GitHubDesktopAuthorizationTokens? Load()
    {
        EnsureWindows();
        if (!File.Exists(_path)) return null;
        var encrypted = File.ReadAllBytes(_path);
        byte[]? plaintext = null;
        try
        {
            plaintext = ProtectedData.Unprotect(
                encrypted, Entropy, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<GitHubDesktopAuthorizationTokens>(plaintext)
                ?? throw new InvalidDataException("GitHub token store is empty or invalid.");
        }
        catch (CryptographicException error)
        {
            throw new InvalidOperationException(
                "GitHub token store cannot be decrypted for this Windows user. Reauthorization is required.",
                error);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(encrypted);
            if (plaintext is not null)
                CryptographicOperations.ZeroMemory(plaintext);
        }
    }

    public void Clear()
    {
        EnsureWindows();
        if (File.Exists(_path)) File.Delete(_path);
    }

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Windows DPAPI CurrentUser is required.");
    }
}

/// <summary>
/// Read-only GitHub REST surface for verifying the signed-in account and the exact
/// repository allowlist. No arbitrary path, shell, branch, commit or write operation exists.
/// </summary>
public sealed class GitHubDesktopApiClient
{
    private static readonly Uri ApiBase = new("https://api.github.com/");
    private readonly HttpClient _httpClient;

    public GitHubDesktopApiClient(HttpClient httpClient) =>
        _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));

    public async Task<GitHubDesktopAuthenticatedUser> GetAuthenticatedUserAsync(
        string accessToken,
        CancellationToken cancellationToken = default)
    {
        using var json = await GetJsonAsync("user", accessToken, cancellationToken)
            .ConfigureAwait(false);
        var root = json.RootElement;
        return new GitHubDesktopAuthenticatedUser(
            root.GetProperty("id").GetInt64(),
            root.GetProperty("login").GetString() ?? throw new InvalidDataException("GitHub login is missing."),
            TryGetString(root, "name"));
    }

    public async Task<IReadOnlyList<GitHubDesktopInstallation>> ListInstallationsAsync(
        string accessToken,
        CancellationToken cancellationToken = default)
    {
        using var json = await GetJsonAsync("user/installations?per_page=100", accessToken, cancellationToken)
            .ConfigureAwait(false);
        if (!json.RootElement.TryGetProperty("installations", out var items) ||
            items.ValueKind != JsonValueKind.Array)
            return [];
        return items.EnumerateArray().Select(item =>
        {
            var accountLogin = item.TryGetProperty("account", out var account)
                ? TryGetString(account, "login") ?? string.Empty
                : string.Empty;
            return new GitHubDesktopInstallation(
                item.GetProperty("id").GetInt64(),
                accountLogin,
                TryGetString(item, "repository_selection") ?? "unknown");
        }).ToArray();
    }

    public async Task<GitHubDesktopRepository> GetAllowlistedRepositoryAsync(
        string accessToken,
        long installationId,
        CancellationToken cancellationToken = default)
    {
        if (installationId <= 0) throw new ArgumentOutOfRangeException(nameof(installationId));
        using var json = await GetJsonAsync(
            $"user/installations/{installationId}/repositories?per_page=100",
            accessToken,
            cancellationToken).ConfigureAwait(false);
        if (!json.RootElement.TryGetProperty("repositories", out var items) ||
            items.ValueKind != JsonValueKind.Array)
            throw new InvalidOperationException("GitHub installation did not return repositories.");

        foreach (var item in items.EnumerateArray())
        {
            var fullName = TryGetString(item, "full_name");
            if (!StringComparer.OrdinalIgnoreCase.Equals(
                fullName, GitHubDesktopAppConfiguration.AllowedRepository))
                continue;
            return new GitHubDesktopRepository(
                item.GetProperty("id").GetInt64(),
                fullName!,
                item.TryGetProperty("private", out var isPrivate) && isPrivate.GetBoolean(),
                TryGetString(item, "default_branch") ?? "main");
        }

        throw new InvalidOperationException(
            "The GitHub App installation does not grant access to the allowlisted GLAFIRA repository.");
    }

    private async Task<JsonDocument> GetJsonAsync(
        string relativePath,
        string accessToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(accessToken))
            throw new InvalidOperationException("GitHub is not connected.");
        if (relativePath.StartsWith('/') || relativePath.Contains("..", StringComparison.Ordinal))
            throw new ArgumentException("Invalid GitHub API path.", nameof(relativePath));

        using var request = new HttpRequestMessage(
            HttpMethod.Get, new Uri(ApiBase, relativePath));
        request.Headers.Authorization =
            new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        request.Headers.Add("X-GitHub-Api-Version", "2022-11-28");
        request.Headers.UserAgent.ParseAdd("GLAFIRA-AI-HUB-Windows-Desktop");

        using var response = await _httpClient.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var status = (int)response.StatusCode;
            throw new HttpRequestException(status switch
            {
                401 => "GitHub authorization expired or was revoked. Reconnect GitHub.",
                403 => "GitHub denied this operation or the API rate limit was reached.",
                404 => "GitHub resource was not found or is not accessible to this App.",
                _ => $"GitHub API request failed (HTTP {status})."
            });
        }
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false);
        return await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken)
            .ConfigureAwait(false);
    }

    private static string? TryGetString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
}
