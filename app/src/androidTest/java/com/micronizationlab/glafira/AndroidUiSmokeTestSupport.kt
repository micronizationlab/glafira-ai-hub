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
    if (journal.exists() && !journal.deleteRecursively()) {
        error("Could not reset interrupted-restore journal for UI smoke test")
    }
    check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) {
        "Interrupted-restore state leaked into UI smoke test"
    }
}
