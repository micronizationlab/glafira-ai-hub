package com.micronizationlab.glafira

import android.content.Intent
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSettingsSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun manualBackupActionRemainsEnabledAndExplainsMissingFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(
            "Could not disable biometric lock for the home-screen smoke test",
            context.getSharedPreferences("glafira_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("biometric_enabled", false).commit()
        )
        assertTrue(
            "Could not isolate backup-folder preference for the smoke test",
            context.getSharedPreferences(
                GlafiraBackupWorker.BACKUP_PREFS,
                android.content.Context.MODE_PRIVATE
            ).edit().remove(GlafiraBackupWorker.KEY_TREE_URI).commit()
        )
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        try {
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("СЕТЬ АГЕНТОВ · 2003").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Открыть настройки").performClick()
            compose.onNodeWithText("НАСТРОЙКИ").assertExists("Settings screen did not open")
            compose.onNodeWithContentDescription("Создать резервную копию сейчас")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            compose.onNodeWithContentDescription(
                "Сначала выбери папку Google Drive или Яндекс Диска."
            ).assertExists("Missing-folder guidance was not shown beside the manual backup action")
        } finally {
            scenario.close()
        }
    }
}
