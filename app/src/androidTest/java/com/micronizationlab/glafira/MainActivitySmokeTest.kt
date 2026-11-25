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
        resetRecoveryJournalForUiSmokeTest(context)
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
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        context.startActivity(intent)
        return UiDevice.getInstance(instrumentation).also {
            assertTrue("GLAFIRA home screen did not open",
                it.wait(Until.hasObject(By.descContains("Открыть очередь задач")), 20_000))
        }
    }


    @Test
    fun chatWorkspaceProjectSelectorSwitchesAndDeleteCancelPreservesProject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Could not install a temporary test provider key",
            saveProviderKey(context, ProviderId.OPENAI, "instrumentation-placeholder-key"))
        try {
            val device = launch()
            device.findObject(By.text("ГЛАФИРА")).click()
            assertTrue("Chat workspace did not open",
                device.wait(Until.hasObject(By.desc("Выбрать проект: Личное")), 10_000))

            device.findObject(By.desc("Выбрать проект: Личное")).click()
            assertTrue("Built-in VodorodAktiv project is missing from chat picker",
                device.wait(Until.hasObject(By.text("ВодородАктив")), 5_000))
            device.findObject(By.text("ВодородАктив")).click()
            assertTrue("Chat did not switch to VodorodAktiv",
                device.wait(Until.hasObject(By.desc("Выбрать проект: ВодородАктив")), 5_000))

            device.findObject(By.desc("Выбрать проект: ВодородАктив")).click()
            device.findObject(By.desc("Удалить проект: ВодородАктив")).click()
            assertTrue("Project delete confirmation did not open",
                device.wait(Until.hasObject(By.text("Удалить проект?")), 5_000))
            assertTrue("Delete confirmation does not identify the selected project",
                device.hasObject(By.text("Все чаты проекта «ВодородАктив» и его закрепления будут безвозвратно удалены.")))
            device.findObject(By.text("Отмена")).click()
            assertTrue("Cancelling deletion must preserve the selected project",
                device.wait(Until.hasObject(By.desc("Выбрать проект: ВодородАктив")), 5_000))
            device.findObject(By.text("←")).click()
            assertTrue("Chat back action did not return to the home screen",
                device.wait(Until.hasObject(By.descContains("Открыть очередь задач")), 5_000))
        } finally {
            saveProviderKey(context, ProviderId.OPENAI, "")
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
        val device = launch()
        device.findObject(By.descContains("Открыть очередь задач")).click()
        assertTrue("Task queue screen did not open",
            device.wait(Until.hasObject(By.text("ОЧЕРЕДЬ ЗАДАЧ")), 10_000))
        device.findObject(By.text("＋ ЗАДАЧА")).click()
        assertTrue("Add task dialog did not open",
            device.wait(Until.hasObject(By.text("Добавить задачу в очередь")), 10_000))
    }

    @Test
    fun councilEntryOpensAndItsActionButtonIsEnabled() {
        val device = launch()
        device.findObject(By.descContains("Открыть совет директоров")).click()
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
