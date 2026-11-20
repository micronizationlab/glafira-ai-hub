package com.micronizationlab.glafira

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlafiraRegressionUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsShowsBridgeAndManualBackupActionIsAvailable() {
        compose.onNodeWithText("⚙").performClick()

        compose.onNodeWithText("ПОДКЛЮЧЕНИЕ К DESKTOP").assertExists()
        compose.onNodeWithText("ЗАПУСТИТЬ").assertExists().assertIsEnabled()
        val statusNode = compose.onNodeWithTag("backup_status")
        val before = statusNode.fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        compose.onNodeWithText("Сохранить сейчас").assertExists().assertIsEnabled().performClick()
        compose.waitForIdle()
        val after = statusNode.fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertTrue("Manual backup action must update its visible status.", before != after)
    }

    @Test
    fun councilActionIsClickableAndExplainsEmptyQuestion() {
        compose.onNodeWithText("🏛").performClick()

        compose.onNodeWithText("СОБРАТЬ СОВЕТ").assertExists().assertIsEnabled().performClick()
        compose.onNodeWithText("Сначала напиши вопрос для совета. Кнопка доступна — поле вопроса пока пустое.").assertExists()
    }

    @Test
    fun taskQueueIsReachableAndHasAddTaskAction() {
        compose.onNodeWithText("☷").performClick()

        compose.onNodeWithText("ОЧЕРЕДЬ ЗАДАЧ").assertExists()
        compose.onNodeWithText("＋ ЗАДАЧА").assertExists().assertIsEnabled().performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Проверить UI задачу")
        fields[1].performTextInput("Сохранить тестовое поручение в локальной очереди.")
        compose.onNodeWithText("ДОБАВИТЬ").performClick()
        compose.onNodeWithText("Задача сохранена в зашифрованной очереди.").assertExists()
        compose.onNodeWithText("Проверить UI задачу").assertExists()
    }
}
