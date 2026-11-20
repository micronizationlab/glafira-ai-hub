package com.micronizationlab.glafira

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class GlafiraRegressionUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsShowsBridgeAndManualBackupActionIsAvailable() {
        compose.onNodeWithText("⚙").performClick()

        compose.onNodeWithText("ПОДКЛЮЧЕНИЕ К DESKTOP").assertExists()
        compose.onNodeWithText("Сохранить сейчас").assertExists().assertIsEnabled()
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
        compose.onNodeWithText("＋ ЗАДАЧА").assertExists().assertIsEnabled()
    }
}
