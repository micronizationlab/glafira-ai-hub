package com.micronizationlab.glafira

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal object UiSmokeTestPreferenceLock {
    private val lock = java.util.concurrent.locks.ReentrantLock(true)
    fun acquire() = lock.lock()
    fun release() = lock.unlock()
}

/** Assert that smoke-test setup starts clean without deleting any recovery evidence. */
internal fun resetRecoveryJournalForUiSmokeTest(context: Context) {
    val app = context.applicationContext
    val journal = File(app.noBackupFilesDir, "glafira-restore-journal")
    val snapshot = File(app.filesDir, ".glafira-core-snapshot")
    val registry = File(app.noBackupFilesDir, ".glafira-restore-temp-registry.json")
    check(!Files.exists(journal.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "Unexpected recovery journal; UI smoke-test setup will not delete recovery evidence."
    }
    check(!Files.exists(snapshot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "Unexpected plaintext snapshot; UI smoke-test setup will not delete recovery evidence."
    }
    check(!Files.exists(registry.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "Unexpected restore-temp registry; UI smoke-test setup will not delete recovery evidence."
    }
    val replacementTemps = runCatching {
        Files.walk(app.filesDir.toPath()).use { paths ->
            paths.anyMatch { path ->
                path.fileName?.toString()?.endsWith(".restore-tmp") == true &&
                    Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }.getOrDefault(true)
    check(!replacementTemps) { "Unexpected restore replacement temp; UI smoke-test setup will not delete it." }
    val stale = app.cacheDir.listFiles { file ->
        file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
    }
    check(stale != null && stale.none { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }) {
        "Unexpected decrypted rollback temp; UI smoke-test setup will not delete it."
    }
}

/** Preference file holding the original biometric value until relaunch succeeds. */
private const val UI_SMOKE_RECOVERY_PREFS = "glafira_ui_smoke_recovery"

internal fun recoverInterruptedBiometricPreference(
    context: Context,
    preferences: android.content.SharedPreferences
) {
    val state = context.getSharedPreferences(UI_SMOKE_RECOVERY_PREFS, Context.MODE_PRIVATE)
    if (!state.getBoolean("pending", false)) return
    val original = state.getBoolean("original_biometric", false)
    restoreBiometricPreferenceAndRelock(context, preferences, original)
}

internal fun persistOriginalBiometricPreference(context: Context, originalBiometric: Boolean) {
    check(context.getSharedPreferences(UI_SMOKE_RECOVERY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean("original_biometric", originalBiometric)
        .putBoolean("pending", true)
        .commit()) {
        "Could not persist the original biometric preference before UI smoke-test mutation."
    }
}

/** Restore the persisted preference and always attempt to recreate MainActivity's task. */
internal fun restoreBiometricPreferenceAndRelock(
    context: Context,
    preferences: android.content.SharedPreferences,
    originalBiometric: Boolean,
    relaunch: () -> Unit = {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
) {
    var failure: Throwable? = null
    try {
        check(preferences.edit().putBoolean("biometric_enabled", originalBiometric).commit()) {
            "Could not restore biometric preference after UI smoke test"
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        try {
            relaunch()
        } catch (relaunchFailure: Throwable) {
            if (failure == null) failure = relaunchFailure else failure!!.addSuppressed(relaunchFailure)
        }
    }
    if (failure != null) throw failure!!
    check(context.getSharedPreferences(UI_SMOKE_RECOVERY_PREFS, Context.MODE_PRIVATE)
        .edit().clear().commit()) {
        "Could not clear the persisted UI smoke-test recovery snapshot."
    }
}

internal fun awaitHomeScreen(
    device: UiDevice,
    context: Context,
    timeoutMillis: Long = 20_000,
    waitForIdle: () -> Unit = {
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
    },
    waitForWorkspace: () -> Boolean = {
        device.wait(Until.hasObject(By.descContains("Открыть очередь задач")), timeoutMillis)
    }
) {
    waitForIdle()
    if (!waitForWorkspace()) {
        throw IllegalStateException("Home workspace did not become accessible.")
    }
}
