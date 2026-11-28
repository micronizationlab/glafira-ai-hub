package com.micronizationlab.glafira

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectSelectorSmokeTest {
    private lateinit var preferences: android.content.SharedPreferences
    private var originalBiometric = false

    @Before
    fun captureBiometricPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UiSmokeTestPreferenceLock.acquire()
        try {
            preferences = context.getSharedPreferences("glafira_prefs", android.content.Context.MODE_PRIVATE)
            originalBiometric = preferences.getBoolean("biometric_enabled", false)
        } catch (failure: Throwable) {
            UiSmokeTestPreferenceLock.release()
            throw failure
        }
    }

    @After
    fun restoreBiometricPreference() {
        try {
            check(preferences.edit().putBoolean("biometric_enabled", originalBiometric).commit()) {
                "Could not restore biometric preference after UI smoke test"
            }
        } finally {
            UiSmokeTestPreferenceLock.release()
        }
    }
    @Test
    fun homeProjectPickerShowsReadableNamesAndSwitchesBuiltInWorkspaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        resetRecoveryJournalForUiSmokeTest(context)
        deletedProjectStore(context.filesDir).write(emptySet())
        saveProjects(context, defaultProjects())
        assertTrue(
            "VodorodAktiv must be present in the seeded project store",
            loadProjects(context).any { it.id == "vodorodaktiv" && it.name == "ВодородАктив" }
        )
        assertTrue(
            "Could not disable biometric lock for project selector acceptance",
            context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("biometric_enabled", false).commit()
        )

        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        val device = UiDevice.getInstance(instrumentation)
        val personalSelector = By.desc("Выбрать проект: Личное")
        awaitHomeScreen(device, context)
        assertTrue(
            "Home screen did not expose the active Personal project selector",
            device.wait(Until.hasObject(personalSelector), 10_000)
        )
        val selector = device.findObject(personalSelector)
        assertTrue("Home project selector is not clickable", selector.isClickable)
        selector.click()

        val vodorodaktiv = By.text("ВодородАктив")
        assertTrue(
            "Built-in VodorodAktiv project is missing or unreadable in the picker",
            device.wait(Until.hasObject(vodorodaktiv), 5_000)
        )
        device.findObject(vodorodaktiv).click()
        val selectedBusiness = By.desc("Выбрать проект: ВодородАктив")
        assertTrue(
            "Selecting VodorodAktiv did not switch the active workspace",
            device.wait(Until.hasObject(selectedBusiness), 5_000)
        )

        device.findObject(selectedBusiness).click()
        val token = By.text("Токен")
        assertTrue("Built-in Token project is missing or unreadable", device.wait(Until.hasObject(token), 5_000))
        device.findObject(token).click()
        assertTrue(
            "Selecting Token did not update the active workspace",
            device.wait(Until.hasObject(By.desc("Выбрать проект: Токен")), 5_000)
        )
    }
}
