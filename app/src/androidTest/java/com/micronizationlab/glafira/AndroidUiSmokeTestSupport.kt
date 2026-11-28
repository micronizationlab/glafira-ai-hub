package com.micronizationlab.glafira

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption

/** Serialize UI suites that temporarily change the same app-wide biometric preference. */
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
    var cleanupFailure: Throwable? = null
    val hierarchy = try {
        if (captureFailure != null) {
            "<dump failed: ${captureFailure.javaClass.simpleName}: ${captureFailure.message}>"
        } else {
            runCatching { readRedactedHierarchySummary(hierarchyFile) }.getOrElse {
                "<read failed: ${it.javaClass.simpleName}: ${it.message}>"
            }
        }
    } finally {
        cleanupFailure = runCatching {
            check(!hierarchyFile.exists() || hierarchyFile.delete()) {
                "Could not remove temporary UI hierarchy diagnostic."
            }
        }.exceptionOrNull()
    }
    val recoveryState = runCatching { recoveryPendingForDiagnostics(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val biometricState = runCatching { isBiometricEnabled(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val hierarchyPath = "hierarchyPath=${hierarchyFile.absolutePath}"
    val cleanupStatus = cleanupFailure?.let { "hierarchyCleanup=error: ${it.javaClass.simpleName}: ${it.message}, " } ?: ""
    return "recovery=$recoveryState, biometric=$biometricState, wait=$waitStatus, ${cleanupStatus}$hierarchyPath. Window hierarchy: $hierarchy"
}

/** Read recovery indicators without deleting a temp file that an active recovery may own. */
internal fun recoveryPendingForDiagnostics(context: Context): Boolean {
    val app = context.applicationContext
    val journal = File(app.noBackupFilesDir, "glafira-restore-journal")
    if (Files.exists(journal.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
    val stale = app.cacheDir.listFiles { file ->
        file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
    } ?: throw IllegalStateException("Could not inspect recovery temp directory.")
    return stale.any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
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
        val hierarchyFile = File.createTempFile("android-ui-smoke-window-", ".xml", context.cacheDir)
        collectHomeScreenDiagnostics(device, context, hierarchyFile, waitStatus)
    }.getOrElse { diagnosticFailure ->
        val recovery = runCatching { recoveryPendingForDiagnostics(context).toString() }
            .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
        val biometric = runCatching { isBiometricEnabled(context).toString() }
            .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
        "recovery=$recovery, biometric=$biometric, wait=$waitStatus, hierarchy=unavailable: ${diagnosticFailure.javaClass.simpleName}: ${diagnosticFailure.message}"
    }
    throw IllegalStateException("Home workspace did not become accessible. $diagnostics", accessibilityWait.exceptionOrNull())
}

/** Read only bounded head/tail slices and redact every user-controlled XML text attribute. */
internal fun readRedactedHierarchySummary(file: File, maximumCharacters: Int = 12_000): String {
    require(maximumCharacters > 0) { "Hierarchy summary limit must be positive." }
    val maximumBytes = maximumCharacters.toLong() * 4L
    val length = file.length()
    val raw = if (length <= maximumBytes) {
        file.readText(Charsets.UTF_8)
    } else {
        val marker = "\n<... hierarchy middle omitted ...>\n"
        val available = (maximumBytes - marker.toByteArray(Charsets.UTF_8).size).toInt()
        require(available > 0) { "Hierarchy summary limit is too small." }
        val headBytes = available / 2
        val tailBytes = available - headBytes
        RandomAccessFile(file, "r").use { source ->
            val head = ByteArray(headBytes).also { source.readFully(it) }
            source.seek((length - tailBytes).coerceAtLeast(0L))
            val tail = ByteArray(tailBytes).also { source.readFully(it) }
            String(head, Charsets.UTF_8) + marker + String(tail, Charsets.UTF_8)
        }
    }
    val redacted = Regex("""((?:text|content-desc)=")[^"]*(")""")
        .replace(raw) { match -> "${match.groupValues[1]}[REDACTED]${match.groupValues[2]}" }
    return summarizeWindowHierarchy(redacted, maximumCharacters)
}

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
