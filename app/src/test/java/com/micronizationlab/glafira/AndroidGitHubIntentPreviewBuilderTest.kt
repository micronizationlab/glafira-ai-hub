package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGitHubIntentPreviewBuilderTest {
    @Test
    fun createsDeterministicPreviewWithoutExposingFileContents() {
        val intent = AndroidGitHubIntent(
            repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
            operation = AndroidGitHubOperation.WRITE_FILE,
            branchName = "feature/readme-update",
            path = "docs/README.md",
            content = "private draft text"
        )

        val first = AndroidGitHubIntentPreviewBuilder.build(intent)
        val second = AndroidGitHubIntentPreviewBuilder.build(intent)

        assertEquals(first.previewHash, second.previewHash)
        assertEquals(first.contentSha256, second.contentSha256)
        assertEquals(18, first.contentBytes)
        assertTrue(first.previewHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.contentSha256!!.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun changingContentInvalidatesPreviewHash() {
        val base = AndroidGitHubIntent(
            repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
            operation = AndroidGitHubOperation.WRITE_FILE,
            branchName = "feature/update",
            path = "docs/example.md",
            content = "version one"
        )
        val changed = base.copy(content = "version two")

        assertNotEquals(
            AndroidGitHubIntentPreviewBuilder.build(base).previewHash,
            AndroidGitHubIntentPreviewBuilder.build(changed).previewHash
        )
    }

    @Test
    fun draftPullRequestPreviewIncludesExactTrimmedTitle() {
        val preview = AndroidGitHubIntentPreviewBuilder.build(
            AndroidGitHubIntent(
                repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
                operation = AndroidGitHubOperation.CREATE_DRAFT_PULL_REQUEST,
                branchName = "feature/review",
                pullRequestTitle = "  Add reviewed draft  "
            )
        )

        assertEquals("Add reviewed draft", preview.pullRequestTitle)
    }

    @Test
    fun changingDraftPullRequestTitleInvalidatesPreviewHash() {
        val first = AndroidGitHubIntent(
            repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
            operation = AndroidGitHubOperation.CREATE_DRAFT_PULL_REQUEST,
            branchName = "feature/review",
            pullRequestTitle = "First title"
        )
        val second = first.copy(pullRequestTitle = "Second title")

        assertNotEquals(
            AndroidGitHubIntentPreviewBuilder.build(first).previewHash,
            AndroidGitHubIntentPreviewBuilder.build(second).previewHash
        )
    }

    @Test
    fun rejectsRepositoryOutsideExplicitAllowlist() {
        val intent = AndroidGitHubIntent(
            repository = "someone/other-repo",
            operation = AndroidGitHubOperation.REPOSITORY_TREE
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            AndroidGitHubIntentPreviewBuilder.build(intent)
        }
        assertEquals("GITHUB_REPOSITORY_NOT_ALLOWED", error.message)
    }

    @Test
    fun rejectsTraversalAndGitMetadataPaths() {
        for (path in listOf("../secret.txt", "src/../../secret", ".git/config")) {
            val intent = AndroidGitHubIntent(
                repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
                operation = AndroidGitHubOperation.READ_FILE,
                path = path
            )
            assertThrows(IllegalArgumentException::class.java) {
                AndroidGitHubIntentPreviewBuilder.build(intent)
            }
        }
    }

    @Test
    fun rejectsProtectedAndMalformedBranchesForEveryMutationPreview() {
        val rejected = listOf(
            "main", "master", "main/hotfix", "master/fix",
            "refs/heads/feature", "feature/.hidden", "feature/release.lock"
        )
        rejected.forEach { branch ->
            assertThrows(IllegalArgumentException::class.java) {
                AndroidGitHubIntentPreviewBuilder.build(
                    AndroidGitHubIntent(
                        repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
                        operation = AndroidGitHubOperation.CREATE_BRANCH,
                        branchName = branch
                    )
                )
            }
        }
    }

    @Test
    fun rejectsOversizedWriteBeforeAnyNetworkLayerExists() {
        val intent = AndroidGitHubIntent(
            repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
            operation = AndroidGitHubOperation.WRITE_FILE,
            branchName = "feature/large",
            path = "docs/large.txt",
            content = "x".repeat(1_048_577)
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            AndroidGitHubIntentPreviewBuilder.build(intent)
        }
        assertEquals("GITHUB_FILE_TOO_LARGE", error.message)
    }
}
