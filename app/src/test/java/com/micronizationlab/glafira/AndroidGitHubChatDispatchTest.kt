package com.micronizationlab.glafira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGitHubChatDispatchTest {
    @Test
    fun recognizesExplicitRepositoryTreeCommand() {
        val intent = AndroidGitHubChatIntentParser.parse("Покажи дерево репозитория")
        assertNotNull(intent)
        assertEquals(AndroidGitHubOperation.REPOSITORY_TREE, intent?.operation)
        assertEquals("main", intent?.baseRef)
        assertEquals(
            AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
            intent?.repository
        )
    }

    @Test
    fun recognizesReadFileAndPreservesSafeRelativePath() {
        val intent = AndroidGitHubChatIntentParser.parse("Прочитай файл app/src/main/AndroidManifest.xml")
        assertNotNull(intent)
        assertEquals(AndroidGitHubOperation.READ_FILE, intent?.operation)
        assertEquals("app/src/main/AndroidManifest.xml", intent?.path)
    }

    @Test
    fun ignoresUnrecognizedNaturalLanguage() {
        assertNull(AndroidGitHubChatIntentParser.parse("Посмотри, что нового в проекте"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        assertTrue(
            runCatching { AndroidGitHubChatIntentParser.parse("Прочитай файл ../../secrets.txt") }
                .isFailure
        )
        assertTrue(
            runCatching { AndroidGitHubChatIntentParser.parse("Прочитай файл /etc/passwd") }
                .isFailure
        )
    }

    @Test
    fun factoryCreatesOnlyScopedReadOnlyCoreTask() {
        val intent = requireNotNull(
            AndroidGitHubChatIntentParser.parse("Прочитай файл README.md")
        )
        val task = AndroidGitHubChatTaskFactory.create("project-main", intent, 1_000L)
        val payload = JSONObject(task.payload)

        assertEquals("github.file.read", task.taskType)
        assertEquals("owner.android.chat.github", task.origin)
        assertEquals(setOf("github.repo.read"), task.requiredScopes)
        assertEquals("README.md", payload.getString("path"))
        assertTrue(payload.getString("actionSummary").contains("README.md"))
        assertEquals(1_000L, task.createdAtMillis)
        assertEquals(1_801_000L, task.deadlineAtMillis)
        assertEquals(0, task.attemptCount)
        assertEquals(GlafiraTaskState.QUEUED, task.state)
        assertFalse(payload.has("accessToken"))
        GlafiraTaskPolicy.validate(task)
    }

    @Test
    fun identicalRapidRetriesShareStableTaskAndIdempotencyIdentity() {
        val intent = requireNotNull(
            AndroidGitHubChatIntentParser.parse("Прочитай файл README.md")
        )
        val first = AndroidGitHubChatTaskFactory.create("project-main", intent, 301_000L)
        val retry = AndroidGitHubChatTaskFactory.create("project-main", intent, 302_000L)
        assertEquals(first.taskId, retry.taskId)
        assertEquals(first.idempotencyKey, retry.idempotencyKey)
    }

    @Test
    fun intentionalRepeatOutsideIdempotencyWindowGetsNewIdentity() {
        val intent = requireNotNull(
            AndroidGitHubChatIntentParser.parse("Прочитай файл README.md")
        )
        val first = AndroidGitHubChatTaskFactory.create("project-main", intent, 301_000L)
        val later = AndroidGitHubChatTaskFactory.create("project-main", intent, 601_000L)
        assertFalse(first.taskId == later.taskId)
    }

    @Test
    fun factoryRejectsMutationIntents() {
        val mutation = AndroidGitHubIntent(
            repository = AndroidGitHubIntentPreviewBuilder.ALLOWED_REPOSITORY,
            operation = AndroidGitHubOperation.CREATE_BRANCH,
            branchName = "feature/test"
        )
        assertTrue(runCatching {
            AndroidGitHubChatTaskFactory.create("project-main", mutation, 1_000L)
        }.isFailure)
    }
}
