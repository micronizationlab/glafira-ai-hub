package com.micronizationlab.glafira

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Side-effect-free description of a user-requested GitHub operation.
 * This model never performs network I/O and is not an authorization grant.
 */
enum class AndroidGitHubOperation {
    REPOSITORY_TREE,
    READ_FILE,
    CREATE_BRANCH,
    WRITE_FILE,
    CREATE_DRAFT_PULL_REQUEST
}

data class AndroidGitHubIntent(
    val repository: String,
    val operation: AndroidGitHubOperation,
    val baseRef: String = "main",
    val branchName: String? = null,
    val path: String? = null,
    val content: String? = null,
    val pullRequestTitle: String? = null
)

data class AndroidGitHubIntentPreview(
    val repository: String,
    val operation: AndroidGitHubOperation,
    val baseRef: String,
    val branchName: String?,
    val path: String?,
    val contentSha256: String?,
    val contentBytes: Int?,
    val pullRequestTitle: String?,
    val previewHash: String
)

/**
 * Builds a deterministic, redacted preview for the first allow-listed repository.
 * The caller must still obtain fresh owner authentication and explicit approval bound
 * to previewHash before any mutating API is called.
 */
object AndroidGitHubIntentPreviewBuilder {
    const val ALLOWED_REPOSITORY = "micronizationlab/glafira-ai-hub"
    private const val MAX_FILE_BYTES = 1_048_576

    fun build(intent: AndroidGitHubIntent): AndroidGitHubIntentPreview {
        require(intent.repository == ALLOWED_REPOSITORY) { "GITHUB_REPOSITORY_NOT_ALLOWED" }
        require(validRef(intent.baseRef)) { "GITHUB_BASE_REF_INVALID" }

        val branch = intent.branchName
        val path = intent.path
        val content = intent.content
        val title = intent.pullRequestTitle?.trim()

        when (intent.operation) {
            AndroidGitHubOperation.REPOSITORY_TREE -> {
                require(branch == null && path == null && content == null && title == null) {
                    "GITHUB_INTENT_FIELDS_INVALID"
                }
            }
            AndroidGitHubOperation.READ_FILE -> {
                require(path != null && safePath(path) && content == null && branch == null && title == null) {
                    "GITHUB_FILE_PATH_INVALID"
                }
            }
            AndroidGitHubOperation.CREATE_BRANCH -> {
                require(branch != null && validBranch(branch) && isWritableFeatureBranch(branch, intent.baseRef) && path == null && content == null && title == null) {
                    "GITHUB_BRANCH_INVALID"
                }
            }
            AndroidGitHubOperation.WRITE_FILE -> {
                require(branch != null && validBranch(branch) && isWritableFeatureBranch(branch, intent.baseRef) && path != null && safePath(path) && content != null && title == null) {
                    "GITHUB_WRITE_FIELDS_INVALID"
                }
                require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_FILE_BYTES) {
                    "GITHUB_FILE_TOO_LARGE"
                }
            }
            AndroidGitHubOperation.CREATE_DRAFT_PULL_REQUEST -> {
                require(branch != null && validBranch(branch) && isWritableFeatureBranch(branch, intent.baseRef) && title != null &&
                    title.isNotBlank() && title.length <= 200 && path == null && content == null
                ) { "GITHUB_PULL_REQUEST_FIELDS_INVALID" }
            }
        }

        val contentHash = content?.let(::sha256)
        val bytes = content?.toByteArray(StandardCharsets.UTF_8)?.size
        val canonical = listOf(
            intent.repository,
            intent.operation.name,
            intent.baseRef,
            branch.orEmpty(),
            path.orEmpty(),
            contentHash.orEmpty(),
            bytes?.toString().orEmpty(),
            title.orEmpty()
        ).joinToString("\n")
        return AndroidGitHubIntentPreview(
            repository = intent.repository,
            operation = intent.operation,
            baseRef = intent.baseRef,
            branchName = branch,
            path = path,
            contentSha256 = contentHash,
            contentBytes = bytes,
            pullRequestTitle = title,
            previewHash = sha256(canonical)
        )
    }

    private fun validRef(value: String): Boolean =
        value.length in 1..128 &&
            !value.startsWith("-") &&
            value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) &&
            !value.contains("..") &&
            !value.endsWith("/") &&
            !value.contains("//")

    private fun validBranch(value: String): Boolean =
        validRef(value) && value.length <= 120 &&
            !value.startsWith("refs/") &&
            !value.endsWith(".lock") &&
            !value.contains("@{")

    /** Keep preview validation standalone; the API layer repeats this guard at dispatch time. */
    private fun isWritableFeatureBranch(branch: String, baseRef: String): Boolean {
        if (!validBranch(branch) || branch == baseRef) return false
        if (branch == "main" || branch == "master" ||
            branch.startsWith("main/") || branch.startsWith("master/") ||
            branch.startsWith("refs/") || branch.endsWith(".lock") ||
            branch.endsWith("/") || branch.contains("..") || branch.contains("//") ||
            branch.contains("@{")) return false
        return branch.split('/').all { segment ->
            !segment.startsWith(".") && !segment.endsWith(".") && !segment.endsWith(".lock")
        }
    }

    private fun safePath(value: String): Boolean {
        if (value.isBlank() || value.length > 512 || value.startsWith("/") || value.startsWith("\\")) return false
        val segments = value.replace('\\', '/').split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
        return segments.none { it.equals(".git", ignoreCase = true) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
