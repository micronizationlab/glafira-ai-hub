using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace GLAFIRA.Desktop.Hub.Core;

public sealed record GitHubDesktopTreeEntry(string Path, string Type, string Sha, long? Size);
public sealed record GitHubDesktopTreeSnapshot(IReadOnlyList<GitHubDesktopTreeEntry> Entries, bool Truncated);
public sealed record GitHubDesktopFileWriteResult(string CommitSha, string ContentSha);
public sealed record GitHubDesktopDraftPullRequest(long Number, Uri Url, string HeadSha, bool Draft);

/// <summary>
/// Owner-allowlisted GitHub coding API for the Windows Desktop client.
/// Writes are restricted to feature branches and Draft PRs. This type has no merge,
/// branch deletion, repository-settings, arbitrary URL, or shell-execution operation.
/// </summary>
public sealed class GitHubDesktopCodingClient
{
    private static readonly Uri ApiBase = new("https://api.github.com/");
    private const int MaxFileBytes = 1024 * 1024;
    private const int MaxResponseBytes = 4 * 1024 * 1024;
    private readonly HttpClient _httpClient;

    public GitHubDesktopCodingClient(HttpClient httpClient) =>
        _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));

    public async Task<GitHubDesktopTreeSnapshot> ReadTreeAsync(
        string accessToken, string repository, string reference,
        CancellationToken cancellationToken = default)
    {
        RequireRepository(repository);
        RequireReference(reference);
        using var json = await SendJsonAsync(
            HttpMethod.Get,
            $"repos/{repository}/git/trees/{EncodeSegment(reference)}?recursive=1",
            accessToken, null, cancellationToken).ConfigureAwait(false);
        var root = json.RootElement;
        var entries = new List<GitHubDesktopTreeEntry>();
        if (root.TryGetProperty("tree", out var tree) && tree.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in tree.EnumerateArray())
            {
                entries.Add(new GitHubDesktopTreeEntry(
                    GetString(item, "path"),
                    GetString(item, "type"),
                    GetString(item, "sha"),
                    item.TryGetProperty("size", out var size) && size.TryGetInt64(out var length)
                        ? length : null));
            }
        }
        return new GitHubDesktopTreeSnapshot(entries, root.TryGetProperty("truncated", out var truncated) &&
            truncated.ValueKind == JsonValueKind.True);
    }

    public async Task<string> ReadFileAsync(
        string accessToken, string repository, string path, string reference,
        CancellationToken cancellationToken = default)
    {
        RequireRepository(repository);
        RequireSafePath(path);
        RequireReference(reference);
        using var json = await SendJsonAsync(
            HttpMethod.Get,
            $"repos/{repository}/contents/{EncodePath(path)}?ref={EncodeSegment(reference)}",
            accessToken, null, cancellationToken).ConfigureAwait(false);
        var root = json.RootElement;
        if (GetString(root, "type") != "file" || GetString(root, "encoding") != "base64")
            throw new InvalidDataException("GitHub did not return a base64 file.");
        var encoded = string.Concat(GetString(root, "content").Where(c => !char.IsWhiteSpace(c)));
        var bytes = Convert.FromBase64String(encoded);
        if (bytes.Length > MaxFileBytes)
            throw new InvalidDataException("GitHub file exceeds the 1 MiB read limit.");
        return Encoding.UTF8.GetString(bytes);
    }

    public async Task<string> CreateBranchAsync(
        string accessToken, string repository, string branch, string baseBranch,
        CancellationToken cancellationToken = default)
    {
        RequireRepository(repository);
        RequireWritableBranch(branch);
        RequireReference(baseBranch);
        using var baseJson = await SendJsonAsync(
            HttpMethod.Get,
            $"repos/{repository}/git/ref/heads/{EncodeSegment(baseBranch)}",
            accessToken, null, cancellationToken).ConfigureAwait(false);
        var baseSha = GetNestedString(baseJson.RootElement, "object", "sha");
        RequireSha(baseSha);
        using var created = await SendJsonAsync(
            HttpMethod.Post,
            $"repos/{repository}/git/refs",
            accessToken,
            new { @ref = $"refs/heads/{branch}", sha = baseSha },
            cancellationToken).ConfigureAwait(false);
        var branchSha = GetNestedString(created.RootElement, "object", "sha");
        RequireSha(branchSha);
        return branchSha;
    }

    public async Task<GitHubDesktopFileWriteResult> WriteFileAsync(
        string accessToken, string repository, string path, string content,
        string commitMessage, string branch, string? existingContentSha = null,
        CancellationToken cancellationToken = default)
    {
        RequireRepository(repository);
        RequireSafePath(path);
        RequireWritablePath(path);
        RequireWritableBranch(branch);
        if (string.IsNullOrWhiteSpace(commitMessage) || commitMessage.Length > 200)
            throw new ArgumentException("Invalid GitHub commit message.", nameof(commitMessage));
        var bytes = Encoding.UTF8.GetBytes(content);
        if (bytes.Length > MaxFileBytes)
            throw new ArgumentException("GitHub file exceeds the 1 MiB write limit.", nameof(content));
        if (existingContentSha is not null) RequireSha(existingContentSha);

        var payload = new Dictionary<string, object>
        {
            ["message"] = commitMessage,
            ["content"] = Convert.ToBase64String(bytes),
            ["branch"] = branch
        };
        if (existingContentSha is not null) payload["sha"] = existingContentSha;
        using var json = await SendJsonAsync(
            HttpMethod.Put,
            $"repos/{repository}/contents/{EncodePath(path)}",
            accessToken, payload, cancellationToken).ConfigureAwait(false);
        var commitSha = GetNestedString(json.RootElement, "commit", "sha");
        var contentSha = GetNestedString(json.RootElement, "content", "sha");
        RequireSha(commitSha);
        RequireSha(contentSha);
        return new GitHubDesktopFileWriteResult(commitSha, contentSha);
    }

    public async Task<GitHubDesktopDraftPullRequest> OpenDraftPullRequestAsync(
        string accessToken, string repository, string title, string body,
        string headBranch, string baseBranch,
        CancellationToken cancellationToken = default)
    {
        RequireRepository(repository);
        RequireWritableBranch(headBranch);
        RequireReference(baseBranch);
        if (headBranch == baseBranch)
            throw new ArgumentException("A pull request must compare different branches.", nameof(headBranch));
        if (string.IsNullOrWhiteSpace(title) || title.Length > 256)
            throw new ArgumentException("Invalid pull request title.", nameof(title));

        using var json = await SendJsonAsync(
            HttpMethod.Post,
            $"repos/{repository}/pulls",
            accessToken,
            new { title, body, head = headBranch, @base = baseBranch, draft = true },
            cancellationToken).ConfigureAwait(false);
        var root = json.RootElement;
        var number = root.GetProperty("number").GetInt64();
        var urlText = GetString(root, "html_url");
        if (!Uri.TryCreate(urlText, UriKind.Absolute, out var url) ||
            url.Scheme != Uri.UriSchemeHttps || url.Host != "github.com")
            throw new InvalidDataException("GitHub returned an invalid pull request URL.");
        var headSha = GetNestedString(root, "head", "sha");
        RequireSha(headSha);
        return new GitHubDesktopDraftPullRequest(
            number, url, headSha,
            root.TryGetProperty("draft", out var draft) && draft.ValueKind == JsonValueKind.True);
    }

    private async Task<JsonDocument> SendJsonAsync(
        HttpMethod method, string relativePath, string accessToken, object? body,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(accessToken))
            throw new InvalidOperationException("GitHub is not connected.");
        if (relativePath.StartsWith('/') || relativePath.Contains("..", StringComparison.Ordinal))
            throw new ArgumentException("Invalid GitHub API path.", nameof(relativePath));
        using var request = new HttpRequestMessage(method, new Uri(ApiBase, relativePath));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        request.Headers.Add("X-GitHub-Api-Version", "2022-11-28");
        request.Headers.UserAgent.ParseAdd("GLAFIRA-AI-HUB-Windows-Desktop");
        if (body is not null)
            request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");

        using var response = await _httpClient.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var status = (int)response.StatusCode;
            throw new HttpRequestException(status switch
            {
                401 => "GitHub authorization expired or was revoked. Reconnect GitHub.",
                403 => "GitHub denied this operation or the API rate limit was reached.",
                404 => "GitHub resource was not found or is not accessible to this App.",
                409 => "GitHub branch changed concurrently; refresh before retrying.",
                422 => "GitHub rejected the change. Check branch, file SHA and App permissions.",
                _ => $"GitHub API request failed (HTTP {status})."
            });
        }

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false);
        using var output = new MemoryStream();
        var buffer = new byte[8192];
        while (true)
        {
            var count = await stream.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (count == 0) break;
            if (output.Length + count > MaxResponseBytes)
                throw new InvalidDataException("GitHub API response exceeds the 4 MiB safety limit.");
            await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken).ConfigureAwait(false);
        }
        return JsonDocument.Parse(output.ToArray());
    }

    private static void RequireRepository(string repository)
    {
        if (!StringComparer.Ordinal.Equals(repository, GitHubDesktopAppConfiguration.AllowedRepository))
            throw new InvalidOperationException("Repository is not approved for GLAFIRA GitHub operations.");
    }

    public static void RequireWritableBranch(string branch)
    {
        if (string.IsNullOrWhiteSpace(branch) || branch.Length > 100 ||
            !System.Text.RegularExpressions.Regex.IsMatch(branch, "^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$") ||
            branch is "main" or "master" || branch.StartsWith("main/", StringComparison.Ordinal) ||
            branch.StartsWith("master/", StringComparison.Ordinal) || branch.EndsWith('/') ||
            branch.Contains("..", StringComparison.Ordinal) || branch.Contains("//", StringComparison.Ordinal) ||
            branch.Split('/').Any(part => part.StartsWith('.') || part.EndsWith('.') ||
                part.EndsWith(".lock", StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Invalid or protected GitHub branch name.", nameof(branch));
    }

    public static void RequireSafePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.Length > 400 || path.StartsWith('/') ||
            path.EndsWith('/') || path.Contains('\\') ||
            path.Split('/').Any(part => string.IsNullOrWhiteSpace(part) || part is "." or ".."))
            throw new ArgumentException("Invalid repository-relative file path.", nameof(path));
    }

    /// <summary>
    /// Prevent autonomous writes from changing CI, build bootstrap, dependency resolution,
    /// or executable project configuration. These require a separate owner-reviewed path.
    /// </summary>
    public static void RequireWritablePath(string path)
    {
        RequireSafePath(path);
        var normalized = path.ToLowerInvariant();
        var segments = normalized.Split('/');
        var fileName = segments[^1];
        var blocked =
            normalized == ".gitmodules" ||
            normalized == "global.json" ||
            normalized == "nuget.config" ||
            normalized == "nuget.lock" ||
            segments[0] is ".github" or ".gitlab" or ".circleci" ||
            fileName == "jenkinsfile" ||
            fileName == "azure-pipelines.yml" ||
            fileName == "dockerfile" ||
            fileName.StartsWith("docker-compose", StringComparison.Ordinal) ||
            fileName.EndsWith(".csproj", StringComparison.Ordinal) ||
            fileName.EndsWith(".sln", StringComparison.Ordinal) ||
            fileName.EndsWith(".slnx", StringComparison.Ordinal) ||
            fileName.EndsWith(".props", StringComparison.Ordinal) ||
            fileName.EndsWith(".targets", StringComparison.Ordinal) ||
            fileName.EndsWith(".wapproj", StringComparison.Ordinal) ||
            fileName.EndsWith(".nuspec", StringComparison.Ordinal) ||
            fileName == "packages.lock.json" ||
            fileName == "directory.build.props" ||
            fileName == "directory.build.targets";
        if (blocked)
            throw new InvalidOperationException(
                "GLAFIRA GitHub coding API cannot modify CI, build bootstrap, dependency or executable project files without a separate owner-reviewed workflow.");
    }

    private static void RequireReference(string reference)
    {
        if (string.IsNullOrWhiteSpace(reference) || reference.Length > 200 ||
            reference.StartsWith('-') || reference.Contains("..", StringComparison.Ordinal))
            throw new ArgumentException("Invalid GitHub ref.", nameof(reference));
    }

    private static void RequireSha(string sha)
    {
        if (!System.Text.RegularExpressions.Regex.IsMatch(sha, "^[0-9a-fA-F]{40}$"))
            throw new InvalidDataException("GitHub returned an invalid SHA.");
    }

    private static string GetString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;

    private static string GetNestedString(JsonElement element, string parent, string child)
    {
        if (!element.TryGetProperty(parent, out var nested) || nested.ValueKind != JsonValueKind.Object)
            return string.Empty;
        return GetString(nested, child);
    }

    private static string EncodeSegment(string value) => Uri.EscapeDataString(value);
    private static string EncodePath(string path) =>
        string.Join("/", path.Split('/').Select(Uri.EscapeDataString));
}
