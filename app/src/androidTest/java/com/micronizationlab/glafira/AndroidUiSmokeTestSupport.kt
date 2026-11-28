package com.micronizationlab.glafira

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.ArrayDeque
import java.util.UUID

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
    waitStatus: String = "not recorded",
    dumpHierarchy: (File) -> Unit = { device.dumpWindowHierarchy(it) }
): String {
    val captureFailure = runCatching { dumpHierarchy(hierarchyFile) }.exceptionOrNull()
    var hierarchyCleanupFailure: Throwable? = null
    val hierarchy = try {
        if (captureFailure != null) {
            "<dump failed: ${captureFailure.javaClass.simpleName}: ${captureFailure.message}>"
        } else {
            runCatching { readRedactedBoundedWindowHierarchy(hierarchyFile) }
                .getOrElse { "<read failed: ${it.javaClass.simpleName}: ${it.message}>" }
        }
    } finally {
        // Unique capture files prevent one failed wait from erasing another's hierarchy.
        hierarchyCleanupFailure = runCatching { eraseAndDelete(hierarchyFile) }.exceptionOrNull()
    }
    val recoveryState = runCatching { recoveryPendingForDiagnostics(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val biometricState = runCatching { isBiometricEnabled(context).toString() }
        .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    val hierarchyPath = "hierarchyPath=${hierarchyFile.absolutePath}"
    val cleanupStatus = hierarchyCleanupFailure?.let {
        "cleanup=error: ${it.javaClass.simpleName}: ${it.message}"
    } ?: "cleanup=complete"
    return "recovery=$recoveryState, biometric=$biometricState, wait=$waitStatus, $cleanupStatus, $hierarchyPath. Window hierarchy: $hierarchy"
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
    val hierarchyFile = File(context.cacheDir, "android-ui-smoke-window-hierarchy-${UUID.randomUUID()}.xml")
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
    if (Files.isSymbolicLink(journal.toPath())) return true
    val marker = File(journal, "restore.pending")
    val committed = File(journal, "restore.committed")
    if (Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        Files.exists(committed.toPath(), LinkOption.NOFOLLOW_LINKS)
    ) {
        return true
    }
    // Diagnostics must not call the production cleanup routine: a matching temp
    // can be the active decrypted rollback database used by a recovery in progress.
    val stale = app.cacheDir.listFiles { file ->
        file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
    } ?: return true
    return stale.any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
}

/**
 * Redact every user-visible text/content description attribute while retaining structural
 * node metadata. Keep only bounded head/tail output in memory; never create a full-size
 * second copy of a potentially large hierarchy dump.
 */
internal fun readRedactedBoundedWindowHierarchy(file: File, maximumCharacters: Int = 12_000): String {
    require(maximumCharacters > 0) { "Hierarchy summary limit must be positive." }
    val marker = "\n<... hierarchy middle omitted to bound diagnostic memory ...>\n"
    val headLimit = maximumCharacters / 2
    val tailLimit = maximumCharacters - headLimit - marker.length
    require(tailLimit > 0) { "Hierarchy summary limit is too small." }
    val head = StringBuilder(headLimit)
    val tail = ArrayDeque<Char>(tailLimit)
    val sensitiveAttributes = setOf("text", "content-desc", "hint", "tooltip-text")
    var totalCharacters = 0L
    var insideTag = false
    var attributeName = StringBuilder()
    var quote: Char? = null
    var redactValue = false

    fun append(character: Char) {
        totalCharacters++
        if (head.length < headLimit) {
            head.append(character)
        } else {
            if (tail.size == tailLimit) tail.removeFirst()
            tail.addLast(character)
        }
    }

    BufferedReader(java.io.InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
        while (true) {
            val value = reader.read()
            if (value < 0) break
            val character = value.toChar()
            val activeQuote = quote
            if (activeQuote != null) {
                if (redactValue) {
                    if (character == activeQuote) {
                        append(character)
                        quote = null
                        redactValue = false
                        attributeName = StringBuilder()
                    }
                } else {
                    append(character)
                    if (character == activeQuote) {
                        quote = null
                        attributeName = StringBuilder()
                    }
                }
            } else if (insideTag) {
                when {
                    character == '>' -> {
                        append(character)
                        insideTag = false
                        attributeName = StringBuilder()
                    }
                    character == '"' || character == '\'' -> {
                        quote = character
                        redactValue = attributeName.toString() in sensitiveAttributes
                        append(character)
                        if (redactValue) "[REDACTED]".forEach(::append)
                    }
                    character == '=' -> append(character)
                    character.isWhitespace() || character == '/' -> {
                        append(character)
                        attributeName = StringBuilder()
                    }
                    else -> {
                        append(character)
                        attributeName.append(character)
                    }
                }
            } else {
                if (character == '<') {
                    insideTag = true
                    attributeName = StringBuilder()
                }
                append(character)
            }
        }
    }

    val suffix = tail.joinToString("")
    return if (totalCharacters <= headLimit + tailLimit) {
        head.toString() + suffix
    } else {
        head.toString() + marker + suffix
    }
}

/** Best-effort overwrite before deleting raw or sanitized hierarchy files from app cache. */
private fun eraseAndDelete(file: File) {
    val path = file.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(path)) {
        check(file.delete()) { "Could not remove hierarchy diagnostic symlink." }
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
    check(file.delete()) { "Could not remove hierarchy diagnostic file." }
}

/** Read at most four bytes per output character, preserving the head and tail of huge dumps. */
internal fun readBoundedWindowHierarchy(
    file: File,
    maximumCharacters: Int = 12_000,
    readRange: (File, Long, Int) -> String = ::readUtf8Range
): String {
    require(maximumCharacters > 0) { "Hierarchy summary limit must be positive." }
    val maximumBytes = maximumCharacters.toLong() * 4L
    val length = file.length()
    if (length <= maximumBytes) {
        return summarizeWindowHierarchy(file.readText(Charsets.UTF_8), maximumCharacters)
    }
    val marker = "\n<... hierarchy middle omitted to bound diagnostic memory ...>\n"
    val contentBytes = (maximumBytes - marker.toByteArray(Charsets.UTF_8).size).toInt()
    require(contentBytes > 0) { "Hierarchy summary limit is too small." }
    val headBytes = contentBytes / 2
    val tailBytes = contentBytes - headBytes
    val head = readRange(file, 0L, headBytes)
    val tail = readRange(file, (length - tailBytes).coerceAtLeast(0L), tailBytes)
    return summarizeWindowHierarchy(head + marker + tail, maximumCharacters)
}

private fun readUtf8Range(file: File, offset: Long, byteCount: Int): String =
    RandomAccessFile(file, "r").use { source ->
        source.seek(offset)
        val bytes = ByteArray(byteCount)
        source.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }

/** Remove revealed password-vault values before hierarchy text can enter JUnit artifacts. */
internal fun redactSensitiveHierarchyText(hierarchy: String): String {
    val revealedPassword = Regex("""((?:text|content-desc)="Пароль:\s*)[^"]*(")""")
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
