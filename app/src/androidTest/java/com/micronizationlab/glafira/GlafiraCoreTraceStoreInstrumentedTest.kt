package com.micronizationlab.glafira

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GlafiraCoreTraceStoreInstrumentedTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().targetContext }
    private lateinit var taskStore: GlafiraTaskStore
    private lateinit var traceStore: GlafiraCoreTraceStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        taskStore = GlafiraTaskStore(context)
        traceStore = GlafiraCoreTraceStore(taskStore)
    }

    @After
    fun tearDown() {
        taskStore.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun appendIsIdempotentAndRedactsActorBeforePersistence() {
        val draft = event(
            eventId = "trace-redaction-1",
            projectId = "trace-project",
            actorId = "alice@example.invalid"
        )

        val first = traceStore.append(draft)
        assertEquals(first, traceStore.append(draft))

        val stored = traceStore.query(
            GlafiraCoreTraceQuery(projectId = "trace-project"),
            GlafiraCoreTraceAccessContext(projectId = "trace-project", allowProjectEvents = true)
        ).events.single()
        assertEquals("redacted", stored.actorId)
        assertTrue(stored.redactionApplied)
        assertFalse(stored.secretValuesStored)

        assertThrows(IllegalStateException::class.java) {
            traceStore.append(draft.copy(actionCode = "TASK_FAILED"))
        }
    }

    @Test
    fun taskScopedReadersCannotReadSiblingTasksOrProjectEventsByDefault() {
        taskStore.enqueue(task("trace-task-a", "trace-project"))
        taskStore.enqueue(task("trace-task-b", "trace-project"))
        traceStore.append(event("trace-task-event-a", "trace-project", taskId = "trace-task-a", occurredAt = 1_000L))
        traceStore.append(event("trace-task-event-b", "trace-project", taskId = "trace-task-b", occurredAt = 2_000L))
        traceStore.append(event("trace-project-event", "trace-project", taskId = null, occurredAt = 3_000L))

        val taskOnly = traceStore.query(
            GlafiraCoreTraceQuery(projectId = "trace-project"),
            GlafiraCoreTraceAccessContext(projectId = "trace-project", taskIds = setOf("trace-task-a"))
        )
        assertEquals(listOf("trace-task-a"), taskOnly.events.map { it.taskId })

        val taskAndProject = traceStore.query(
            GlafiraCoreTraceQuery(projectId = "trace-project"),
            GlafiraCoreTraceAccessContext(
                projectId = "trace-project",
                taskIds = setOf("trace-task-a"),
                allowProjectEvents = true
            )
        )
        assertEquals(setOf("trace-task-a", null), taskAndProject.events.map { it.taskId }.toSet())

        val crossProject = traceStore.query(
            GlafiraCoreTraceQuery(projectId = "another-project"),
            GlafiraCoreTraceAccessContext(projectId = "trace-project", taskIds = null)
        )
        assertTrue(crossProject.events.isEmpty())
    }

    @Test
    fun parentEventMustExistInTheSameProject() {
        traceStore.append(event("trace-parent", "trace-project"))
        traceStore.append(
            event("trace-child", "trace-project", occurredAt = 2_000L)
                .copy(parentEventId = "trace-parent")
        )
        assertThrows(IllegalStateException::class.java) {
            traceStore.append(
                event("trace-cross-project-child", "another-project", occurredAt = 3_000L)
                    .copy(parentEventId = "trace-parent")
            )
        }
    }

    @Test
    fun paginationUsesStableOccurredAtAndEventIdCursor() {
        (1..3).forEach { index ->
            traceStore.append(
                event(
                    eventId = "trace-page-$index",
                    projectId = "trace-page-project",
                    occurredAt = index * 1_000L
                )
            )
        }
        val access = GlafiraCoreTraceAccessContext("trace-page-project", allowProjectEvents = true)
        val first = traceStore.query(GlafiraCoreTraceQuery("trace-page-project", limit = 2), access)
        assertEquals(listOf("trace-page-3", "trace-page-2"), first.events.map { it.eventId })
        assertNotNull(first.nextCursor)

        val second = traceStore.query(
            GlafiraCoreTraceQuery("trace-page-project", afterEventId = first.nextCursor, limit = 2),
            access
        )
        assertEquals(listOf("trace-page-1"), second.events.map { it.eventId })
        assertNull(second.nextCursor)
    }

    @Test
    fun inTransactionAppendRollsBackWithItsOwningCoreTransaction() {
        val db = taskStore.writableDatabase
        db.beginTransaction()
        try {
            traceStore.appendInTransaction(
                db,
                event("trace-transaction-rollback", "trace-project")
            )
            // Deliberately do not mark the caller's transaction successful.
        } finally {
            db.endTransaction()
        }

        val page = traceStore.query(
            GlafiraCoreTraceQuery("trace-project"),
            GlafiraCoreTraceAccessContext("trace-project", allowProjectEvents = true)
        )
        assertTrue(page.events.isEmpty())
    }

    @Test
    fun sqliteRejectsTraceUpdateAndDeleteOutsidePortableRestore() {
        val eventId = traceStore.append(event("trace-append-only", "trace-project"))
        val db = taskStore.writableDatabase

        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "UPDATE ${GlafiraCoreTraceStore.TABLE} SET action_code = ? WHERE event_id = ?",
                arrayOf("TASK_FAILED", eventId)
            )
        }
        assertThrows(SQLiteException::class.java) {
            db.delete(GlafiraCoreTraceStore.TABLE, "event_id = ?", arrayOf(eventId))
        }
    }

    @Test
    fun ownerProjectDeletionPurgesTraceAndRestoresAppendOnlyGuards() {
        taskStore.enqueue(task("delete-project-task", "delete-project"))
        assertNotNull(taskStore.claimNext(2_000L))
        assertTrue(taskStore.transition(
            "delete-project-task",
            setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.SUCCEEDED,
            3_000L
        ))
        traceStore.append(event(
            "delete-project-task-event", "delete-project",
            taskId = "delete-project-task", occurredAt = 4_000L
        ))
        traceStore.append(event("delete-project-only-event", "delete-project", occurredAt = 5_000L))
        traceStore.append(event("preserved-other-project-event", "preserved-project", occurredAt = 6_000L))

        taskStore.deleteFinishedProject("delete-project")

        assertTrue(traceStore.query(
            GlafiraCoreTraceQuery("delete-project"),
            GlafiraCoreTraceAccessContext("delete-project")
        ).events.isEmpty())
        assertEquals(
            listOf("preserved-other-project-event"),
            traceStore.query(
                GlafiraCoreTraceQuery("preserved-project"),
                GlafiraCoreTraceAccessContext("preserved-project")
            ).events.map { it.eventId }
        )

        assertThrows(SQLiteException::class.java) {
            taskStore.writableDatabase.delete(
                GlafiraCoreTraceStore.TABLE,
                "event_id = ?",
                arrayOf("preserved-other-project-event")
            )
        }
    }

    @Test
    fun taskLifecycleTraceSharesTransactionsWithQueueAndResultWrites() {
        val taskId = "trace-lifecycle-task"
        val projectId = "trace-lifecycle-project"
        val db = taskStore.writableDatabase

        assertThrows(IllegalArgumentException::class.java) {
            taskStore.enqueue(task("unsafe task id", projectId))
        }
        assertThrows(IllegalArgumentException::class.java) {
            taskStore.enqueue(task("safe-task-id", "unsafe project id"))
        }

        db.execSQL("""
            CREATE TRIGGER fail_trace_task_create
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.event_type = 'TASK_CREATED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) { taskStore.enqueue(task(taskId, projectId)) }
        assertNull(taskStore.getTask(taskId))
        db.execSQL("DROP TRIGGER fail_trace_task_create")
        taskStore.enqueue(task(taskId, projectId))

        db.execSQL("""
            CREATE TRIGGER fail_trace_task_start
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.event_type = 'TASK_STARTED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) { taskStore.claimNext(2_000L) }
        assertEquals(GlafiraTaskState.QUEUED, taskStore.getTask(taskId)?.state)
        assertEquals(0, taskStore.getTask(taskId)?.attemptCount)
        db.execSQL("DROP TRIGGER fail_trace_task_start")

        assertNotNull(taskStore.claimNext(3_000L))
        db.execSQL("""
            CREATE TRIGGER fail_trace_result_commit
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.action_code = 'TASK_RESULT_COMMITTED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            taskStore.persistCoreTaskResult(
                taskId,
                setOf(GlafiraTaskState.RUNNING),
                "durable-result",
                4_000L
            )
        }
        assertEquals(GlafiraTaskState.RUNNING, taskStore.getTask(taskId)?.state)
        assertNull(taskStore.coreTaskResult(taskId, projectId))
        db.execSQL("DROP TRIGGER fail_trace_result_commit")

        assertTrue(taskStore.persistCoreTaskResult(
            taskId,
            setOf(GlafiraTaskState.RUNNING),
            "durable-result",
            5_000L
        ))
        val page = traceStore.query(
            GlafiraCoreTraceQuery(projectId, taskId = taskId),
            GlafiraCoreTraceAccessContext(projectId, taskIds = setOf(taskId))
        )
        assertEquals(4, page.events.size)
        assertEquals(
            setOf(
                GlafiraCoreTraceEventType.TASK_CREATED,
                GlafiraCoreTraceEventType.TASK_STARTED,
                GlafiraCoreTraceEventType.TASK_SUCCEEDED
            ),
            page.events.map { it.eventType }.toSet()
        )
        assertEquals(
            setOf("TASK_CREATED", "TASK_STATE_CHANGED", "TASK_RESULT_COMMITTED"),
            page.events.map { it.actionCode }.toSet()
        )
    }

    @Test
    fun dryRunPreviewAndPolicyTraceCommitOrRollBackTogether() {
        val taskId = "trace-dry-run-task"
        val projectId = "trace-dry-run-project"
        val original = task(taskId, projectId).copy(
            payload = org.json.JSONObject().put("actionSummary", "Preview this task").toString()
        )
        taskStore.enqueue(original)
        assertNotNull(taskStore.claimNext(2_000L))
        val plan = GlafiraDryRunPlanner.build(
            original,
            GlafiraTaskAuthorizationResult(
                GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER,
                "OWNER_APPROVAL_REQUIRED"
            )
        )
        val db = taskStore.writableDatabase
        db.execSQL("""
            CREATE TRIGGER fail_dry_run_trace
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.action_code = 'DRY_RUN_PREVIEW_STORED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_DRY_RUN_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            taskStore.persistDryRunPreview(
                taskId, setOf(GlafiraTaskState.RUNNING), plan, 3_000L
            )
        }
        assertEquals(GlafiraTaskState.RUNNING, taskStore.getTask(taskId)?.state)
        assertNull(taskStore.dryRunPreview(taskId))
        db.execSQL("DROP TRIGGER fail_dry_run_trace")

        assertTrue(taskStore.persistDryRunPreview(
            taskId, setOf(GlafiraTaskState.RUNNING), plan, 4_000L
        ))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, taskStore.getTask(taskId)?.state)
        assertNotNull(taskStore.dryRunPreview(taskId))
        db.execSQL("""
            CREATE TRIGGER fail_dry_run_review_trace
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.event_type = 'REVIEW_ACCEPTED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_REVIEW_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            taskStore.reviewDryRunPreview(
                taskId, plan.planHash, GlafiraDryRunReviewStatus.ACKNOWLEDGED, 5_000L
            )
        }
        assertEquals(GlafiraDryRunReviewStatus.PENDING, taskStore.dryRunPreview(taskId)?.reviewStatus)
        db.execSQL("DROP TRIGGER fail_dry_run_review_trace")
        assertTrue(taskStore.reviewDryRunPreview(
            taskId, plan.planHash, GlafiraDryRunReviewStatus.ACKNOWLEDGED, 6_000L
        ))
        assertEquals(GlafiraDryRunReviewStatus.ACKNOWLEDGED, taskStore.dryRunPreview(taskId)?.reviewStatus)
        val events = traceStore.query(
            GlafiraCoreTraceQuery(projectId, taskId = taskId),
            GlafiraCoreTraceAccessContext(projectId, taskIds = setOf(taskId))
        ).events
        assertTrue(events.any {
            it.eventType == GlafiraCoreTraceEventType.POLICY_CHECKED &&
                it.actionCode == "DRY_RUN_PREVIEW_STORED"
        })
        assertTrue(events.any {
            it.eventType == GlafiraCoreTraceEventType.REVIEW_ACCEPTED &&
                it.actionCode == "DRY_RUN_REVIEW_ACCEPTED" &&
                it.actorType == GlafiraCoreTraceActorType.OWNER
        })
    }

    @Test
    fun interruptedTaskRecoveryAndTraceTransitionCommitOrRollBackTogether() {
        val taskId = "trace-recovery-task"
        val projectId = "trace-recovery-project"
        taskStore.enqueue(task(taskId, projectId))
        assertNotNull(taskStore.claimNext(2_000L))
        val db = taskStore.writableDatabase
        db.execSQL("""
            CREATE TRIGGER fail_trace_recovery
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.event_type = 'TASK_QUEUED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_RECOVERY_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            taskStore.recoverInterruptedCoreTasks(3_000L, maxRetries = 3)
        }
        assertEquals(GlafiraTaskState.RUNNING, taskStore.getTask(taskId)?.state)
        db.execSQL("DROP TRIGGER fail_trace_recovery")

        assertEquals(1, taskStore.recoverInterruptedCoreTasks(4_000L, maxRetries = 3))
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, taskStore.getTask(taskId)?.state)
        val events = traceStore.query(
            GlafiraCoreTraceQuery(projectId, taskId = taskId),
            GlafiraCoreTraceAccessContext(projectId, taskIds = setOf(taskId))
        ).events
        assertEquals(
            setOf(
                GlafiraCoreTraceEventType.TASK_CREATED,
                GlafiraCoreTraceEventType.TASK_STARTED,
                GlafiraCoreTraceEventType.TASK_QUEUED
            ),
            events.map { it.eventType }.toSet()
        )
    }

    @Test
    fun legacyV2PortableSnapshotWithoutTraceTableMigratesToEmptyTraceTable() {
        val tables = JSONObject().apply {
            listOf(
                "core_tasks",
                "core_task_plans",
                "core_task_plan_nodes",
                "core_task_dependencies",
                "core_desktop_results",
                "core_task_results",
                "core_dry_run_previews",
                "core_task_history"
            ).forEach { put(it, JSONArray()) }
        }
        val legacy = JSONObject()
            .put("format", "GLAFIRA_PORTABLE_CORE")
            .put("version", 2)
            .put("tables", tables)
        val file = File(context.cacheDir, "legacy-core-v2-${UUID.randomUUID()}.json")
        file.writeText(legacy.toString())
        try {
            val verified = GlafiraPortableCoreSnapshot(taskStore).readVerified(file)
            assertEquals(3, verified.getInt("version"))
            assertEquals(0, verified.getJSONObject("tables")
                .getJSONArray(GlafiraCoreTraceStore.TABLE).length())
        } finally {
            file.delete()
        }
    }

    private fun task(id: String, project: String) = GlafiraTask(
        taskId = id,
        projectId = project,
        taskType = "project.read",
        payload = "{}",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = id
    )

    private fun event(
        eventId: String,
        projectId: String,
        taskId: String? = null,
        occurredAt: Long = 1_000L,
        actorId: String = "owner-1"
    ) = GlafiraCoreTraceDraft(
        eventId = eventId,
        projectId = projectId,
        taskId = taskId,
        correlationId = "corr-$eventId",
        occurredAtMillis = occurredAt,
        actorType = GlafiraCoreTraceActorType.OWNER,
        actorId = actorId,
        component = "core",
        eventType = GlafiraCoreTraceEventType.TASK_CREATED,
        actionCode = "TASK_CREATED",
        dataClassification = GlafiraCoreTraceDataClassification.INTERNAL
    )

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
