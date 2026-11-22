package com.micronizationlab.glafira

import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubRepositoryOperationsTest {
    @Test
    fun allowsFeatureBranches() {
        GitHubRepositoryOperations.requireWritableBranch("feat/github-write-api")
        GitHubRepositoryOperations.requireWritableBranch("fix/voice-crash-2026")
    }

    @Test
    fun rejectsProtectedBranches() {
        listOf("main", "master", "main/hotfix").forEach { branch ->
            assertThrows(IllegalArgumentException::class.java) {
                GitHubRepositoryOperations.requireWritableBranch(branch)
            }
        }
    }

    @Test
    fun rejectsMalformedBranchNames() {
        listOf("../main", "feature//unsafe", "feature/", "feature with spaces", "-leading").forEach { branch ->
            assertThrows(IllegalArgumentException::class.java) {
                GitHubRepositoryOperations.requireWritableBranch(branch)
            }
        }
    }

    @Test
    fun acceptsRepositoryRelativePaths() {
        GitHubRepositoryOperations.requireSafePath("app/src/main/Example.kt")
    }

    @Test
    fun rejectsUnsafeFilePaths() {
        listOf("", "/absolute/path", "../outside", "app/../secret", "folder/", "folder\\\\secret").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                GitHubRepositoryOperations.requireSafePath(path)
            }
        }
    }
}
