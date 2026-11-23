package com.micronizationlab.glafira

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class GitHubAuthenticatedUser(
    val id: Long,
    val login: String,
    val name: String?
)

internal data class GitHubInstallation(
    val id: Long,
    val accountLogin: String,
    val repositorySelection: String
)

internal data class GitHubRepository(
    val id: Long,
    val fullName: String,
    val isPrivate: Boolean,
    val defaultBranch: String
)

/**
 * Minimal GitHub REST client. All requests are HTTPS and pinned to api.github.com;
 * callers must pass an owner-authorized token obtained from GitHub Device Flow.
 */
internal class GitHubApiClient {
    suspend fun getAuthenticatedUser(accessToken: String): GitHubAuthenticatedUser =
        withContext(Dispatchers.IO) {
            val json = getJson("/user", accessToken)
            GitHubAuthenticatedUser(
                id = json.getLong("id"),
                login = json.getString("login"),
                name = json.optString("name").takeIf(String::isNotBlank)
            )
        }

    suspend fun listInstallations(accessToken: String): List<GitHubInstallation> =
        withContext(Dispatchers.IO) {
            val json = getJson("/user/installations?per_page=100", accessToken)
            val items = json.optJSONArray("installations") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val account = item.optJSONObject("account")
                    add(
                        GitHubInstallation(
                            id = item.optLong("id"),
                            accountLogin = account?.optString("login").orEmpty(),
                            repositorySelection = item.optString("repository_selection", "unknown")
                        )
                    )
                }
            }
        }

    suspend fun listInstallationRepositories(
        accessToken: String,
        installationId: Long
    ): List<GitHubRepository> = withContext(Dispatchers.IO) {
        require(installationId > 0L) { "Invalid GitHub installation ID." }
        val json = getJson(
            "/user/installations/$installationId/repositories?per_page=100",
            accessToken
        )
        val items = json.optJSONArray("repositories") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(
                    GitHubRepository(
                        id = item.optLong("id"),
                        fullName = item.optString("full_name"),
                        isPrivate = item.optBoolean("private"),
                        defaultBranch = item.optString("default_branch", "main")
                    )
                )
            }
        }
    }

    private fun getJson(path: String, accessToken: String): JSONObject {
        require(accessToken.isNotBlank()) { "GitHub is not connected." }
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains("..")) {
            "Invalid GitHub API path."
        }
        val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("User-Agent", "GLAFIRA-AI-HUB-Android")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) {
                when (code) {
                    401 -> "GitHub authorization expired or was revoked. Reconnect GitHub."
                    403 -> "GitHub denied this operation or the API rate limit was reached."
                    404 -> "GitHub resource was not found or is not accessible to this App."
                    else -> "GitHub API request failed (HTTP $code)."
                }
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
    }
}
