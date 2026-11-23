package com.micronizationlab.glafira

import org.json.JSONObject
import java.util.UUID

/** Deterministic first vertical: explicit GitHub read commands only. */
internal object AndroidGitHubChatIntentParser {
    private val treeCommands = setOf(
        "покажи дерево репозитория", "покажи дерево github",
        "покажи структуру репозитория", "show repository tree",
        "show github repository tree"
    )
    private val readFilePattern = Regex(
        "^(?:прочитай файл|покажи содержимое файла|read file)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )

    fun parse(message: String): AndroidGitHubIntent? {
        val normalized = message.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized in treeCommands) {
            return AndroidGitHubIntent(
                AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
                AndroidGitHubOperation.REPOSITORY_TREE,
                baseRef = "main"
            ).also { AndroidGitHubIntentPreviewBuilder.build(it) }
        }
        val match = readFilePattern.matchEntire(message.trim()) ?: return null
        val path = match.groupValues[1].trim().trim('"', 39.toChar())
        require(path.isNotBlank()) { "GITHUB_FILE_PATH_INVALID" }
        return AndroidGitHubIntent(
            AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
            AndroidGitHubOperation.READ_FILE,
            baseRef = "main",
            path = path
        ).also { AndroidGitHubIntentPreviewBuilder.build(it) }
    }
}

/** Creates an owner-authored read-only task for the existing durable Core queue. */
internal object AndroidGitHubChatTaskFactory {
    fun create(
        projectId: String,
        intent: AndroidGitHubIntent,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraTask {
        require(projectId.isNotBlank())
        require(nowMillis > 0L)
        val preview = AndroidGitHubIntentPreviewBuilder.build(intent)
        require(preview.operation == AndroidGitHubOperation.REPOSITORY_TREE ||
            preview.operation == AndroidGitHubOperation.READ_FILE) {
            "GITHUB_CHAT_MUTATION_NOT_SUPPORTED"
        }
        val taskType = when (preview.operation) {
            AndroidGitHubOperation.REPOSITORY_TREE -> "github.repository.tree"
            AndroidGitHubOperation.READ_FILE -> "github.file.read"
            else -> error("GITHUB_CHAT_MUTATION_NOT_SUPPORTED")
        }
        val actionSummary = when (preview.operation) {
            AndroidGitHubOperation.REPOSITORY_TREE ->
                "Показать дерево " + preview.repository + " на ветке " + preview.baseRef
            AndroidGitHubOperation.READ_FILE ->
                "Прочитать файл " + preview.path + " из " + preview.repository + " на ветке " + preview.baseRef
            else -> error("GITHUB_CHAT_MUTATION_NOT_SUPPORTED")
        }
        val payload = JSONObject()
            .put("repository", preview.repository)
            .put("baseRef", preview.baseRef)
            .put("actionSummary", actionSummary)
            .apply { preview.path?.let { put("path", it) } }
            .toString()
        return GlafiraTask(
            taskId = UUID.randomUUID().toString(),
            projectId = projectId,
            taskType = taskType,
            payload = payload,
            origin = "owner.android.chat.github",
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            deadlineAtMillis = nowMillis + 30L * 60L * 1000L,
            idempotencyKey = UUID.randomUUID().toString(),
            requiredScopes = setOf("github.repo.read")
        ).also(GlafiraTaskPolicy::validate)
    }
}
