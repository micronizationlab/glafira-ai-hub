package com.micronizationlab.glafira

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private fun launch(): UiDevice {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return UiDevice.getInstance(instrumentation).also {
            assertTrue("GLAFIRA home screen did not open", it.wait(Until.hasObject(By.desc("Открыть очередь задач")), 10_000))
        }
    }

    @Test
    fun mainActivityLaunchesAndRemainsForegrounded() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
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
        val device = launch()
        device.findObject(By.desc("Открыть очередь задач")).click()
        assertTrue("Task queue screen did not open", device.wait(Until.hasObject(By.text("ОЧЕРЕДЬ ЗАДАЧ")), 5_000))
        device.findObject(By.text("＋ ЗАДАЧА")).click()
        assertTrue("Add task dialog did not open", device.wait(Until.hasObject(By.text("Добавить задачу в очередь")), 5_000))
    }

    @Test
    fun councilEntryOpensAndItsActionButtonIsEnabled() {
        val device = launch()
        device.findObject(By.desc("Открыть совет директоров")).click()
        assertTrue("Council screen did not open", device.wait(Until.hasObject(By.text("СОБРАТЬ СОВЕТ")), 5_000))
        val button = device.findObject(By.text("СОБРАТЬ СОВЕТ"))
        assertNotNull(button)
        assertTrue("Council action button is disabled", button.isEnabled)
        button.click()
        assertTrue("Empty-question guidance was not shown",
            device.wait(Until.hasObject(By.textContains("Сначала напиши вопрос")), 5_000))
    }

    @Test
    fun backupManualActionRemainsEnabledAndExplainsMissingFolder() {
        val device = launch()
        device.findObject(By.desc("Открыть настройки")).click()
        var saveButton = device.findObject(By.text("Сохранить сейчас"))
        repeat(8) {
            if (saveButton == null) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 4, 12)
                saveButton = device.findObject(By.text("Сохранить сейчас"))
            }
        }
        assertNotNull("Manual backup button is not visible in Settings", saveButton)
        assertTrue("Manual backup button is disabled before input", saveButton!!.isEnabled)
        saveButton!!.click()
        assertTrue("Missing-folder explanation was not shown",
            device.wait(Until.hasObject(By.textContains("Сначала выбери папку")), 5_000))
    }
}
