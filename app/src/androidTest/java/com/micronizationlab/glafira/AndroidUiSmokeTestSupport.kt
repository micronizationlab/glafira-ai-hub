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
        val readResult = runCatching {
            redactSensitiveHierarchyText(readBoundedWindowHierarchy(hierarchyFile))
        }
        readResult.getOrNull()
            ?: "<read failed: ${readResult.exceptionOrNull()?.javaClass?.simpleName}: ${readResult.exceptionOrNull()?.message}>"
    }
    val recoveryState = runCatching { recoveryPendingForDiagnostics(context).toString() }
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

/**
 * A diagnostic read must never reclaim a rollback temp file: recovery may be actively
 * decrypting it. Treat any journal or stale temp as pending without mutating filesystem state.
 */
internal fun recoveryPendingForDiagnostics(context: Context): Boolean {
    val app = context.applicationContext
    val journal = File(app.noBackupFilesDir, "glafira-restore-journal")
    if (Files.isSymbolicLink(journal.toPath()) || Files.exists(journal.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        return true
    }
    val stale = app.cacheDir.listFiles { file ->
        file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
    }.orEmpty()
    return stale.any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
}

/** Read at most four bytes per output character, preserving the head and tail of huge dumps. */
internal fun readBoundedWindowHierarchy(file: File, maximumCharacters: Int = 12_000): String {
    require(maximumCharacters > 0) { "Hierarchy summary limit must be positive." }
    val maximumBytes = maximumCharacters.toLong() * 4L
    val length = file.length()
    if (length <= maximumBytes) {
        return summarizeWindowHierarchy(file.readText(Charsets.UTF_8), maximumCharacters)
    }
    val marker = "\\n<... hierarchy middle omitted to bound diagnostic memory ...>\\n"
    val contentBytes = (maximumBytes - marker.toByteArray(Charsets.UTF_8).size).toInt()
    require(contentBytes > 0) { "Hierarchy summary limit is too small." }
    val headBytes = contentBytes / 2
    val tailBytes = contentBytes - headBytes
    fun readRange(offset: Long, byteCount: Int): String =
        RandomAccessFile(file, "r").use { source ->
            source.seek(offset)
            val bytes = ByteArray(byteCount)
            val count = source.read(bytes)
            if (count <= 0) "" else String(bytes, 0, count, Charsets.UTF_8)
        }
    val head = readRange(0L, headBytes)
    val tail = readRange((length - tailBytes).coerceAtLeast(0L), tailBytes)
    return summarizeWindowHierarchy(head + marker + tail, maximumCharacters)
}

/** Remove revealed password-vault values before hierarchy text can enter JUnit artifacts. */
internal fun redactSensitiveHierarchyText(hierarchy: String): String {
    val revealedPassword = Regex("""((?:text|content-desc)="Пароль:\\s*)[^"]*(")""")
    return revealedPassword.replace(hierarchy) { match ->
        "${match.groupValues[1]}[REDACTED]${match.groupValues[2]}"
    }
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
