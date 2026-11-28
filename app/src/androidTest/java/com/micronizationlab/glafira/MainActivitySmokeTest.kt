package com.micronizationlab.glafira

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private lateinit var preferences: android.content.SharedPreferences
    private var originalBiometric = false
    private var preferenceCaptured = false

    @Before
    fun captureBiometricPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UiSmokeTestPreferenceLock.acquire()
        try {
            preferences = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
            recoverInterruptedBiometricPreference(context, preferences)
            originalBiometric = preferences.getBoolean("biometric_enabled", false)
            persistOriginalBiometricPreference(context, originalBiometric)
            preferenceCaptured = true
        } catch (failure: Throwable) {
            UiSmokeTestPreferenceLock.release()
            throw failure
        }
    }

    @After
    fun restoreBiometricPreference() {
        if (!preferenceCaptured) return
        try {
            restoreBiometricPreferenceAndRelock(
                InstrumentationRegistry.getInstrumentation().targetContext,
                preferences,
                originalBiometric
            )
        } finally {
            preferenceCaptured = false
            UiSmokeTestPreferenceLock.release()
        }
    }

    private fun clickFresh(device: UiDevice, selector: BySelector) {
        var lastStale: StaleObjectException? = null
        repeat(8) {
            val target = device.wait(Until.findObject(selector), 2_000) ?: return@repeat
            try {
                target.click()
                return
            } catch (stale: StaleObjectException) {
                lastStale = stale
                SystemClock.sleep(120)
            }
        }
        throw AssertionError("Accessibility node remained stale for selector: $selector", lastStale)
    }

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
            awaitHomeScreen(it, context)
        }
    }

    @Test
    fun homeScreenWaitFailureStillCollectsStartupDiagnostics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        prepareUnlockedHome(context)
        val device = UiDevice.getInstance(instrumentation)
        val injectedFailure = IllegalStateException("injected accessibility wait failure")
        val failure = assertThrows(IllegalStateException::class.java) {
            awaitHomeScreen(device, context, waitForWorkspace = {
                throw injectedFailure
            })
        }
        assertSame(injectedFailure, failure.cause)
        assertTrue(failure.message.orEmpty().contains("recovery=false"))
        assertTrue(failure.message.orEmpty().contains("biometric=false"))
        assertTrue(failure.message.orEmpty().contains("injected accessibility wait failure"))
    }

    @Test
    fun idleWaitFailureIsReportedAndAccessibilityFailureRemainsCause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        prepareUnlockedHome(context)
        val device = UiDevice.getInstance(instrumentation)
        val idleFailure = IllegalStateException("injected idle timeout")
        val accessibilityFailure = IllegalArgumentException("injected accessibility failure")

        val failure = assertThrows(IllegalStateException::class.java) {
            awaitHomeScreen(
                device = device,
                context = context,
                waitForIdle = { throw idleFailure },
                waitForWorkspace = { throw accessibilityFailure }
            )
        }

        assertSame(accessibilityFailure, failure.cause)
        assertTrue(failure.message.orEmpty().contains("idle=error: IllegalStateException: injected idle timeout"))
        assertTrue(failure.message.orEmpty().contains("accessibility=error: IllegalArgumentException"))
    }

    @Test
    fun recoveryDiagnosticsUseTheSharedGuardAndSafeHierarchyOmitsKnownWindowText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = launch()
        val knownText = "ГЛАФИРА"
        val knownDescription = "Открыть очередь задач"
        assertTrue("Known text fixture must be visible before hierarchy capture", device.hasObject(By.text(knownText)))
        assertTrue("Known description fixture must be visible before hierarchy capture",
            device.hasObject(By.descContains(knownDescription)))
        val stale = File(context.cacheDir, "glafira-rollback-recovery-ui-diagnostic-${System.nanoTime()}.tmp")
        val sentinel = File(stale, "preserve-this-undeletable-fixture")
        try {
            assertTrue(stale.mkdir())
            sentinel.writeText("diagnostic fixture must remain unchanged")
            assertTrue(recoveryPendingForDiagnostics(context))
            assertTrue("A non-empty recovery temp directory cannot be deleted by the shared guard", stale.isDirectory)
            assertEquals("diagnostic fixture must remain unchanged", sentinel.readText())
            val hierarchy = captureSafeWindowHierarchy()
            assertFalse("A real active window must be traversed, not replaced by the no-root fallback",
                hierarchy.contains("<no active accessibility root>"))
            assertTrue("Captured hierarchy must contain structural node data", hierarchy.contains("children="))
            assertFalse("Known visible text must not enter diagnostics", hierarchy.contains(knownText))
            assertFalse("Known accessibility description must not enter diagnostics", hierarchy.contains(knownDescription))
        } finally {
            check(!stale.exists() || stale.deleteRecursively())
        }
    }

    @Test
    fun interruptedBiometricPreferenceIsRecoveredBeforeTheNextSmokeTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        val original = preferences.getBoolean("biometric_enabled", false)
        try {
            persistOriginalBiometricPreference(context, true)
            assertTrue(preferences.edit().putBoolean("biometric_enabled", false).commit())
            recoverInterruptedBiometricPreference(context, preferences)
            assertTrue("A later smoke test must recover the persisted original preference",
                preferences.getBoolean("biometric_enabled", false))
        } finally {
            check(preferences.edit().putBoolean("biometric_enabled", original).commit())
        }
    }

    @Test
    fun hierarchyTruncationNoticeIsPreservedWithinCharacterLimit() {
        val device = launch()
        assertTrue("Home window must expose at least one child for the truncation fixture",
            device.hasObject(By.descContains("Открыть очередь задач")))
        val maximumCharacters = 200
        val hierarchy = captureSafeWindowHierarchy(maximumNodes = 1, maximumCharacters = maximumCharacters)
        assertTrue("Truncated hierarchy must retain an explicit notice: $hierarchy",
            hierarchy.contains("<hierarchy summary bounded; nodes=1>"))
        assertTrue("Hierarchy output must respect its configured character bound",
            hierarchy.length <= maximumCharacters)
    }

    @Test
    fun startupDiagnosticsRetainGatesWhenSafeHierarchyCaptureFails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        prepareUnlockedHome(context)
        assertTrue(preferences.edit().putBoolean("biometric_enabled", true).commit())
        val diagnostics = collectHomeScreenDiagnostics(
            UiDevice.getInstance(instrumentation),
            context,
            "idle=completed, accessibility=timed out",
            captureHierarchy = { throw IllegalStateException("injected hierarchy capture failure") }
        )
        assertTrue("Recovery gate must be false in the prepared fixture: $diagnostics", diagnostics.contains("recovery=false"))
        assertTrue("Biometric gate must reflect the enabled fixture: $diagnostics", diagnostics.contains("biometric=true"))
        assertTrue(diagnostics.contains("wait=idle=completed, accessibility=timed out"))
        assertTrue(diagnostics.contains("hierarchy unavailable: IllegalStateException: injected hierarchy capture failure"))
    }

    @Test
    fun chatWorkspaceProjectSelectorSwitchesAndDeleteCancelPreservesProject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Could not install a temporary test provider key",
            saveProviderKey(context, ProviderId.OPENAI, "instrumentation-placeholder-key"))
        try {
            val device = launch()
            clickFresh(device, By.text("ГЛАФИРА"))
            assertTrue("Chat workspace did not open",
                device.wait(Until.hasObject(By.desc("Выбрать проект: Личное")), 10_000))

            clickFresh(device, By.desc("Выбрать проект: Личное"))
            assertTrue("Built-in VodorodAktiv project is missing from chat picker",
                device.wait(Until.hasObject(By.text("ВодородАктив")), 5_000))
            clickFresh(device, By.text("ВодородАктив"))
            assertTrue("Chat did not switch to VodorodAktiv",
                device.wait(Until.hasObject(By.desc("Выбрать проект: ВодородАктив")), 5_000))

            clickFresh(device, By.desc("Выбрать проект: ВодородАктив"))
            clickFresh(device, By.desc("Удалить проект: ВодородАктив"))
            assertTrue("Project delete confirmation did not open",
                device.wait(Until.hasObject(By.text("Удалить проект?")), 5_000))
            assertTrue("Delete confirmation does not identify the selected project",
                device.hasObject(By.text("Все чаты проекта «ВодородАктив» и его закрепления будут безвозвратно удалены.")))
            clickFresh(device, By.text("Отмена"))
            assertTrue("Cancelling deletion must preserve the selected project",
                device.wait(Until.hasObject(By.desc("Выбрать проект: ВодородАктив")), 5_000))
            clickFresh(device, By.text("←"))
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
