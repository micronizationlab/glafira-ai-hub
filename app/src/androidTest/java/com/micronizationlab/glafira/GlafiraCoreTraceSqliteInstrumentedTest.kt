package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreTraceSqliteInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore
    private lateinit var trace: GlafiraCoreTraceSqliteRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        store = GlafiraTaskStore(context)
        trace = GlafiraCoreTraceSqliteRepository(store)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    private fun task(id: String) = GlafiraTask(
        taskId = id,
        projectId = PROJECT_ID,
        taskType = "project.read",
        payload = "trace-test-$id",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "trace-test-$id"
    )

    private fun draft(
        id: String,
        taskId: String? = null,
        eventType: GlafiraCoreTraceEventType = GlafiraCoreTraceEventType.POLICY_CHECKED,
        occurredAt: Long = 10_000L,
        actorId: String = "owner-001",
        actionCode: String = "policy.checked",
        targetId: String? = taskId,
        evidenceRefs: List<String> = listOf("evidence-001")
    ) = GlafiraCoreTraceDraft(
        eventId = id,
        projectId = PROJECT_ID,
        taskId = taskId,
        correlationId = "correlation-$id",
        occurredAtMillis = occurredAt,
        actorType = GlafiraCoreTraceActorType.CORE,
        actorId = actorId,
        component = "PolicyEngine",
        eventType = eventType,
        actionCode = actionCode,
        targetType = if (taskId == null) null else "TASK",
        targetId = targetId,
        resultStatus = "RECORDED",
        evidenceRefs = evidenceRefs
    )

    private fun query(
        taskId: String? = null,
        eventTypes: Set<GlafiraCoreTraceEventType> = emptySet(),
        limit: Int = 50,
        afterEventId: String? = null,
        taskIds: Set<String>? = null,
        allowProjectEvents: Boolean = false
    ) = trace.query(
        GlafiraCoreTraceQuery(
            projectId = PROJECT_ID,
            taskId = taskId,
            eventTypes = eventTypes,
            afterEventId = afterEventId,
            limit = limit
        ),
        GlafiraCoreTraceAccessContext(PROJECT_ID, taskIds, allowProjectEvents)
    )

    private fun count(sql: String, args: Array<String> = emptyArray()): Long =
        store.readableDatabase.rawQuery(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    @Test
    fun appendRedactsBeforePersistenceAndReturnsOnlyMetadata() {
        store.enqueue(task("redaction-task"))
        val eventId = trace.append(draft(
            id = "trace-redaction",
            taskId = "redaction-task",
            actorId = "owner@example.com",
            actionCode = "Bearer abcdefghijklmnopqrstuvwxyz123456",
            targetId = "https://user:password@example.com/private",
            evidenceRefs = listOf("ghp_12345678901234567890")
        ))

        val event = query(
            taskId = "redaction-task",
            eventTypes = setOf(GlafiraCoreTraceEventType.POLICY_CHECKED),
            taskIds = setOf("redaction-task")
        ).events.single()
        assertEquals("trace-redaction", eventId)
        assertEquals("redacted", event.actorId)
        assertEquals("redacted", event.actionCode)
        assertEquals("redacted", event.targetId)
        assertEquals(listOf("redacted"), event.evidenceRefs)
        assertTrue(event.redactionApplied)
        assertFalse(event.secretValuesStored)
        assertEquals("redacted", store.readableDatabase.rawQuery(
            "SELECT actor_id FROM core_trace_events WHERE event_id = ?",
            arrayOf(eventId)
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
    }

    @Test
    fun queryEnforcesTaskScopeAndUsesBoundedOpaqueCursorPagination() {
        store.enqueue(task("one"))
        store.enqueue(task("two"))
        (1..3).forEach { index ->
            trace.append(draft("policy-$index", taskId = "one", occurredAt = 10_000L + index))
        }

        val first = query(
            taskId = "one",
            eventTypes = setOf(GlafiraCoreTraceEventType.POLICY_CHECKED),
            limit = 2,
            taskIds = setOf("one")
        )
        assertEquals(2, first.events.size)
        assertNotNull(first.nextCursor)

        val second = query(
            taskId = "one",
            eventTypes = setOf(GlafiraCoreTraceEventType.POLICY_CHECKED),
            limit = 2,
            afterEventId = first.nextCursor,
            taskIds = setOf("one")
        )
        assertEquals(1, second.events.size)
        assertNull(second.nextCursor)
        assertTrue(second.events.none { it.taskId == "two" })

        assertTrue(query(
            taskId = "one",
            eventTypes = setOf(GlafiraCoreTraceEventType.POLICY_CHECKED),
            taskIds = setOf("two")
        ).events.isEmpty())
        assertTrue(query(
            taskId = "two",
            eventTypes = setOf(GlafiraCoreTraceEventType.POLICY_CHECKED),
            afterEventId = first.nextCursor,
            taskIds = setOf("two")
        ).events.isEmpty())
        assertThrows(SecurityException::class.java) {
            trace.query(
                GlafiraCoreTraceQuery(projectId = "another-project"),
                GlafiraCoreTraceAccessContext(PROJECT_ID)
            )
        }
    }

    @Test
    fun traceRowsCannotBeUpdatedAndTaskHistoryWritesTraceAtomically() {
        store.enqueue(task("atomic-task"))
        val beforeHistory = count(
            "SELECT COUNT(*) FROM core_task_history WHERE task_id = ?",
            arrayOf("atomic-task")
        )
        val beforeTrace = count(
            "SELECT COUNT(*) FROM core_trace_events WHERE task_id = ?",
            arrayOf("atomic-task")
        )
        assertTrue(beforeTrace > 0L)

        assertThrows(Exception::class.java) {
            store.writableDatabase.execSQL(
                "UPDATE core_trace_events SET action_code = 'policy.changed' WHERE task_id = ?",
                arrayOf("atomic-task")
            )
        }
        assertThrows(Exception::class.java) {
            store.writableDatabase.delete("core_trace_events", "task_id = ?", arrayOf("atomic-task"))
        }

        store.writableDatabase.execSQL("""
            CREATE TRIGGER trace_test_inject_task_started_failure
            BEFORE INSERT ON core_trace_events
            WHEN NEW.event_type = 'TASK_STARTED'
            BEGIN
                SELECT RAISE(ABORT, 'INJECTED_TRACE_FAILURE');
            END
        """.trimIndent())
        val failure = runCatching {
            store.transition(
                "atomic-task",
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.RUNNING,
                2_000L,
                null,
                null
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("atomic-task")?.state)
        assertEquals(beforeHistory, count(
            "SELECT COUNT(*) FROM core_task_history WHERE task_id = ?",
            arrayOf("atomic-task")
        ))
        assertEquals(beforeTrace, count(
            "SELECT COUNT(*) FROM core_trace_events WHERE task_id = ?",
            arrayOf("atomic-task")
        ))

        store.writableDatabase.execSQL("DROP TRIGGER trace_test_inject_task_started_failure")
        assertTrue(store.transition(
            "atomic-task",
            setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.RUNNING,
            3_000L,
            null,
            null
        ))
        assertTrue(query(
            taskId = "atomic-task",
            eventTypes = setOf(GlafiraCoreTraceEventType.TASK_STARTED),
            taskIds = setOf("atomic-task")
        ).events.isNotEmpty())
    }

    @Test
    fun schemaUpgradeFromVersionNineBackfillsExistingTaskHistory() {
        store.enqueue(task("legacy-task"))
        store.claimNext(2_000L)
        val db = store.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS core_trace_from_task_history")
        db.execSQL("DROP TRIGGER IF EXISTS core_trace_events_no_update")
        db.execSQL("DROP TABLE core_trace_events")
        db.version = 9
        store.close()

        store = GlafiraTaskStore(context)
        trace = GlafiraCoreTraceSqliteRepository(store)
        assertEquals(10, store.readableDatabase.version)
        val events = query(
            taskId = "legacy-task",
            eventTypes = setOf(
                GlafiraCoreTraceEventType.TASK_QUEUED,
                GlafiraCoreTraceEventType.TASK_STARTED
            ),
            taskIds = setOf("legacy-task")
        ).events
        assertEquals(2, events.size)
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
        private const val PROJECT_ID = "trace-project"
    }
}
