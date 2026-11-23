package com.micronizationlab.glafira

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
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

            // Scroll the actual Compose settings container. Keep and use the UiObject
            // returned by scrollIntoView: a second accessibility lookup can race with
            // Compose semantics recycling after the scroll and incorrectly report null.
            val scrollable = UiScrollable(UiSelector().scrollable(true)).setAsVerticalList()
            val backupHeading = scrollable.scrollIntoView(
                UiSelector().textContains("РЕЗЕРВНОЕ КОПИРОВАНИЕ")
            )
            assertNotNull("Backup section is not reachable in Settings", backupHeading)

            val saveButton = scrollable.scrollIntoView(
                UiSelector().descriptionContains("Создать резервную копию сейчас")
            )
            assertNotNull("Manual backup button is not visible in Settings", saveButton)
            assertTrue("Manual backup button is disabled before input", saveButton.isEnabled)
            assertTrue("Manual backup action is not visible", saveButton.visibleBounds.width() > 0 &&
                saveButton.visibleBounds.height() > 0)
            assertTrue("Manual backup action could not be clicked", saveButton.click())
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
