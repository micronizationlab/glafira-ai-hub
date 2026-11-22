package com.micronizationlab.glafira

import android.content.Context
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
    private fun prepareUnlockedHome(context: Context) {
        assertTrue(
            "Could not disable biometric lock for the home-screen smoke test",
            context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("biometric_enabled", false).commit()
        )
    }

    private fun launch(): UiDevice {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        prepareUnlockedHome(context)
        forceStopTargetApp(instrumentation)
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        context.startActivity(intent)
        return UiDevice.getInstance(instrumentation).also {
            it.waitForIdle()
            assertTrue("GLAFIRA home screen did not open",
                it.wait(Until.hasObject(By.text("СЕТЬ АГЕНТОВ · 2003")), 20_000))
        }
    }

    @Test
    fun mainActivityLaunchesAndRemainsForegrounded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prepareUnlockedHome(context)
        forceStopTargetApp(InstrumentationRegistry.getInstrumentation())
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
        val device = launch()
        device.findObject(By.desc("Открыть очередь задач")).click()
        assertTrue("Task queue screen did not open",
            device.wait(Until.hasObject(By.text("ОЧЕРЕДЬ ЗАДАЧ")), 10_000))
        device.findObject(By.text("＋ ЗАДАЧА")).click()
        assertTrue("Add task dialog did not open",
            device.wait(Until.hasObject(By.text("Добавить задачу в очередь")), 10_000))
    }

    @Test
    fun councilEntryOpensAndItsActionButtonIsEnabled() {
        val device = launch()
        device.findObject(By.desc("Открыть совет директоров")).click()
        assertTrue("Council screen did not open",
            device.wait(Until.hasObject(By.text("СОБРАТЬ СОВЕТ")), 10_000))
        val button = device.findObject(By.text("СОБРАТЬ СОВЕТ"))
        assertNotNull(button)
        assertTrue("Council action button is disabled", button.isEnabled)
        button.click()
        assertTrue("Empty-question guidance was not shown",
            device.wait(Until.hasObject(By.textContains("Сначала напиши вопрос")), 10_000))
    }
}
