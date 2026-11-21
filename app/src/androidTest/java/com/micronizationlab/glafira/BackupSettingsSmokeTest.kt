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
        assertTrue(
            "Could not isolate backup-folder preference for the smoke test",
            context.getSharedPreferences(
                GlafiraBackupWorker.BACKUP_PREFS,
                android.content.Context.MODE_PRIVATE
            ).edit().remove(GlafiraBackupWorker.KEY_TREE_URI).commit()
        )
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("GLAFIRA home screen did not open",
                device.wait(Until.hasObject(By.desc("Открыть настройки")), 10_000))
            device.findObject(By.desc("Открыть настройки")).click()

            var saveButton = device.findObject(By.text("Сохранить сейчас"))
            repeat(8) {
                if (saveButton == null) {
                    device.swipe(
                        device.displayWidth / 2, device.displayHeight * 3 / 4,
                        device.displayWidth / 2, device.displayHeight / 4, 12
                    )
                    saveButton = device.findObject(By.text("Сохранить сейчас"))
                }
            }
            assertNotNull("Manual backup button is not visible in Settings", saveButton)
            assertTrue("Manual backup button is disabled before input", saveButton!!.isEnabled)
            saveButton!!.click()
            val guidance = device.wait(
                Until.findObject(By.textContains("Сначала выбери папку")), 3_000)
            assertNotNull("Missing-folder guidance was not shown beside the manual backup action", guidance)
        } finally {
            scenario.close()
        }
    }
}
