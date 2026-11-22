package com.micronizationlab.glafira

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun prepareUnlockedHome(context: Context) {
        assertTrue(
            "Could not disable biometric lock for the home-screen smoke test",
            context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("biometric_enabled", false).commit()
        )
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        prepareUnlockedHome(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        return ActivityScenario.launch<MainActivity>(intent).also {
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("СЕТЬ АГЕНТОВ · 2003").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun mainActivityLaunchesAndRemainsForegrounded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prepareUnlockedHome(context)
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        try {
            scenario.onActivity { activity ->
                assertTrue("MainActivity unexpectedly finished", !activity.isFinishing)
                assertTrue("MainActivity unexpectedly destroyed", !activity.isDestroyed)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun taskQueueEntryOpensAndShowsAddTaskDialog() {
        launch().use {
            compose.onNodeWithContentDescription("Открыть очередь задач").performClick()
            compose.onNodeWithText("ОЧЕРЕДЬ ЗАДАЧ").assertExists("Task queue screen did not open")
            compose.onNodeWithContentDescription("Добавить задачу").performClick()
            compose.onNodeWithText("Добавить задачу в очередь")
                .assertExists("Add task dialog did not open")
        }
    }

    @Test
    fun councilEntryOpensAndItsActionButtonIsEnabled() {
        launch().use {
            compose.onNodeWithContentDescription("Открыть совет директоров").performClick()
            compose.onNodeWithText("СОБРАТЬ СОВЕТ")
                .assertExists("Council screen did not open")
                .assertIsEnabled()
                .performClick()
            compose.onNodeWithText("Сначала напиши вопрос", substring = true)
                .assertExists("Empty-question guidance was not shown")
        }
    }
}
