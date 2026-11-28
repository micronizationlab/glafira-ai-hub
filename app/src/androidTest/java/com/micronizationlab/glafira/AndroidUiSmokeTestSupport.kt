package com.micronizationlab.glafira

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File

internal fun resetRecoveryJournalForUiSmokeTest(context: Context) {
    val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
    if (journal.exists() && !journal.deleteRecursively()) {
        error("Could not reset interrupted-restore journal for UI smoke test")
    }
    check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) {
        "Interrupted-restore state leaked into UI smoke test"
    }
}

/** Preserve the primary test failure if restoring a fixture preference also fails. */
internal fun restorePreferencePreservingFailure(primaryFailure: Throwable?, restore: () -> Boolean) {
    val restoreFailure = runCatching {
        check(restore()) { "Could not restore preference after startup diagnostics test" }
    }.exceptionOrNull() ?: return
    if (primaryFailure != null) {
        primaryFailure.addSuppressed(restoreFailure)
    } else {
        throw restoreFailure
    }
}

/** Always attempt preference restoration, even when fixture cleanup fails. */
internal fun cleanupAndRestorePreservingFailure(
    primaryFailure: Throwable?,
    cleanup: () -> Unit,
    restore: () -> Boolean
) {
    val cleanupFailure = runCatching(cleanup).exceptionOrNull()
    val failureToPreserve = primaryFailure ?: cleanupFailure
    if (primaryFailure != null && cleanupFailure != null) {
        primaryFailure.addSuppressed(cleanupFailure)
    }
    restorePreferencePreservingFailure(failureToPreserve, restore)
    if (primaryFailure == null && cleanupFailure != null) {
        throw cleanupFailure
    }
}

/**
 * Capture the current window hierarchy and startup-gate states for a failed home-screen
 * accessibility wait. This function does not wait for the workspace to appear.
 */
internal fun collectHomeScreenDiagnostics(
    device: UiDevice,
    context: Context,
    hierarchyFile: File,
    waitStatus: String = "not recorded"
): String {
    val captureFailure = runCatching { device.dumpWindowHierarchy(hierarchyFile) }.exceptionOrNull()
    val hierarchy = if (captureFailure != null) {
        "<dump failed: ${captureFailure.javaClass.simpleName}: ${captureFailure.message}>"
    } else {
        val readResult = runCatching { summarizeWindowHierarchy(hierarchyFile.readText(Charsets.UTF_8)) }
        readResult.getOrNull()
            ?: "<read failed: ${readResult.exceptionOrNull()?.javaClass?.simpleName}: ${readResult.exceptionOrNull()?.message}>"
    }
    val recoveryState = runCatching { GlafiraCoreBackupCoordinator.hasInterruptedRestore(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val biometricState = runCatching { isBiometricEnabled(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val hierarchyPath = "hierarchyPath=${hierarchyFile.absolutePath}"
    return "recovery=$recoveryState, biometric=$biometricState, wait=$waitStatus, $hierarchyPath. Window hierarchy: $hierarchy"
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
    val hierarchyFile = File(context.cacheDir, "android-ui-smoke-window-hierarchy.xml")
    val diagnostics = collectHomeScreenDiagnostics(device, context, hierarchyFile, waitStatus)
    throw IllegalStateException("Home workspace did not become accessible. $diagnostics", accessibilityWait.exceptionOrNull())
}

/** Keep both the start and end of large UI hierarchies; screen identity often appears near the tail. */
internal fun summarizeWindowHierarchy(hierarchy: String, maximumCharacters: Int = 12_000): String {
    require(maximumCharacters > 0) { "Hierarchy summary limit must be positive." }
    if (hierarchy.length <= maximumCharacters) return hierarchy
    val marker = "\n<... hierarchy truncated; showing first and last portions ...>\n"
    require(maximumCharacters > marker.length) { "Hierarchy summary limit is too small." }
    val contentCharacters = maximumCharacters - marker.length
    val headCharacters = contentCharacters / 2
    val tailCharacters = contentCharacters - headCharacters
    return hierarchy.take(headCharacters) + marker + hierarchy.takeLast(tailCharacters)
}
