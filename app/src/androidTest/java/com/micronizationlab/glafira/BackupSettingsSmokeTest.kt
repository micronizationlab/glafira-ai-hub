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
                "GLAFIRA home screen did not open",
                device.wait(Until.hasObject(By.descContains("Открыть настройки")), 20_000)
            )
            device.findObject(By.descContains("Открыть настройки")).click()
            assertTrue(
                "Settings screen did not open",
                device.wait(Until.hasObject(By.text("НАСТРОЙКИ")), 10_000)
            )

            // Settings uses a Compose verticalScroll Column, which is not consistently
            // exposed as a UiScrollable node by all emulator API levels. Scroll the
            // actual device viewport in bounded steps and wait for the semantic node.
            val backupHeading = By.text("РЕЗЕРВНОЕ КОПИРОВАНИЕ")
            var headingVisible = device.wait(Until.hasObject(backupHeading), 1_000)
            repeat(12) {
                if (!headingVisible) {
                    device.swipe(
                        device.displayWidth / 2,
                        (device.displayHeight * 0.82f).toInt(),
                        device.displayWidth / 2,
                        (device.displayHeight * 0.30f).toInt(),
                        18
                    )
                    headingVisible = device.wait(Until.hasObject(backupHeading), 500)
                }
            }
            assertTrue("Backup section is not reachable in Settings", headingVisible)

            val buttonSelector = By.desc("Создать резервную копию сейчас")
            var saveButton = device.wait(Until.findObject(buttonSelector), 1_000)
            repeat(8) {
                if (saveButton == null) {
                    device.swipe(
                        device.displayWidth / 2,
                        (device.displayHeight * 0.82f).toInt(),
                        device.displayWidth / 2,
                        (device.displayHeight * 0.30f).toInt(),
                        18
                    )
                    saveButton = device.wait(Until.findObject(buttonSelector), 500)
                }
            }
            assertNotNull("Manual backup button is not visible in Settings", saveButton)
            val saveAction = requireNotNull(saveButton)
            assertTrue("Manual backup button is disabled before input", saveAction.isEnabled)
            val saveBounds = saveAction.visibleBounds
            assertTrue(
                "Manual backup action is not visible",
                saveBounds.width() > 0 && saveBounds.height() > 0
            )
            saveAction.click()
            device.waitForIdle()
            val guidance = device.wait(
                Until.findObject(By.descContains("Сначала выбери папку")), 10_000
            )
            assertNotNull(
                "Missing-folder guidance was not shown beside the manual backup action",
                guidance
            )
        } finally {
            scenario.close()
        }
    }
}
