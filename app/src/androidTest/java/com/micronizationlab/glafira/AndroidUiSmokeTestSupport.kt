package com.micronizationlab.glafira

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.locks.ReentrantLock
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

private fun pathExistsNoFollow(path: Path): Boolean =
    try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) {
        false
    }

internal fun resetRecoveryJournalForUiSmokeTest(context: Context) {
    val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
    val journalPath = journal.toPath()
    if (pathExistsNoFollow(journalPath)) {
        if (Files.isSymbolicLink(journalPath)) {
            Files.delete(journalPath)
        } else {
            check(journal.deleteRecursively()) {
                "Could not reset interrupted-restore journal for UI smoke test"
            }
        }
    }
    val stale = context.cacheDir.listFiles { file ->
        file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
    } ?: throw IOException("Could not inspect rollback recovery fixtures")
    stale.forEach { Files.deleteIfExists(it.toPath()) }
    check(!recoveryPendingForDiagnostics(context)) {
        "Interrupted-restore state leaked into UI smoke test"
    }
}

/** Read-only equivalent of the startup recovery gate; it never reclaims a temp file. */
internal fun recoveryPendingForDiagnostics(context: Context): Boolean = runCatching {
    val app = context.applicationContext
    val journal = File(app.noBackupFilesDir, "glafira-restore-journal").toPath()
    if (pathExistsNoFollow(journal) && Files.isSymbolicLink(journal)) return@runCatching true
    val marker = File(journal.toFile(), "restore.pending").toPath()
    val committed = File(journal.toFile(), "restore.committed").toPath()
    if (pathExistsNoFollow(marker) || pathExistsNoFollow(committed)) return@runCatching true
    val stale = app.cacheDir.listFiles { file ->
        file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
    } ?: throw IOException("Could not inspect rollback recovery temp files")
    stale.any { file ->
        pathExistsNoFollow(file.toPath()) &&
            (!file.canWrite() || file.parentFile?.canWrite() != true)
    }
}.getOrDefault(true)

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

/** Serializes the three UI smoke suites and restores the exact pre-test biometric setting. */
internal class BiometricPreferenceRestoreRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                preferenceLock.lock()
                try {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val preferences = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                    val previousBiometric = preferences.getBoolean("biometric_enabled", false)
                    var primaryFailure: Throwable? = null
                    try {
                        base.evaluate()
                    } catch (failure: Throwable) {
                        primaryFailure = failure
                        throw failure
                    } finally {
                        restorePreferencePreservingFailure(primaryFailure) {
                            preferences.edit().putBoolean("biometric_enabled", previousBiometric).commit()
                        }
                    }
                } finally {
                    preferenceLock.unlock()
                }
            }
        }

    private companion object {
        val preferenceLock = ReentrantLock()
    }
}

/**
 * Capture the current window hierarchy and startup-gate states for a failed home-screen
 * accessibility wait. Raw UiAutomator XML is erased after a bounded, redacted read.
 */
internal fun collectHomeScreenDiagnostics(
    device: UiDevice,
    context: Context,
    hierarchyFile: File,
    waitStatus: String = "not recorded",
    dumpHierarchy: (File) -> Unit = { device.dumpWindowHierarchy(it) }
): String {
    val captureFailure = runCatching { dumpHierarchy(hierarchyFile) }.exceptionOrNull()
    var cleanupFailure: Throwable? = null
    val hierarchy = try {
        if (captureFailure != null) {
            "<dump failed: ${captureFailure.javaClass.simpleName}: ${captureFailure.message}>"
        } else {
            val readResult = runCatching { readBoundedWindowHierarchy(hierarchyFile) }
            readResult.getOrNull()
                ?: "<read failed: ${readResult.exceptionOrNull()?.javaClass?.simpleName}: ${readResult.exceptionOrNull()?.message}>"
        }
    } finally {
        cleanupFailure = runCatching { eraseAndDelete(hierarchyFile) }.exceptionOrNull()
    }
    val recoveryState = runCatching { recoveryPendingForDiagnostics(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val biometricState = runCatching { isBiometricEnabled(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val cleanupStatus = cleanupFailure?.let { "error: ${it.javaClass.simpleName}: ${it.message}" } ?: "complete"
    val hierarchyPath = "hierarchyPath=${hierarchyFile.absolutePath}"
    return "recovery=$recoveryState, biometric=$biometricState, wait=$waitStatus, hierarchyCleanup=$cleanupStatus, $hierarchyPath. Window hierarchy: $hierarchy"
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
    val hierarchyFile = File.createTempFile("android-ui-smoke-window-", ".xml", context.cacheDir)
    val diagnostics = collectHomeScreenDiagnostics(device, context, hierarchyFile, waitStatus)
    throw IllegalStateException("Home workspace did not become accessible. $diagnostics", accessibilityWait.exceptionOrNull())
}

/** Read a bounded head/tail window and redact vault values before any truncation occurs. */
internal fun readBoundedWindowHierarchy(file: File, maximumCharacters: Int = 12_000): String {
    require(maximumCharacters > 256) { "Hierarchy summary limit is too small." }
    val maximumBytes = maximumCharacters.toLong() * 4L
    val length = file.length()
    val safeContent = if (length <= maximumBytes) {
        val raw = file.readText(Charsets.UTF_8)
        redactSensitiveHierarchyText(raw, redactAllUserText = raw.contains("Пароль:"))
    } else {
        val rangeBytes = (maximumBytes / 2L).toInt()
        val head = readUtf8Range(file, 0L, rangeBytes)
        val tail = readUtf8Range(file, (length - rangeBytes).coerceAtLeast(0L), rangeBytes)
        val completeHead = head.substringBeforeLast(">", "") + ">"
        val completeTail = tail.substringAfter(">", "")
        val marker = "\n<... hierarchy middle omitted to bound diagnostic memory ...>\n"
        redactSensitiveHierarchyText(completeHead + marker + completeTail, redactAllUserText = true)
    }
    return summarizeWindowHierarchy(safeContent, maximumCharacters)
}

/** In a vault hierarchy every visible text field may contain account-owned data. */
internal fun redactSensitiveHierarchyText(hierarchy: String, redactAllUserText: Boolean = hierarchy.contains("Пароль:")): String {
    val pattern = if (redactAllUserText) {
        Regex("""((?:text|content-desc)=")[^"]*(")""")
    } else {
        Regex("""((?:text|content-desc)="Пароль:\\s*)[^"]*(")""")
    }
    return pattern.replace(hierarchy) { match ->
        "${match.groupValues[1]}[REDACTED]${match.groupValues[2]}"
    }
}

private fun readUtf8Range(file: File, offset: Long, byteCount: Int): String =
    RandomAccessFile(file, "r").use { source ->
        source.seek(offset)
        val bytes = ByteArray(byteCount)
        source.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }

/** Overwrite raw cache content where possible, then unlink the diagnostic file. */
private fun eraseAndDelete(file: File) {
    val path = file.toPath()
    if (!pathExistsNoFollow(path)) return
    if (Files.isSymbolicLink(path)) {
        Files.deleteIfExists(path)
        return
    }
    if (file.isFile) {
        runCatching {
            RandomAccessFile(file, "rw").use { target ->
                val zeros = ByteArray(8_192)
                var remaining = target.length()
                target.seek(0L)
                while (remaining > 0L) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    target.write(zeros, 0, count)
                    remaining -= count
                }
                target.fd.sync()
            }
        }
    }
    if (!Files.deleteIfExists(path)) throw IOException("Could not remove raw UI hierarchy from app cache.")
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
