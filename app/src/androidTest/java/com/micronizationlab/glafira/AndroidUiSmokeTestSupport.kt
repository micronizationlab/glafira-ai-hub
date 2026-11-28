package com.micronizationlab.glafira

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File

internal object UiSmokeTestPreferenceLock {
    private val lock = java.util.concurrent.locks.ReentrantLock(true)
    fun acquire() = lock.lock()
    fun release() = lock.unlock()
}

internal fun resetRecoveryJournalForUiSmokeTest(context: Context) {
    val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
    if (journal.exists() && !journal.deleteRecursively()) {
        error("Could not reset interrupted-restore journal for UI smoke test")
    }
    check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) {
        "Interrupted-restore state leaked into UI smoke test"
    }
}

/** Restore the preference, then recreate the task so MainActivity applies the restored lock state. */
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

/** Restore persisted state and always invalidate the prior MainActivity task, even on failure. */
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

/** Read recovery indicators without deleting a temp file that an active recovery may own. */
/** Read recovery state without deleting a rollback temp that may be in active use. */
internal fun recoveryPendingForDiagnostics(context: Context): Boolean {
    val app = context.applicationContext
    val journal = File(app.noBackupFilesDir, "glafira-restore-journal")
    val markers = listOf(
        File(journal, "restore.pending"),
        File(journal, "restore.committed"),
        File(app.filesDir, ".glafira-core-snapshot")
    )
    if (Files.isSymbolicLink(journal.toPath()) ||
        markers.any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) } ||
        Files.exists(
            File(app.noBackupFilesDir, ".glafira-restore-temp-registry.json").toPath(),
            LinkOption.NOFOLLOW_LINKS
        )
    ) return true
    val replacementTemps = runCatching {
        Files.walk(app.filesDir.toPath()).use { paths ->
            paths.anyMatch { path ->
                path.fileName?.toString()?.endsWith(".restore-tmp") == true &&
                    Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }.getOrDefault(true)
    if (replacementTemps) return true
    val stale = app.cacheDir.listFiles { file ->
        file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
    } ?: return true
    return stale.any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
}

/** Capture only structural accessibility metadata; never read text or content descriptions. */
internal fun captureSafeWindowHierarchy(maximumNodes: Int = 300, maximumCharacters: Int = 12_000): String {
    require(maximumNodes > 0) { "Hierarchy node limit must be positive." }
    val truncationNoticePrefix = "<hierarchy summary bounded; nodes="
    val truncationNoticeSuffix = ">\n"
    val truncationNoticeReserve =
        truncationNoticePrefix.length + maximumNodes.toString().length + truncationNoticeSuffix.length
    require(maximumCharacters >= truncationNoticeReserve + 2) {
        "Hierarchy character limit must leave room for the truncation notice."
    }
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        ?: return "<no active accessibility root>"
    val bodyLimit = maximumCharacters - truncationNoticeReserve
    val output = StringBuilder()
    var visited = 0
    var truncated = false
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (visited >= maximumNodes || output.length >= bodyLimit) {
            truncated = true
            return
        }
        visited++
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        output.append("  ".repeat(depth.coerceAtMost(16)))
            .append(node.className ?: "unknown")
            .append(" id=").append(node.viewIdResourceName ?: "-")
            .append(" bounds=").append(bounds.flattenToString())
            .append(" clickable=").append(node.isClickable)
            .append(" enabled=").append(node.isEnabled)
            .append(" children=").append(node.childCount)
            .append('\n')
        for (index in 0 until node.childCount) {
            if (visited >= maximumNodes || output.length >= bodyLimit) {
                truncated = true
                break
            }
            node.getChild(index)?.let { child -> visit(child, depth + 1) }
        }
    }
    visit(root, 0)
    if (!truncated && (visited >= maximumNodes || output.length >= bodyLimit)) truncated = true
    if (!truncated) return output.toString().take(maximumCharacters)
    return output.toString().take(bodyLimit) + truncationNoticePrefix + visited + truncationNoticeSuffix
}

internal fun collectHomeScreenDiagnostics(
    device: UiDevice,
    context: Context,
    waitStatus: String = "not recorded",
    captureHierarchy: () -> String = ::captureSafeWindowHierarchy
): String {
    val hierarchy = runCatching(captureHierarchy).getOrElse {
        "<hierarchy unavailable: ${it.javaClass.simpleName}: ${it.message}>"
    }
    val recoveryState = runCatching { recoveryPendingForDiagnostics(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val biometricState = runCatching { isBiometricEnabled(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    return "recovery=$recoveryState, biometric=$biometricState, wait=$waitStatus. Safe window structure: $hierarchy"
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
    val idleWait = runCatching(waitForIdle)
    val accessibilityWait = runCatching(waitForWorkspace)
    if (accessibilityWait.getOrNull() == true) return

    val idleStatus = idleWait.exceptionOrNull()?.let { "error: ${it.javaClass.simpleName}: ${it.message}" } ?: "completed"
    val accessibilityStatus = accessibilityWait.exceptionOrNull()?.let { "error: ${it.javaClass.simpleName}: ${it.message}" } ?: "timed out"
    val waitStatus = "idle=$idleStatus, accessibility=$accessibilityStatus"
    val diagnostics = runCatching {
        collectHomeScreenDiagnostics(device, context, waitStatus)
    }.getOrElse {
        "recovery=unavailable, biometric=unavailable, wait=$waitStatus, hierarchy unavailable: ${it.javaClass.simpleName}: ${it.message}"
    }
    throw IllegalStateException("Home workspace did not become accessible. $diagnostics", accessibilityWait.exceptionOrNull())
}
