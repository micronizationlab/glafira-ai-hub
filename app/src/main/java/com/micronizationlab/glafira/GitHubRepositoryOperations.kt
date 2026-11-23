package com.micronizationlab.glafira

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

internal data class GitHubTreeEntry(
    val path: String,
    val type: String,
    val sha: String,
    val size: Long?
)

internal data class GitHubTreeSnapshot(
    val entries: List<GitHubTreeEntry>,
    val truncated: Boolean
)

internal data class GitHubFileWriteResult(
    val commitSha: String,
    val contentSha: String
)

internal data class GitHubDraftPullRequest(
    val number: Long,
    val url: String,
    val headSha: String,
    val draft: Boolean
)

/**
 * Branch-scoped GitHub coding operations for the owner-approved GLAFIRA repository.
 *
 * This class intentionally has no merge, branch deletion, repository-settings, or
 * arbitrary HTTP operation. Writes to the default/protected branch are rejected locally.
 */
internal class GitHubRepositoryOperations(
    private val allowedRepositories: Set<String> = DEFAULT_ALLOWED_REPOSITORIES
) {
    suspend fun readTree(
        accessToken: String,
        repository: String,
        ref: String
    ): GitHubTreeSnapshot = withContext(Dispatchers.IO) {
        requireRepository(repository)
        requireRef(ref)
        val json = requestJson(
            method = "GET",
            path = "/repos/${repository}/git/trees/${encodeQuery(ref)}?recursive=1",
            accessToken = accessToken
        )
        val tree = json.optJSONArray("tree") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until tree.length()) {
                val item = tree.optJSONObject(index) ?: continue
                add(
                    GitHubTreeEntry(
                        path = item.optString("path"),
                        type = item.optString("type"),
                        sha = item.optString("sha"),
                        size = if (item.has("size") && !item.isNull("size")) item.optLong("size") else null
                    )
                )
            }
        }
        GitHubTreeSnapshot(entries, json.optBoolean("truncated"))
    }

    suspend fun readFile(
        accessToken: String,
        repository: String,
        path: String,
        ref: String
    ): String = withContext(Dispatchers.IO) {
        requireRepository(repository)
        requireSafePath(path)
        requireRef(ref)
        val json = requestJson(
            method = "GET",
            path = "/repos/${repository}/contents/${encodePath(path)}?ref=${encodeQuery(ref)}",
            accessToken = accessToken
        )
        require(json.optString("type") == "file") { "GitHub path is not a file." }
        require(json.optString("encoding") == "base64") { "GitHub returned an unsupported file encoding." }
        val encoded = json.optString("content").replace("\\s".toRegex(), "")
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_FILE_BYTES) { "GitHub file exceeds the 1 MiB read limit." }
        String(bytes, Charsets.UTF_8)
    }

    suspend fun createBranch(
        accessToken: String,
        repository: String,
        branch: String,
        baseBranch: String
    ): String = withContext(Dispatchers.IO) {
        requireRepository(repository)
        requireWritableBranch(branch)
        requireRef(baseBranch)
        val base = requestJson(
            method = "GET",
            path = "/repos/${repository}/git/ref/heads/${encodeQuery(baseBranch)}",
            accessToken = accessToken
        )
        val baseSha = base.optJSONObject("object")?.optString("sha").orEmpty()
        require(baseSha.matches(SHA_PATTERN)) { "GitHub returned an invalid base commit SHA." }
        val response = requestJson(
            method = "POST",
            path = "/repos/${repository}/git/refs",
            accessToken = accessToken,
            body = JSONObject()
                .put("ref", "refs/heads/$branch")
                .put("sha", baseSha)
        )
        response.optJSONObject("object")?.optString("sha")
            ?.takeIf { it.matches(SHA_PATTERN) }
            ?: error("GitHub did not return the created branch SHA.")
    }

    suspend fun writeFile(
        accessToken: String,
        repository: String,
        path: String,
        content: String,
        message: String,
        branch: String,
        existingContentSha: String? = null
    ): GitHubFileWriteResult = withContext(Dispatchers.IO) {
        requireRepository(repository)
        requireSafePath(path)
        requireWritablePath(path)
        requireWritableBranch(branch)
        require(message.isNotBlank() && message.length <= 200) { "Invalid GitHub commit message." }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "GitHub file exceeds the 1 MiB write limit." }
        if (existingContentSha != null) {
            require(existingContentSha.matches(SHA_PATTERN)) { "Invalid existing content SHA." }
        }
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(bytes))
            .put("branch", branch)
        if (existingContentSha != null) body.put("sha", existingContentSha)
        val response = requestJson(
            method = "PUT",
            path = "/repos/${repository}/contents/${encodePath(path)}",
            accessToken = accessToken,
            body = body
        )
        val commitSha = response.optJSONObject("commit")?.optString("sha").orEmpty()
        val contentSha = response.optJSONObject("content")?.optString("sha").orEmpty()
        require(commitSha.matches(SHA_PATTERN) && contentSha.matches(SHA_PATTERN)) {
            "GitHub did not return valid commit and content SHAs."
        }
        GitHubFileWriteResult(commitSha, contentSha)
    }

    suspend fun openDraftPullRequest(
        accessToken: String,
        repository: String,
        title: String,
        body: String,
        headBranch: String,
        baseBranch: String
    ): GitHubDraftPullRequest = withContext(Dispatchers.IO) {
        requireRepository(repository)
        requireWritableBranch(headBranch)
        requireRef(baseBranch)
        require(headBranch != baseBranch) { "A pull request must compare different branches." }
        require(title.isNotBlank() && title.length <= 256) { "Invalid pull request title." }
        val response = requestJson(
            method = "POST",
            path = "/repos/${repository}/pulls",
            accessToken = accessToken,
            body = JSONObject()
                .put("title", title)
                .put("body", body)
                .put("head", headBranch)
                .put("base", baseBranch)
                .put("draft", true)
        )
        val number = response.optLong("number")
        val url = response.optString("html_url")
        val headSha = response.optJSONObject("head")?.optString("sha").orEmpty()
        require(number > 0L && url.startsWith("https://github.com/") && headSha.matches(SHA_PATTERN)) {
            "GitHub returned an incomplete pull request response."
        }
        GitHubDraftPullRequest(number, url, headSha, response.optBoolean("draft"))
    }

    private fun requestJson(
        method: String,
        path: String,
        accessToken: String,
        body: JSONObject? = null
    ): JSONObject {
        require(accessToken.isNotBlank()) { "GitHub is not connected." }
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains("..")) {
            "Invalid GitHub API path."
        }
        val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("User-Agent", "GLAFIRA-AI-HUB-Android")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = readBounded(stream, MAX_RESPONSE_BYTES)
            require(code in 200..299) {
                when (code) {
                    401 -> "GitHub authorization expired or was revoked. Reconnect GitHub."
                    403 -> "GitHub denied this operation or the API rate limit was reached."
                    404 -> "GitHub resource was not found or is not accessible to this App."
                    409 -> "GitHub branch changed concurrently; refresh its state before retrying."
                    422 -> "GitHub rejected the requested change. Check branch, file SHA and permissions."
                    else -> "GitHub API request failed (HTTP $code)."
                }
            }
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream?, limit: Int): String {
        if (stream == null) return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        stream.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "GitHub API response exceeds the 4 MiB safety limit." }
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun requireRepository(repository: String) {
        require(repository in allowedRepositories) { "Repository is not approved for GLAFIRA GitHub operations." }
    }

    companion object {
        private const val API_BASE = "https://api.github.com"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private val SHA_PATTERN = Regex("^[0-9a-fA-F]{40}$")
        private val BRANCH_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$")
        private val DEFAULT_ALLOWED_REPOSITORIES = setOf("micronizationlab/glafira-ai-hub")

        internal fun requireWritableBranch(branch: String) {
            require(BRANCH_PATTERN.matches(branch)) { "Invalid GitHub branch name." }
            require(branch != "main" && branch != "master" &&
                !branch.startsWith("main/") && !branch.startsWith("master/")) {
                "GLAFIRA cannot write directly to a protected branch."
            }
            require(!branch.endsWith("/") && !branch.contains("..") && !branch.contains("//") &&
                branch.split('/').all { segment ->
                    !segment.startsWith(".") && !segment.endsWith(".") && !segment.endsWith(".lock")
                }) {
                "Invalid GitHub branch name."
            }
        }

        internal fun requireSafePath(path: String) {
            require(path.isNotBlank() && path.length <= 400) { "Invalid GitHub file path." }
            require(!path.startsWith("/") && !path.endsWith("/") && !path.contains("\\")) {
                "Invalid GitHub file path."
            }
            require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Invalid GitHub file path."
            }
        }

        /**
         * Prevent the autonomous coding API from changing the repository's own execution,
         * CI, dependency-resolution or build-bootstrap controls. Those changes require a
         * separately reviewed, owner-authored change until a dedicated approval workflow exists.
         */
        internal fun requireWritablePath(path: String) {
            requireSafePath(path)
            val normalized = path.lowercase(java.util.Locale.ROOT)
            val segments = normalized.split('/')
            val fileName = segments.last()
            val blocked = normalized == ".gitmodules" ||
                normalized == ".gitlab-ci.yml" || normalized == ".travis.yml" ||
                normalized == "gradle.properties" ||
                normalized == "gradle/wrapper/gradle-wrapper.properties" ||
                normalized == "gradle/libs.versions.toml" ||
                normalized == "build.gradle" || normalized == "build.gradle.kts" ||
                normalized == "settings.gradle" || normalized == "settings.gradle.kts" ||
                normalized == "gradlew" || normalized == "gradlew.bat" ||
                segments.firstOrNull()?.let { it in setOf(".github", ".gitlab", ".circleci") } == true ||
                fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts") ||
                fileName == "jenkinsfile" || fileName == "azure-pipelines.yml" ||
                fileName == "dockerfile" || fileName.startsWith("docker-compose")
            require(!blocked) {
                "GLAFIRA GitHub coding API cannot modify CI, build bootstrap, dependency or execution-control files without a separate owner-reviewed workflow."
            }
        }

        private fun requireRef(ref: String) {
            require(ref.isNotBlank() && ref.length <= 200 && !ref.contains("..") && !ref.startsWith("-")) {
                "Invalid GitHub ref."
            }
        }

        private fun encodePath(value: String): String =
            value.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }

        private fun encodeQuery(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
