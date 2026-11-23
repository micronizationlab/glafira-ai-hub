package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes the fixed GitHub tool set from GLAFIRA Core.
 *
 * Model providers can propose a typed task, but cannot supply credentials, authorize it,
 * bypass owner scopes, or veto its execution. Core authorization runs before this handler.
 */
internal class GitHubCoreTaskHandlers(context: Context) {
    private val session = GitHubSession(context.applicationContext)
    private val operations = GitHubRepositoryOperations()

    fun handler(toolId: String): GlafiraTaskHandler = GlafiraTaskHandler { task ->
        require(task.taskType == toolId) { "GITHUB_TOOL_TASK_TYPE_MISMATCH" }
        execute(task)
    }

    private suspend fun execute(task: GlafiraTask): GlafiraTaskExecutionOutcome {
        val payload = try {
            JSONObject(task.payload)
        } catch (_: Exception) {
            return GlafiraTaskExecutionOutcome.PermanentFailure("GITHUB_TASK_INVALID")
        }
        val token = try {
            session.accessTokenOrRefresh()
        } catch (_: Exception) {
            null
        } ?: return GlafiraTaskExecutionOutcome.WaitingForOwner

        val repository = payload.optString("repository", "")
        val baseRef = payload.optString("baseRef", "main")
        return try {
            when (task.taskType) {
                "github.repository.tree" -> {
                    val tree = operations.readTree(token, repository, baseRef)
                    val entries = JSONArray()
                    tree.entries.take(MAX_TREE_ENTRIES).forEach { entry ->
                        entries.put(JSONObject().apply {
                            put("path", entry.path)
                            put("type", entry.type)
                            put("sha", entry.sha)
                            if (entry.size != null) put("size", entry.size)
                        })
                    }
                    GlafiraTaskExecutionOutcome.Succeeded(
                        JSONObject().put("entries", entries).put("truncated", tree.truncated ||
                            tree.entries.size > MAX_TREE_ENTRIES).toString()
                    )
                }
                "github.file.read" -> {
                    val content = operations.readFile(
                        token, repository, payload.getString("path"), baseRef
                    )
                    GlafiraTaskExecutionOutcome.Succeeded(content)
                }
                "github.branch.create" -> {
                    val branch = payload.getString("branch")
                    val sha = operations.createBranch(token, repository, branch, baseRef)
                    GlafiraTaskExecutionOutcome.Succeeded(
                        JSONObject().put("branch", branch).put("headSha", sha).toString()
                    )
                }
                "github.file.write" -> {
                    val result = operations.writeFile(
                        accessToken = token,
                        repository = repository,
                        path = payload.getString("path"),
                        content = payload.getString("content"),
                        message = payload.getString("message"),
                        branch = payload.getString("branch"),
                        existingContentSha = payload.optString("existingContentSha")
                            .takeIf(String::isNotBlank)
                    )
                    GlafiraTaskExecutionOutcome.Succeeded(
                        JSONObject().put("commitSha", result.commitSha)
                            .put("contentSha", result.contentSha).toString()
                    )
                }
                "github.pull_request.draft" -> {
                    val result = operations.openDraftPullRequest(
                        accessToken = token,
                        repository = repository,
                        title = payload.getString("title"),
                        body = payload.optString("body"),
                        headBranch = payload.getString("branch"),
                        baseBranch = baseRef
                    )
                    GlafiraTaskExecutionOutcome.Succeeded(
                        JSONObject().put("number", result.number)
                            .put("url", result.url).put("headSha", result.headSha)
                            .put("draft", result.draft).toString()
                    )
                }
                else -> GlafiraTaskExecutionOutcome.PermanentFailure("GITHUB_TOOL_NOT_REGISTERED")
            }
        } catch (_: IllegalArgumentException) {
            GlafiraTaskExecutionOutcome.PermanentFailure("GITHUB_TASK_INVALID")
        } catch (_: Exception) {
            // A write may have reached GitHub before the connection failed. Never replay it
            // blindly; move it to owner review so the remote state can be reconciled first.
            if (task.taskType in MUTATING_TOOLS) GlafiraTaskExecutionOutcome.WaitingForOwner
            else GlafiraTaskExecutionOutcome.RetryableFailure("GITHUB_READ_FAILED")
        }
    }

    private companion object {
        const val MAX_TREE_ENTRIES = 2_000
        val MUTATING_TOOLS = setOf(
            "github.branch.create", "github.file.write", "github.pull_request.draft"
        )
    }
}
