package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class GlafiraTaskQueueRecoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore

    @Before fun setUp() {
        context.deleteDatabase("glafira_core_tasks.db")
        store = GlafiraTaskStore(context)
    }
    @After fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
    }
    private fun task(id: String, type: String = "project.read", project: String = "project") = GlafiraTask(
        taskId = id, projectId = project, taskType = type, payload = "{}", origin = "owner",
        createdAtMillis = 1_000, updatedAtMillis = 1_000, idempotencyKey = id
    )

    @Test fun disconnectedTaskResumesOnlyAfterExplicitNetworkWakeAcrossReopen() {
        store.enqueue(task("network"))
        store.claimNext(2_000)
        assertTrue(store.transition("network", setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.WAITING_FOR_NETWORK, 3_000))
        store.close()
        store = GlafiraTaskStore(context)
        assertNull(store.claimNext(4_000))
        assertTrue(store.hasWaitingForNetworkTasks())
        assertEquals(1, store.resumeWaitingForNetwork(5_000))
        assertEquals(0, store.resumeWaitingForNetwork(5_000))
        assertEquals("network", store.claimNext(6_000)?.taskId)
        assertEquals(2, store.getTask("network")?.attemptCount)
    }

    @Test fun retryWakeUsesPersistedEarliestTimestampAndDoesNotRunEarly() {
        store.enqueue(task("retry"))
        store.claimNext(2_000)
        store.transition("retry", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.RETRY_SCHEDULED,
            3_000, retryAtMillis = 15_000)
        store.enqueue(task("ready"))
        assertEquals(15_000L, store.nextRetryAtMillis())
        assertEquals("ready", store.claimNext(4_000)?.taskId)
        assertNull(store.claimNext(14_999))
        assertEquals("retry", store.claimNext(15_000)?.taskId)
    }

    @Test fun completedOperationCannotBeReclaimedOrReopenedAndHistorySurvivesReopen() {
        store.enqueue(task("completed"))
        store.claimNext(2_000)
        assertTrue(store.transition("completed", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.SUCCEEDED, 3_000))
        assertFalse(store.transition("completed", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.SUCCEEDED, 4_000))
        assertEquals(0, store.recoverInterruptedCoreTasks(5_000, 5))
        assertNull(store.claimNext(5_000))
        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(listOf(GlafiraTaskState.SUCCEEDED, GlafiraTaskState.RUNNING, GlafiraTaskState.QUEUED),
            store.taskHistory("completed", "project").map { it.toState })
        assertTrue(store.taskHistory("completed", "other-project").isEmpty())
        assertThrows(Exception::class.java) {
            store.writableDatabase.update("core_tasks", ContentValues().apply { put("state", "queued") },
                "task_id = ?", arrayOf("completed"))
        }
    }

    @Test fun uncertainWriteIsQuarantinedWhileIndependentReadKeepsRunning() {
        store.enqueue(task("write", "message.send"))
        store.claimNext(2_000)
        store.enqueue(task("read"))
        assertEquals(1, store.recoverInterruptedCoreTasks(3_000, 5))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("write")?.state)
        assertEquals("CORE_EXECUTION_OUTCOME_UNKNOWN", store.getTask("write")?.lastError)
        assertEquals("read", store.claimNext(4_000)?.taskId)
    }

    @Test fun blockedOwnerAndExpiredTasksDoNotHideReadyTasks() {
        store.enqueue(task("owner").copy(priority = 100))
        store.transition("owner", setOf(GlafiraTaskState.QUEUED), GlafiraTaskState.WAITING_FOR_OWNER, 2_000)
        store.enqueue(task("expired").copy(priority = 99, deadlineAtMillis = 3_000))
        store.enqueue(task("ready"))
        assertEquals("ready", store.claimNext(4_000)?.taskId)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("expired")?.state)
        assertEquals("TASK_DEADLINE_EXPIRED", store.getTask("expired")?.lastError)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("owner")?.state)
    }

    @Test fun scopedStatusAndQueueListingDoNotExposeOtherProjects() {
        store.enqueue(task("first", project = "first-project"))
        store.enqueue(task("second", project = "second-project"))
        assertNull(store.getTask("first", "second-project"))
        assertEquals(listOf("second"), store.listTasks(projectId = "second-project").map { it.taskId })
    }

    @Test fun injectedClaimHistoryFailureRollsBackClaimAndAttemptCountTogether() {
        store.enqueue(task("injected-claim-history-failure"))
        store.writableDatabase.execSQL("""
            CREATE TRIGGER inject_core_claim_history_failure
            BEFORE INSERT ON core_task_history
            WHEN NEW.to_state = 'running'
            BEGIN
                SELECT RAISE(ABORT, 'injected-claim-history-failure');
            END
        """.trimIndent())

        val failure = runCatching { store.claimNext(2_000) }.exceptionOrNull()
        assertNotNull("The injected history failure must abort the claim", failure)
        val afterFailure = requireNotNull(store.getTask("injected-claim-history-failure"))
        assertEquals(GlafiraTaskState.QUEUED, afterFailure.state)
        assertEquals(0, afterFailure.attemptCount)
        assertEquals(listOf(GlafiraTaskState.QUEUED),
            store.taskHistory("injected-claim-history-failure", "project").map { it.toState })
        store.writableDatabase.execSQL("DROP TRIGGER inject_core_claim_history_failure")

        store.close()
        store = GlafiraTaskStore(context)
        val reopened = requireNotNull(store.getTask("injected-claim-history-failure"))
        assertEquals(GlafiraTaskState.QUEUED, reopened.state)
        assertEquals(0, reopened.attemptCount)
        assertEquals(listOf(GlafiraTaskState.QUEUED),
            store.taskHistory("injected-claim-history-failure", "project").map { it.toState })
        assertEquals("injected-claim-history-failure", store.claimNext(3_000)?.taskId)
    }

    @Test fun repeatedCoreResultCommitIsIdempotentAndConflictingResultFailsClosed() {
        store.enqueue(task("idempotent-core-result"))
        assertEquals("idempotent-core-result", store.claimNext(2_000)?.taskId)

        assertTrue(store.persistCoreTaskResult(
            "idempotent-core-result",
            setOf(GlafiraTaskState.RUNNING),
            "canonical-result",
            3_000L
        ))
        assertTrue(store.persistCoreTaskResult(
            "idempotent-core-result",
            setOf(GlafiraTaskState.RUNNING),
            "canonical-result",
            4_000L
        ))

        assertThrows(IllegalStateException::class.java) {
            store.persistCoreTaskResult(
                "idempotent-core-result",
                setOf(GlafiraTaskState.RUNNING),
                "conflicting-result",
                5_000L
            )
        }

        assertEquals("canonical-result", store.coreTaskResult("idempotent-core-result", "project")?.result)
        assertEquals(1, store.taskHistory("idempotent-core-result", "project")
            .count { it.toState == GlafiraTaskState.SUCCEEDED })
        assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask("idempotent-core-result")?.state)
    }

    @Test fun injectedResultInsertFailureRollsBackStateResultAndHistoryTogether() {
        store.enqueue(task("injected-result-failure"))
        assertNotNull(store.claimNext(2_000))
        store.writableDatabase.execSQL("""
            CREATE TRIGGER inject_core_result_insert_failure
            BEFORE INSERT ON core_task_results
            BEGIN
                SELECT RAISE(ABORT, 'injected-result-write-failure');
            END
        """.trimIndent())

        val failure = runCatching {
            store.persistCoreTaskResult(
                "injected-result-failure",
                setOf(GlafiraTaskState.RUNNING),
                "must-not-commit",
                3_000
            )
        }.exceptionOrNull()

        assertNotNull("The injected database failure must reach the caller", failure)
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("injected-result-failure")?.state)
        assertNull(store.coreTaskResult("injected-result-failure", "project"))
        assertEquals(
            listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING),
            store.taskHistory("injected-result-failure", "project").map { it.toState }.reversed()
        )

        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("injected-result-failure")?.state)
        assertNull(store.coreTaskResult("injected-result-failure", "project"))
        assertEquals(
            listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING),
            store.taskHistory("injected-result-failure", "project").map { it.toState }.reversed()
        )
    }

    @Test fun historyIsRolledBackWithFailedResultTransaction() {
        store.enqueue(task("rollback"))
        store.claimNext(2_000)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.update("core_tasks", ContentValues().apply { put("state", "succeeded"); put("updated_at", 3_000) },
                "task_id = ? AND state = ?", arrayOf("rollback", "running"))
            db.insertOrThrow("core_task_results", null, ContentValues().apply {
                put("task_id", "rollback")
                put("result_ciphertext", GlafiraTaskPayloadCrypto.encrypt(
                    "not committed", "rollback|core-result|project"
                ))
                put("completed_at", 3_000)
            })
            // Simulates an exception before the state and result transaction commits.
        } finally { db.endTransaction() }
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("rollback")?.state)
        assertNull(store.coreTaskResult("rollback", "project"))
        assertFalse(db.query(
            "core_task_results", arrayOf("task_id"), "task_id = ?",
            arrayOf("rollback"), null, null, null, "1"
        ).use { it.moveToFirst() })
        assertEquals(2, store.taskHistory("rollback", "project").size)
    }
    @Test fun injectedDesktopProgressWriteFailurePreservesPriorLeaseProgressAcrossReopen() {
        val now = System.currentTimeMillis()
        store.enqueue(task("desktop-progress", "desktop.execute").copy(
            payload = """{"requiredCapabilities":["code.build"]}"""
        ))
        val lease = requireNotNull(store.claimDesktopTask(
            "desktop-progress", "desktop-node-progress", setOf("code.build"), 1, now
        ))
        val before = store.readableDatabase.rawQuery(
            "SELECT lease_expires_at, progress_percent, progress_message FROM core_desktop_leases WHERE task_id = ?",
            arrayOf("desktop-progress")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            Triple(cursor.getLong(0), cursor.getInt(1), if (cursor.isNull(2)) null else cursor.getString(2))
        }
        assertEquals(0, before.second)
        assertNull(before.third)

        store.writableDatabase.execSQL("""
            CREATE TRIGGER inject_desktop_progress_update_failure
            BEFORE UPDATE ON core_desktop_leases
            WHEN NEW.progress_percent = 50
            BEGIN
                SELECT RAISE(ABORT, 'injected-desktop-progress-failure');
            END
        """.trimIndent())

        val failure = runCatching {
            store.updateDesktopTask(
                "desktop-progress", "desktop-node-progress", lease.leaseId, lease.fencingToken,
                "RUNNING", 50, "halfway", null, null, now + 100
            )
        }.exceptionOrNull()
        assertNotNull("The injected progress persistence failure must reach the caller", failure)

        val afterFailure = store.readableDatabase.rawQuery(
            "SELECT lease_expires_at, progress_percent, progress_message FROM core_desktop_leases WHERE task_id = ?",
            arrayOf("desktop-progress")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            Triple(cursor.getLong(0), cursor.getInt(1), if (cursor.isNull(2)) null else cursor.getString(2))
        }
        assertEquals(before, afterFailure)

        store.writableDatabase.execSQL("DROP TRIGGER inject_desktop_progress_update_failure")
        store.close()
        store = GlafiraTaskStore(context)
        val reopened = store.readableDatabase.rawQuery(
            "SELECT lease_expires_at, progress_percent, progress_message FROM core_desktop_leases WHERE task_id = ?",
            arrayOf("desktop-progress")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            Triple(cursor.getLong(0), cursor.getInt(1), if (cursor.isNull(2)) null else cursor.getString(2))
        }
        assertEquals(before, reopened)
    }

    @Test fun expiredDesktopLeaseDoesNotReplayOperationAndAcceptsLateBoundResult() {
        val now = System.currentTimeMillis()
        store.enqueue(task("desktop", "desktop.execute").copy(payload = """{"requiredCapabilities":["code.build"]}"""))
        val claim = store.claimDesktopTask("desktop", "desktop-node-1", setOf("code.build"), 1, now - 700_000)
        assertNotNull(claim)
        assertTrue(store.queuedDesktopTasks().isEmpty())
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("desktop")?.state)
        assertNotNull(store.desktopTaskLeaseExpiry("desktop"))
        assertNull(store.claimDesktopTask("desktop", "desktop-node-2", setOf("code.build"), 1, now))
        assertFalse(store.updateDesktopTask("desktop", "desktop-node-1", claim!!.leaseId, claim.fencingToken, "RUNNING", 50, null, null, null, now))
        assertFalse(store.updateDesktopTask("desktop", "desktop-node-2", claim.leaseId, claim.fencingToken, "SUCCEEDED", 100, null, "wrong", null, now))
        assertTrue(store.updateDesktopTask("desktop", "desktop-node-1", claim.leaseId, claim.fencingToken, "SUCCEEDED", 100, null, "actual result", null, now))
        assertTrue(store.updateDesktopTask("desktop", "desktop-node-1", claim.leaseId, claim.fencingToken, "SUCCEEDED", 100, null, "actual result", null, now))
        assertFalse(store.updateDesktopTask("desktop", "desktop-node-1", claim.leaseId, claim.fencingToken, "SUCCEEDED", 100, null, "conflict", null, now))
        assertEquals("actual result", store.desktopTaskResult("desktop"))
        assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask("desktop")?.state)
    }

    @Test fun staleDesktopLeaseGenerationCannotReleaseOrUpdateReplacementLease() {
        val now = System.currentTimeMillis()
        store.enqueue(task("desktop-fenced", "desktop.execute").copy(
            payload = """{"requiredCapabilities":["code.build"]}"""
        ))
        val first = requireNotNull(store.claimDesktopTask(
            "desktop-fenced", "desktop-node-1", setOf("code.build"), 1, now
        ))
        assertTrue(store.releaseDesktopLease(
            "desktop-fenced", "desktop-node-1", first.leaseId, first.fencingToken
        ))
        store.writableDatabase.update("core_tasks", android.content.ContentValues().apply {
            put("state", "queued")
            put("updated_at", now + 1)
        }, "task_id = ?", arrayOf("desktop-fenced"))
        val second = requireNotNull(store.claimDesktopTask(
            "desktop-fenced", "desktop-node-1", setOf("code.build"), 1, now + 2
        ))

        assertTrue(first.leaseId != second.leaseId)
        assertTrue(first.fencingToken < second.fencingToken)
        assertFalse(store.releaseDesktopLease(
            "desktop-fenced", "desktop-node-1", first.leaseId, first.fencingToken
        ))
        assertFalse(store.updateDesktopTask(
            "desktop-fenced", "desktop-node-1", first.leaseId, first.fencingToken,
            "RUNNING", 50, "stale", null, null, now + 3
        ))
        assertTrue(store.updateDesktopTask(
            "desktop-fenced", "desktop-node-1", second.leaseId, second.fencingToken,
            "RUNNING", 50, "current", null, null, now + 3
        ))
    }

    @Test fun versionOneUpgradeCreatesEveryLaterSchemaWithoutDroppingTasks() {
        store.enqueue(task("legacy-v1"))
        store.close()
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("glafira_core_tasks.db").path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try {
            listOf(
                "core_desktop_leases", "core_desktop_results", "core_desktop_nodes",
                "core_dry_run_previews", "core_task_history", "core_task_results",
                "core_task_dependencies", "core_task_plan_nodes", "core_task_plans",
                "core_live_run_grants"
            ).forEach { database.execSQL("DROP TABLE IF EXISTS $it") }
            listOf(
                "core_task_history_insert", "core_task_history_transition",
                "core_task_terminal_guard", "core_task_plan_updated"
            ).forEach { database.execSQL("DROP TRIGGER IF EXISTS $it") }
            database.version = 1
        } finally {
            database.close()
        }

        store = GlafiraTaskStore(context)
        assertEquals(9, store.readableDatabase.version)
        assertNotNull(store.getTask("legacy-v1"))
        listOf(
            "core_desktop_leases", "core_desktop_results", "core_desktop_nodes",
            "core_dry_run_previews", "core_task_history", "core_task_results",
            "core_task_dependencies", "core_task_plan_nodes", "core_task_plans",
            "core_live_run_grants"
        ).forEach { table ->
            assertTrue("Missing migrated table $table", store.readableDatabase.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name = ?",
                arrayOf(table)
            ).use { it.moveToFirst() })
        }
    }

    @Test fun versionTwoUpgradeAddsAllLaterSchemasWithoutDroppingTasks() {
        store.enqueue(task("legacy-v2"))
        store.close()
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("glafira_core_tasks.db").path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try {
            listOf(
                "core_dry_run_previews", "core_task_history", "core_task_results",
                "core_task_dependencies", "core_task_plan_nodes", "core_task_plans",
                "core_live_run_grants"
            ).forEach { database.execSQL("DROP TABLE IF EXISTS $it") }
            listOf(
                "core_task_history_insert", "core_task_history_transition",
                "core_task_terminal_guard", "core_task_plan_updated"
            ).forEach { database.execSQL("DROP TRIGGER IF EXISTS $it") }
            database.version = 2
        } finally {
            database.close()
        }

        store = GlafiraTaskStore(context)
        assertEquals(9, store.readableDatabase.version)
        assertNotNull(store.getTask("legacy-v2"))
        assertTrue(store.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='core_live_run_grants'",
            null
        ).use { it.moveToFirst() })
    }

    @Test fun versionThreeUpgradePreservesTasksAndStartsHonestHistoryBaseline() {
        store.enqueue(task("legacy"))
        val db = store.writableDatabase
        db.execSQL("DROP TRIGGER core_task_history_insert")
        db.execSQL("DROP TRIGGER core_task_history_transition")
        db.execSQL("DROP TRIGGER core_task_terminal_guard")
        db.execSQL("DROP TABLE core_task_history")
        db.version = 3
        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(9, store.writableDatabase.version)
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("legacy")?.state)
        assertEquals("HISTORY_BASELINE", store.taskHistory("legacy", "project").single().reason)
        store.claimNext(2_000)
        assertEquals(2, store.taskHistory("legacy", "project").size)
    }

    @Test fun versionSevenUpgradeAddsLeaseFencingWithoutDroppingLegacyRows() {
        store.enqueue(task("legacy-desktop").copy(taskType = GlafiraTaskStore.DESKTOP_TASK_TYPE))
        store.close()
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("glafira_core_tasks.db").path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try {
            database.execSQL("DROP TABLE core_desktop_leases")
            database.execSQL("DROP TABLE core_desktop_results")
            database.execSQL("""
                CREATE TABLE core_desktop_leases (
                    task_id TEXT PRIMARY KEY NOT NULL REFERENCES core_tasks(task_id) ON DELETE CASCADE,
                    device_id TEXT NOT NULL,
                    lease_expires_at INTEGER NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    progress_percent INTEGER NOT NULL DEFAULT 0,
                    progress_message TEXT,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE core_desktop_results (
                    task_id TEXT PRIMARY KEY NOT NULL REFERENCES core_tasks(task_id) ON DELETE CASCADE,
                    result_ciphertext TEXT,
                    error_code TEXT,
                    completed_at INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL("""
                INSERT INTO core_desktop_leases(
                    task_id, device_id, lease_expires_at, attempt_count,
                    progress_percent, progress_message, updated_at
                ) VALUES('legacy-desktop', 'agent-legacy', 9000, 3, 50, 'legacy progress', 2000)
            """.trimIndent())
            database.version = 7
        } finally {
            database.close()
        }

        store = GlafiraTaskStore(context)
        val lease = store.readableDatabase.rawQuery(
            "SELECT lease_id, fencing_token FROM core_desktop_leases WHERE task_id = ?",
            arrayOf("legacy-desktop")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0) to cursor.getLong(1)
        }
        assertTrue(lease.first.startsWith("legacy-"))
        assertEquals(3L, lease.second)
        assertNotNull(store.getTask("legacy-desktop"))
        assertTrue(store.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='core_live_run_grants'",
            null
        ).use { it.moveToFirst() })
    }

    @Test fun versionEightUpgradeAddsLiveRunGrantSchemaWithoutDroppingTasks() {
        store.enqueue(task("legacy-v8"))
        store.close()
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("glafira_core_tasks.db").path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try {
            database.execSQL("DROP TABLE IF EXISTS core_live_run_grants")
            database.version = 8
        } finally {
            database.close()
        }

        store = GlafiraTaskStore(context)
        assertNotNull(store.getTask("legacy-v8"))
        assertTrue(store.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='core_live_run_grants'",
            null
        ).use { it.moveToFirst() })
    }

    @Test fun versionFourUpgradeCreatesEncryptedCoreResultTable() {
        store.enqueue(task("legacy-v4"))
        store.writableDatabase.execSQL("DROP TABLE core_task_results")
        store.writableDatabase.version = 4
        store.close()
        store = GlafiraTaskStore(context)

        assertEquals(9, store.writableDatabase.version)
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("legacy-v4")?.state)
        assertTrue(store.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'core_task_results'",
            null
        ).use { it.moveToFirst() })
    }

    @Test fun desktopProjectFilterRunsBeforeLimitAndCannotStarvePermittedProject() {
        repeat(101) { index ->
            store.enqueue(task("other-$index", "desktop.execute", "other-project").copy(priority = 100))
        }
        store.enqueue(task("permitted", "desktop.execute", "permitted-project"))
        assertTrue(store.queuedDesktopTasks().none { it.taskId == "permitted" })
        assertEquals(listOf("permitted"),
            store.queuedDesktopTasks(limit = 1, projectId = "permitted-project").map { it.taskId })
        assertTrue(store.queuedDesktopTasks(projectId = "missing-project").isEmpty())
    }

    @Test fun concurrentSqliteClaimsNeverReturnTheSameTaskToTwoWorkers() {
        val taskCount = 80
        repeat(taskCount) { index -> store.enqueue(task("parallel-$index")) }
        val start = CountDownLatch(1)
        val claimedIds = Collections.synchronizedList(mutableListOf<String>())
        val secondStore = GlafiraTaskStore(context)
        val stores = listOf(store, secondStore)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val workers = (0 until 8).map { workerIndex ->
                val workerStore = stores[workerIndex % stores.size]
                pool.submit {
                    start.await()
                    while (true) {
                        val claimed = workerStore.claimNext(2_000) ?: break
                        claimedIds += claimed.taskId
                        check(workerStore.persistCoreTaskResult(
                            claimed.taskId,
                            setOf(GlafiraTaskState.RUNNING),
                            "result-${claimed.taskId}",
                            3_000
                        ))
                    }
                }
            }
            start.countDown()
            workers.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            secondStore.close()
        }

        assertEquals(taskCount, claimedIds.size)
        assertEquals(taskCount, claimedIds.toSet().size)
        assertEquals(taskCount, store.listTasks(setOf(GlafiraTaskState.SUCCEEDED)).size)
        repeat(taskCount) { index ->
            assertEquals(
                listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING, GlafiraTaskState.SUCCEEDED),
                store.taskHistory("parallel-$index", "project").map { it.toState }.reversed()
            )
            assertEquals("result-parallel-$index", store.coreTaskResult("parallel-$index", "project")?.result)
        }
    }

    @Test fun cancellationVsCompletionRaceHasExactlyOneDurableWinner() {
        store.enqueue(task("cancel-complete-race"))
        assertNotNull(store.claimNext(2_000))
        val secondStore = GlafiraTaskStore(context)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val completion = pool.submit<Boolean> {
                start.await()
                store.persistCoreTaskResult(
                    "cancel-complete-race",
                    setOf(GlafiraTaskState.RUNNING),
                    "completed-before-cancel",
                    3_000
                )
            }
            val cancellation = pool.submit<Boolean> {
                start.await()
                secondStore.transition(
                    "cancel-complete-race",
                    setOf(GlafiraTaskState.RUNNING),
                    GlafiraTaskState.RETRY_SCHEDULED,
                    3_000,
                    retryAtMillis = 3_000,
                    lastError = "EXECUTION_CANCELLED"
                )
            }
            start.countDown()
            val completed = completion.get(15, TimeUnit.SECONDS)
            val cancelled = cancellation.get(15, TimeUnit.SECONDS)

            assertNotEquals("Exactly one compare-and-set must win", completed, cancelled)
            val task = requireNotNull(store.getTask("cancel-complete-race"))
            if (completed) {
                assertEquals(GlafiraTaskState.SUCCEEDED, task.state)
                assertEquals(
                    "completed-before-cancel",
                    store.coreTaskResult("cancel-complete-race", "project")?.result
                )
            } else {
                assertEquals(GlafiraTaskState.RETRY_SCHEDULED, task.state)
                assertNull(store.coreTaskResult("cancel-complete-race", "project"))
                assertEquals("EXECUTION_CANCELLED", task.lastError)
            }
        } finally {
            pool.shutdownNow()
            secondStore.close()
        }
    }

    @Test fun processRestartRecoveryRequeuesSafeReadsButQuarantinesUnknownWrites() {
        store.enqueue(task("restart-read", "project.read"))
        store.enqueue(task("restart-write", "message.send"))
        repeat(2) { assertNotNull(store.claimNext(2_000)) }

        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(2, store.recoverInterruptedCoreTasks(3_000, 5))

        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, store.getTask("restart-read")?.state)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("restart-write")?.state)
        assertEquals("restart-read", store.claimNext(4_000)?.taskId)
        assertNull(store.claimNext(4_000))
    }

    @Test fun successfulCoreResultIsEncryptedAndSurvivesDatabaseReopen() {
        store.enqueue(task("result-task"))
        assertNotNull(store.claimNext(2_000))
        assertTrue(store.persistCoreTaskResult(
            "result-task", setOf(GlafiraTaskState.RUNNING), "private handler result", 3_000
        ))
        assertTrue(store.persistCoreTaskResult(
            "result-task", setOf(GlafiraTaskState.RUNNING), "private handler result", 4_000
        ))
        assertThrows(IllegalStateException::class.java) {
            store.persistCoreTaskResult(
                "result-task", setOf(GlafiraTaskState.RUNNING), "different result", 4_000
            )
        }

        val ciphertext = store.readableDatabase.query(
            "core_task_results", arrayOf("result_ciphertext"),
            "task_id = ?", arrayOf("result-task"), null, null, null, "1"
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
        assertFalse(ciphertext.contains("private handler result"))
        assertEquals("private handler result", store.coreTaskResult("result-task", "project")?.result)
        assertNull(store.coreTaskResult("result-task", "another-project"))

        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask("result-task")?.state)
        assertEquals("private handler result", store.coreTaskResult("result-task", "project")?.result)
    }


    @Test fun injectedRetryHistoryFailureAfterHandlerExceptionLeavesTaskRecoverableAfterRestart() = runBlocking {
        store.enqueue(task("handler-failure-recovery"))
        store.writableDatabase.execSQL("""
            CREATE TRIGGER inject_core_retry_history_failure
            BEFORE INSERT ON core_task_history
            WHEN NEW.to_state = 'retry_scheduled'
            BEGIN
                SELECT RAISE(ABORT, 'injected-retry-history-failure');
            END
        """.trimIndent())

        val executor = GlafiraCoreTaskExecutor(
            queue = store,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler {
                throw IllegalStateException("injected-handler-failure")
            }),
            dispatcher = Dispatchers.IO,
            clock = { 2_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 1) },
            executionMode = { GlafiraExecutionMode.LIVE }
        )

        val failure = runCatching { executor.executeNext() }.exceptionOrNull()
        assertNotNull("The injected history failure must escape the failed retry persistence", failure)
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("handler-failure-recovery")?.state)
        assertEquals(
            listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING),
            store.taskHistory("handler-failure-recovery", "project").map { it.toState }.reversed()
        )

        store.writableDatabase.execSQL("DROP TRIGGER inject_core_retry_history_failure")
        store.close()
        store = GlafiraTaskStore(context)

        assertEquals(1, store.recoverInterruptedCoreTasks(3_000L, 5))
        val recovered = requireNotNull(store.getTask("handler-failure-recovery"))
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, recovered.state)
        assertEquals(3_000L, recovered.retryAtMillis)
        assertEquals("CORE_EXECUTION_INTERRUPTED", recovered.lastError)
        assertEquals(
            listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING, GlafiraTaskState.RETRY_SCHEDULED),
            store.taskHistory("handler-failure-recovery", "project").map { it.toState }.reversed()
        )
    }

    @Test fun injectedResultAndRetryHistoryFailuresRecoverCompletedReadAfterRestart() = runBlocking {
        store.enqueue(task("result-persist-recovery"))
        store.writableDatabase.execSQL("""
            CREATE TRIGGER inject_core_result_persist_failure
            BEFORE INSERT ON core_task_results
            BEGIN
                SELECT RAISE(ABORT, 'injected-result-persist-failure');
            END
        """.trimIndent())
        store.writableDatabase.execSQL("""
            CREATE TRIGGER inject_core_retry_history_after_result_failure
            BEFORE INSERT ON core_task_history
            WHEN NEW.to_state = 'retry_scheduled'
            BEGIN
                SELECT RAISE(ABORT, 'injected-retry-history-after-result-failure');
            END
        """.trimIndent())

        val executor = GlafiraCoreTaskExecutor(
            queue = store,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler {
                GlafiraTaskExecutionOutcome.Succeeded("handler completed before result-store failure")
            }),
            dispatcher = Dispatchers.IO,
            clock = { 2_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 1) },
            executionMode = { GlafiraExecutionMode.LIVE }
        )

        val execution = executor.executeNext()
        assertEquals(GlafiraTaskExecutionStatus.FAILED, execution.status)
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("result-persist-recovery")?.state)
        assertNull(store.coreTaskResult("result-persist-recovery", "project"))
        assertEquals(
            listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING),
            store.taskHistory("result-persist-recovery", "project").map { it.toState }.reversed()
        )

        store.writableDatabase.execSQL("DROP TRIGGER inject_core_result_persist_failure")
        store.writableDatabase.execSQL("DROP TRIGGER inject_core_retry_history_after_result_failure")
        store.close()
        store = GlafiraTaskStore(context)

        assertEquals(1, store.recoverInterruptedCoreTasks(3_000L, 5))
        val recovered = requireNotNull(store.getTask("result-persist-recovery"))
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, recovered.state)
        assertEquals(3_000L, recovered.retryAtMillis)
        assertEquals("CORE_EXECUTION_INTERRUPTED", recovered.lastError)
        assertNull(store.coreTaskResult("result-persist-recovery", "project"))
        assertEquals(
            listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING, GlafiraTaskState.RETRY_SCHEDULED),
            store.taskHistory("result-persist-recovery", "project").map { it.toState }.reversed()
        )
    }

    @Test fun coreExecutorPersistsHandlerSummaryWithSucceededState() = runBlocking {
        store.enqueue(task("executor-result"))
        val executor = GlafiraCoreTaskExecutor(
            queue = store,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler {
                GlafiraTaskExecutionOutcome.Succeeded("persisted by Core executor")
            }),
            dispatcher = Dispatchers.IO,
            clock = { 2_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 1) },
            executionMode = { GlafiraExecutionMode.LIVE }
        )

        val result = executor.executeNext()

        assertEquals(GlafiraTaskExecutionStatus.SUCCEEDED, result.status)
        assertEquals("persisted by Core executor", store.coreTaskResult("executor-result", "project")?.result)
        assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask("executor-result")?.state)
    }

    @Test fun acknowledgedReadPreviewNeedsFreshDurableGrantBeforeLiveHandlerRuns() = runBlocking {
        val read = task("reviewed-live-read").copy(
            payload = """{"actionSummary":"Read current project metadata"}""",
            requiredScopes = setOf("project.read"),
            deadlineAtMillis = 100_000L
        )
        store.enqueue(read)
        val handlerCalls = java.util.concurrent.atomic.AtomicInteger()
        val dryExecutor = GlafiraCoreTaskExecutor(
            queue = store,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler {
                handlerCalls.incrementAndGet()
                GlafiraTaskExecutionOutcome.Succeeded("unexpected dry-run execution")
            }),
            dispatcher = Dispatchers.IO,
            clock = { 2_000L },
            executionMode = { GlafiraExecutionMode.LIVE },
            liveTaskAdmission = GlafiraLiveTaskAdmission.DENY_ALL,
            dryRunPreviewPersistence = store
        )

        assertEquals(GlafiraTaskExecutionStatus.DRY_RUN_PREVIEWED, dryExecutor.executeNext().status)
        assertEquals(0, handlerCalls.get())
        val preview = requireNotNull(store.dryRunPreview(read.taskId))
        assertEquals(GlafiraDryRunReviewStatus.PENDING, preview.reviewStatus)
        assertTrue(store.reviewDryRunPreview(
            read.taskId, preview.planHash, GlafiraDryRunReviewStatus.ACKNOWLEDGED, 2_500L
        ))
        val liveRuns = GlafiraCoreLiveRunStore(store)
        assertEquals(
            GlafiraLiveRunApprovalMutation.APPROVED,
            liveRuns.approveReadOnlyRun(read.taskId, preview.planHash, ownerSession(), 3_000L)
        )
        assertEquals(GlafiraTaskState.QUEUED, store.getTask(read.taskId)?.state)

        store.close()
        store = GlafiraTaskStore(context)
        val reopenedAdmission = GlafiraCoreLiveRunStore(store)
        assertTrue(reopenedAdmission.allows(requireNotNull(store.getTask(read.taskId)), 4_000L))
        val liveExecutor = GlafiraCoreTaskExecutor(
            queue = store,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler {
                handlerCalls.incrementAndGet()
                GlafiraTaskExecutionOutcome.Succeeded("durable reviewed read")
            }),
            dispatcher = Dispatchers.IO,
            clock = { 4_000L },
            executionMode = { GlafiraExecutionMode.LIVE },
            liveTaskAdmission = reopenedAdmission,
            dryRunPreviewPersistence = store
        )

        assertEquals(GlafiraTaskExecutionStatus.SUCCEEDED, liveExecutor.executeNext().status)
        assertEquals(1, handlerCalls.get())
        assertEquals("durable reviewed read", store.coreTaskResult(read.taskId, read.projectId)?.result)
    }

    @Test fun acknowledgedExternalWritePreviewCannotReceiveInitialLiveReadGrant() = runBlocking {
        val write = task("write-not-admitted", "message.send").copy(
            payload = """{"actionSummary":"Send a message"}""",
            requiredScopes = setOf("communications.send"),
            deadlineAtMillis = 100_000L
        )
        store.enqueue(write)
        val executor = GlafiraCoreTaskExecutor(
            queue = store,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER, "OWNER_APPROVAL_REQUIRED")
            },
            handlers = listOf("message.send" to GlafiraTaskHandler {
                GlafiraTaskExecutionOutcome.Succeeded("must not send")
            }),
            dispatcher = Dispatchers.IO,
            clock = { 2_000L },
            executionMode = { GlafiraExecutionMode.DRY_RUN },
            dryRunPreviewPersistence = store
        )
        assertEquals(GlafiraTaskExecutionStatus.DRY_RUN_PREVIEWED, executor.executeNext().status)
        val preview = requireNotNull(store.dryRunPreview(write.taskId))
        assertTrue(store.reviewDryRunPreview(
            write.taskId, preview.planHash, GlafiraDryRunReviewStatus.ACKNOWLEDGED, 2_500L
        ))

        assertEquals(
            GlafiraLiveRunApprovalMutation.TOOL_NOT_ALLOWED,
            GlafiraCoreLiveRunStore(store).approveReadOnlyRun(
                write.taskId, preview.planHash, ownerSession(), 3_000L
            )
        )
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask(write.taskId)?.state)
    }

    @Test fun dryRunApprovalInspectionDoesNotConsumePersistentOneUseGrant() {
        val actionSummary = "Send the approved customer update"
        val queued = task("dry-run-owner-grant", "message.send").copy(
            payload = org.json.JSONObject().put("actionSummary", actionSummary).toString()
        )
        val now = System.currentTimeMillis()
        store.enqueue(queued)
        assertNotNull(store.claimNext(now + 1L))

        val approvals = GlafiraCoreOwnerApprovalStore(store)
        assertTrue(approvals.requestApproval(queued, actionSummary, now + 2L))
        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                queued.taskId,
                setOf("communications.send"),
                ownerSession(),
                now + 3L
            )
        )

        val current = requireNotNull(store.getTask(queued.taskId))
        val authorizer = RegistryBackedGlafiraTaskAuthorizer(approvals)
        assertEquals(
            GlafiraTaskAuthorizationStatus.AUTHORIZED,
            authorizer.inspectForDryRun(current, now + 4L).status
        )
        assertEquals(
            GlafiraTaskAuthorizationStatus.AUTHORIZED,
            authorizer.inspectForDryRun(current, now + 5L).status
        )
        assertNotNull(approvals.peekApprovalGrant(current, actionSummary, now + 5L))

        // Only the real execution authorization consumes the one-use approval.
        assertEquals(
            GlafiraTaskAuthorizationStatus.AUTHORIZED,
            authorizer.authorize(current, now + 6L).status
        )
        assertNull(approvals.peekApprovalGrant(current, actionSummary, now + 7L))
    }

    private fun ownerSession(): GlafiraCoreOwnerSession {
        val constructor = GlafiraCoreOwnerSession::class.java.getDeclaredConstructor(
            String::class.java,
            java.lang.Long.TYPE
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            UUID.randomUUID().toString(),
            SystemClock.elapsedRealtime() + 60_000L
        )
    }


}
