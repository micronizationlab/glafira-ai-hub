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
        // UI smoke tests run against an isolated emulator app install. Restore the
        // canonical built-ins so earlier UI cases cannot leave deleted-project state.
        deletedProjectStore(context.filesDir).write(emptySet())
        saveProjects(context, defaultProjects())
        assertTrue("Built-in VodorodAktiv workspace was not seeded for UI tests",
            loadProjects(context).any { it.id == "vodorodaktiv" && it.name == "ВодородАктив" })
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
            assertTrue("GLAFIRA chat workspace did not open",
                it.wait(Until.hasObject(By.descContains("Выбрать агента: ГЛАФИРА")), 20_000))
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
    fun chatWorkspaceExposesProjectAgentModuleAndActionSelectors() {
        val device = launch()

        assertTrue("GLAFIRA chat is not the default workspace",
            device.wait(Until.hasObject(By.textContains("ГЛАФИРА")), 5_000))

        device.findObject(By.descContains("Выбрать проект: Личное")).click()
        assertTrue("Built-in VodorodAktiv project is missing from the picker",
            device.wait(Until.hasObject(By.text("ВодородАктив")), 5_000))
        device.findObject(By.text("ВодородАктив")).click()
        assertTrue("Project selector did not switch to VodorodAktiv",
            device.wait(Until.hasObject(By.textContains("ВодородАктив")), 5_000))

        device.findObject(By.desc("Выбрать проект: ВодородАктив")).click()
        device.findObject(By.desc("Удалить проект: ВодородАктив")).click()
        assertTrue("Project delete icon did not open the confirmation dialog",
            device.wait(Until.hasObject(By.text("Удалить проект?")), 5_000))
        assertTrue("Delete confirmation does not identify the selected project",
            device.hasObject(By.text("Все чаты проекта «ВодородАктив» и его закрепления будут безвозвратно удалены.")))
        device.findObject(By.text("Отмена")).click()
        device.findObject(By.desc("Выбрать проект: ВодородАктив")).click()
        assertTrue("Cancelling deletion must keep the selected project",
            device.wait(Until.hasObject(By.text("ВодородАктив")), 5_000))
        device.pressBack()

        device.findObject(By.desc("Выбрать агента: ГЛАФИРА")).click()
        assertTrue("Agent picker did not expose the Analyst agent",
            device.wait(Until.hasObject(By.text("АНАЛИТИК")), 5_000))
        device.pressBack()

        device.findObject(By.text("Открыть модуль")).click()
        assertTrue("Module picker did not expose the planning module",
            device.wait(Until.hasObject(By.text("МОДУЛЬ · ПЛАНИРОВАНИЕ")), 5_000))

        device.pressBack()
        device.findObject(By.descContains("Открыть действия чата")).click()
        assertTrue("Bottom action menu did not expose agent transfer",
            device.wait(Until.hasObject(By.text("Передать агенту")), 5_000))
        assertTrue("Bottom action menu did not expose chat deletion",
            device.hasObject(By.text("Очистить чат")))
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
