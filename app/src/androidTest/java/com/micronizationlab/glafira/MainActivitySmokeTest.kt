package com.micronizationlab.glafira

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private fun launch(): UiDevice {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        context.startActivity(intent)
        return UiDevice.getInstance(instrumentation).also {
            assertTrue(
                "GLAFIRA home screen did not open",
                it.wait(Until.hasObject(By.descContains("Открыть очередь задач")), 20_000)
            )
        }
    }

    @Test
    fun mainActivityLaunchesAndRemainsForegrounded() {
        val device = launch()
        assertTrue(device.currentPackageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test
    fun taskQueueEntryOpensAndShowsAddTaskDialog() {
        val device = launch()
        device.findObject(By.descContains("Открыть очередь задач")).click()
        assertTrue(
            "Task queue screen did not open",
            device.wait(Until.hasObject(By.text("ОЧЕРЕДЬ ЗАДАЧ")), 10_000)
        )
        device.findObject(By.text("＋ ЗАДАЧА")).click()
        assertTrue(device.wait(Until.hasObject(By.text("НОВАЯ ЗАДАЧА")), 10_000))
    }

    @Test
    fun councilEntryOpensAndItsActionButtonIsEnabled() {
        val device = launch()
        device.findObject(By.descContains("Открыть совет директоров")).click()
        assertTrue(
            "Council screen did not open",
            device.wait(Until.hasObject(By.text("СОБРАТЬ СОВЕТ")), 10_000)
        )
        val button = device.findObject(By.text("СОБРАТЬ СОВЕТ"))
        assertTrue("Council action button is disabled", button.isEnabled)
    }
}
