package com.micronizationlab.glafira

import android.content.Context
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File

/**
 * UI smoke tests must start from the normal workspace, not inherit an interrupted
 * restore journal left by a recovery test or a reused emulator snapshot.
 *
 * Recovery behavior is covered by dedicated instrumentation tests.
 */
internal fun resetRecoveryJournalForUiSmokeTest(context: Context) {
    val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
    if (journal.exists() && !journal.deleteRecursively()) {
        error("Could not reset interrupted-restore journal for UI smoke test")
    }
    check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) {
        "Interrupted-restore state leaked into UI smoke test"
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
    waitFailure: Throwable? = null
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
    val waitState = waitFailure?.let { "timeout/error: ${it.javaClass.simpleName}: ${it.message}" } ?: "ok"
    return "recovery=$recoveryState, biometric=$biometricState, wait=$waitState. Window hierarchy: $hierarchy"
}

/**
 * Wait for the Compose workspace to publish its accessibility tree before UI assertions.
 * If it does not appear, report a best-effort hierarchy snapshot and both startup gates.
 */
internal fun awaitHomeScreen(
    device: UiDevice,
    context: Context,
    timeoutMillis: Long = 20_000,
    waitForWorkspace: () -> Boolean = {
        device.wait(Until.hasObject(By.descContains("Открыть очередь задач")), timeoutMillis)
    }
) {
    val idleWaitFailure = runCatching { device.waitForIdle(5_000) }.exceptionOrNull()
    val accessibilityWait = runCatching(waitForWorkspace)
    if (accessibilityWait.getOrNull() == true) return

    val waitFailure = accessibilityWait.exceptionOrNull() ?: idleWaitFailure
    val hierarchyFile = File(context.cacheDir, "android-ui-smoke-window-hierarchy.xml")
    val diagnostics = collectHomeScreenDiagnostics(device, context, hierarchyFile, waitFailure)
    error("Home workspace did not become accessible. $diagnostics")
}

/** Keep both the start and end of large UI hierarchies; screen identity often appears near the tail. */
internal fun summarizeWindowHierarchy(hierarchy: String, maximumCharacters: Int = 12_000): String {
    require(maximumCharacters > 0) { "Hierarchy summary limit must be positive." }
    if (hierarchy.length <= maximumCharacters) return hierarchy
    val headCharacters = maximumCharacters / 2
    val tailCharacters = maximumCharacters - headCharacters
    val omittedCharacters = hierarchy.length - maximumCharacters
    return hierarchy.take(headCharacters) +
        "\n<... truncated $omittedCharacters characters; showing first $headCharacters and last $tailCharacters ...>\n" +
        hierarchy.takeLast(tailCharacters)
}
