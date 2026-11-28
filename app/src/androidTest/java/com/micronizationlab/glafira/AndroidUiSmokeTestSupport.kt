package com.micronizationlab.glafira

import android.content.Context
import java.io.File

/**
 * UI smoke tests must start from the normal workspace, not inherit an interrupted
 * restore journal left by a recovery test or a reused emulator snapshot.
 *
 * Recovery behavior is covered by dedicated instrumentation tests.
 */
internal fun resetRecoveryJournalForUiSmokeTest(context: Context) {
    val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
    val journalPath = journal.toPath()
    if (java.nio.file.Files.exists(journalPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        val removed = if (java.nio.file.Files.isSymbolicLink(journalPath)) {
            journal.delete()
        } else if (journal.isDirectory) {
            journal.deleteRecursively()
        } else {
            journal.delete()
        }
        check(removed) { "Could not reset interrupted-restore journal for UI smoke test" }
    }
    check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) {
        "Interrupted-restore state leaked into UI smoke test"
    }
}


internal fun awaitHomeScreen(
    device: androidx.test.uiautomator.UiDevice,
    context: Context,
    selectorDescription: String,
    timeoutMillis: Long = 20_000
) {
    if (device.wait(
            androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.descContains(selectorDescription)
            ),
            timeoutMillis
        )
    ) return

    val hierarchyFile = File(context.cacheDir, "android-ui-smoke-window-hierarchy.xml")
    val dumpFailure = runCatching { device.dumpWindowHierarchy(hierarchyFile) }.exceptionOrNull()
    val hierarchy = if (dumpFailure != null) {
        "<dump failed: \${dumpFailure.javaClass.simpleName}: \${dumpFailure.message}>"
    } else {
        runCatching { hierarchyFile.readText(Charsets.UTF_8).take(12_000) }
            .getOrElse { "<read failed: \${it.javaClass.simpleName}: \${it.message}>" }
    }
    val recovery = runCatching {
        GlafiraCoreBackupCoordinator.hasInterruptedRestore(context).toString()
    }.getOrElse { "error:\${it.javaClass.simpleName}:\${it.message}" }
    val biometric = runCatching { isBiometricEnabled(context).toString() }
        .getOrElse { "error:\${it.javaClass.simpleName}:\${it.message}" }
    error(
        "GLAFIRA home screen did not open; selector=\$selectorDescription; " +
            "recovery=\$recovery; biometric=\$biometric; hierarchy=\$hierarchy"
    )
}
