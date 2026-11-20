package com.micronizationlab.glafira

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithText("Сохранить сейчас").assertExists().assertIsEnabled().performClick()
        val actionableFeedback = listOf(
            "Сначала выбери папку",
            "Введи пароль",
            "Нет сохранённого разрешения",
            "Облачная папка доступна только для чтения",
            "Резервная копия поставлена в очередь"
        ).any { message ->
            compose.onAllNodes(hasText(message, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Manual backup action must explain its current state.", actionableFeedback)
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
