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
class GlafiraCoreTraceSqliteRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore
    private lateinit var repository: GlafiraCoreTraceSqliteRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        store = GlafiraTaskStore(context)
        repository = GlafiraCoreTraceSqliteRepository(store)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    private fun task(id: String, project: String = "trace-project") = GlafiraTask(
        taskId = id,
        projectId = project,
        taskType = "project.read",
        payload = """{"record":"trace-test"}""",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "$project:$id"
    )

    private fun draft(
        id: String,
        at: Long,
        taskId: String? = null,
        projectId: String = "trace-project",
        type: GlafiraCoreTraceEventType = GlafiraCoreTraceEventType.TASK_STARTED
    ) = GlafiraCoreTraceDraft(
        eventId = id,
        projectId = projectId,
        taskId = taskId,
        correlationId = "trace-correlation",
        occurredAtMillis = at,
        actorType = GlafiraCoreTraceActorType.CORE,
        actorId = "core-main",
        component = "CoreTraceRepository",
        eventType = type,
        actionCode = "task.started"
    )

    @Test
    fun appendPersistsOnlyRedactedMetadataAndSurvivesDatabaseReopen() {
        store.enqueue(task("trace-task"))
        repository.append(draft("trace-event-1", 2_000L, taskId = "trace-task").copy(
            actorId = "owner@example.com",
            actionCode = "Bearer abcdefghijklmnopqrstuvwxyz123456",
            targetId = "https://example.com/private?token=secret"
        ))

        val beforeClose = repository.query(
            GlafiraCoreTraceQuery("trace-project"),
            GlafiraCoreTraceAccessContext("trace-project")
        ).events.single()
        assertEquals("redacted", beforeClose.actorId)
        assertEquals("redacted", beforeClose.actionCode)
        assertEquals("redacted", beforeClose.targetId)

        store.close()
        store = GlafiraTaskStore(context)
        repository = GlafiraCoreTraceSqliteRepository(store)
        val reopened = repository.query(
            GlafiraCoreTraceQuery("trace-project"),
            GlafiraCoreTraceAccessContext("trace-project")
        ).events.single()
        assertEquals("trace-event-1", reopened.eventId)
        assertTrue(reopened.redactionApplied)
        assertFalse(reopened.secretValuesStored)
    }

    @Test
    fun queryUsesBoundedCursorAndProjectTaskAccess() {
        store.enqueue(task("task-a"))
        store.enqueue(task("task-b"))
        repository.append(draft("event-a", 2_000L, "task-a"))
        repository.append(draft("event-b", 3_000L, "task-b"))
        repository.append(draft("event-project", 4_000L, type = GlafiraCoreTraceEventType.POLICY_CHECKED))

        val taskAccess = GlafiraCoreTraceAccessContext("trace-project", setOf("task-a"))
        val first = repository.query(
            GlafiraCoreTraceQuery("trace-project", limit = 1),
            taskAccess
        )
        assertEquals(listOf("event-a"), first.events.map { it.eventId })
        assertNull(first.nextCursor)

        val projectAccess = GlafiraCoreTraceAccessContext(
            "trace-project", setOf("task-a"), allowProjectEvents = true
        )
        val page = repository.query(
            GlafiraCoreTraceQuery("trace-project", limit = 1),
            projectAccess
        )
        assertEquals(listOf("event-a"), page.events.map { it.eventId })
        assertEquals("event-a", page.nextCursor)
        val next = repository.query(
            GlafiraCoreTraceQuery("trace-project", afterEventId = page.nextCursor, limit = 10),
            projectAccess
        )
        assertEquals(listOf("event-project"), next.events.map { it.eventId })
    }

    @Test
    fun taskBoundEventCannotBeWrittenIntoAnotherProject() {
        store.enqueue(task("trace-task", "trace-project"))
        assertThrows(IllegalArgumentException::class.java) {
            repository.append(draft("wrong-project-event", 2_000L, "trace-task", "another-project"))
        }
        assertTrue(repository.query(
            GlafiraCoreTraceQuery("trace-project"),
            GlafiraCoreTraceAccessContext("trace-project")
        ).events.isEmpty())
    }

    @Test
    fun duplicateEventIdAndInjectedInsertFailureDoNotLeavePartialRows() {
        repository.append(draft("duplicate-event", 2_000L))
        assertThrows(Exception::class.java) { repository.append(draft("duplicate-event", 3_000L)) }

        store.writableDatabase.execSQL("""
            CREATE TRIGGER inject_trace_insert_failure
            BEFORE INSERT ON core_trace_events
            WHEN NEW.event_id = 'injected-failure'
            BEGIN
                SELECT RAISE(ABORT, 'injected-trace-insert-failure');
            END
        """.trimIndent())
        assertThrows(Exception::class.java) {
            repository.append(draft("injected-failure", 4_000L))
        }
        store.writableDatabase.execSQL("DROP TRIGGER inject_trace_insert_failure")

        val events = repository.query(
            GlafiraCoreTraceQuery("trace-project"),
            GlafiraCoreTraceAccessContext("trace-project")
        ).events
        assertEquals(listOf("duplicate-event"), events.map { it.eventId })
    }

    @Test
    fun projectEventsRequireExplicitPermissionAndTaskQueriesCannotWidenAccess() {
        repository.append(draft("project-only", 2_000L))
        val denied = repository.query(
            GlafiraCoreTraceQuery("trace-project"),
            GlafiraCoreTraceAccessContext("trace-project", setOf("task-a"))
        )
        assertTrue(denied.events.isEmpty())

        val allowed = repository.query(
            GlafiraCoreTraceQuery("trace-project"),
            GlafiraCoreTraceAccessContext("trace-project", setOf("task-a"), allowProjectEvents = true)
        )
        assertEquals(listOf("project-only"), allowed.events.map { it.eventId })

        assertThrows(IllegalArgumentException::class.java) {
            repository.query(
                GlafiraCoreTraceQuery("another-project"),
                GlafiraCoreTraceAccessContext("trace-project")
            )
        }
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
