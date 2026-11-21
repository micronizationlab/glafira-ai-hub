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
