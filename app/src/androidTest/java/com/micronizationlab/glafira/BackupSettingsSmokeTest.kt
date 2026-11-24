package com.micronizationlab.glafira

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSettingsSmokeTest {
    @Test
    fun manualBackupActionRemainsEnabledAndExplainsMissingFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        resetRecoveryJournalForUiSmokeTest(context)
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
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(
                "GLAFIRA chat workspace did not open",
                device.wait(Until.hasObject(By.descContains("Выбрать агента: ГЛАФИРА")), 20_000)
            )
            val actionsButton = device.findObject(By.descContains("Открыть действия чата"))
            assertTrue(
                "Chat actions menu is not visible",
                actionsButton.visibleBounds.let { it.width() > 0 && it.height() > 0 }
            )
            actionsButton.click()
            val settingsButton = device.wait(Until.findObject(By.text("Настройки")), 5_000)
            assertNotNull("Settings action is missing from the bottom chat menu", settingsButton)
            requireNotNull(settingsButton).click()
            assertTrue(
                "Settings screen did not open",
                device.wait(Until.hasObject(By.text("НАСТРОЙКИ")), 10_000)
            )

            // Compose Settings is a verticalScroll Column. The heading is not a stable
            // accessibility selector on every emulator API level, so locate the actual
            // actionable semantic node while moving through the viewport in bounded steps.
            val buttonSelector = By.descContains("Создать резервную копию сейчас")
            var saveAction = device.wait(Until.findObject(buttonSelector), 500)
            repeat(16) {
                // A non-empty visibleBounds can still be only a clipped sliver of a
                // Compose button. Keep scrolling until the hit target is substantially visible.
                if (saveAction == null || saveAction.visibleBounds.height() < 80) {
                    device.swipe(
                        device.displayWidth / 2,
                        (device.displayHeight * 0.82f).toInt(),
                        device.displayWidth / 2,
                        (device.displayHeight * 0.28f).toInt(),
                        18
                    )
                    device.waitForIdle()
                    saveAction = device.wait(Until.findObject(buttonSelector), 500)
                }
            }
            assertNotNull("Manual backup button is not reachable in Settings", saveAction)
            val button = requireNotNull(saveAction)
            assertTrue("Manual backup button is disabled before input", button.isEnabled)
            val bounds = button.visibleBounds
            assertTrue(
                "Manual backup action does not expose a sufficiently large visible hit target: $bounds",
                bounds.width() > 0 && bounds.height() >= 80
            )
            device.click(bounds.centerX(), bounds.centerY())
            device.waitForIdle()
            val guidance = device.wait(
                Until.findObject(By.descContains("Сначала выбери папку")), 10_000
            )
            assertNotNull(
                "Missing-folder guidance was not shown after the manual backup action",
                guidance
            )
        } finally {
            scenario.close()
        }
    }
}
