package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubConnectCommandTest {
    @Test
    fun recognizesExplicitRussianConnectionCommands() {
        assertTrue(isGitHubConnectCommand("Подключи меня к GitHub"))
        assertTrue(isGitHubConnectCommand("Глафира, подключи GitHub!"))
        assertTrue(isGitHubConnectCommand("подключить меня к github"))
    }

    @Test
    fun recognizesExplicitEnglishConnectionCommands() {
        assertTrue(isGitHubConnectCommand("Connect me to GitHub"))
        assertTrue(isGitHubConnectCommand("open GitHub connection"))
    }

    @Test
    fun doesNotCaptureRepositoryWorkOrUnrelatedMessages() {
        assertFalse(isGitHubConnectCommand("Проверь репозиторий"))
        assertFalse(isGitHubConnectCommand("Создай ветку в GitHub"))
        assertFalse(isGitHubConnectCommand("Расскажи, что такое GitHub"))
        assertFalse(isGitHubConnectCommand(""))
    }
}
