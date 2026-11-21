package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * Append-only metadata trace persisted in the same SQLite database as Core task state.
 * Only redacted events can enter the table; task-bound events must reference a task in
 * the same project. Query access is constrained in SQL before rows are materialized.
 */
class GlafiraCoreTraceSqliteRepository(
    private val taskStore: GlafiraTaskStore
) : GlafiraCoreTraceRepository {

    init {
        createSchema(taskStore.writableDatabase)
    }

    @Synchronized
    override fun append(event: GlafiraCoreTraceDraft): String {
        val safe = GlafiraCoreTraceRedactor.redact(event)
        val db = taskStore.writableDatabase
        db.beginTransaction()
        try {
            safe.taskId?.let { taskId ->
                db.rawQuery(
                    "SELECT 1 FROM core_tasks WHERE task_id = ? AND project_id = ? LIMIT 1",
                    arrayOf(taskId, safe.projectId)
                ).use { require(it.moveToFirst()) { "TRACE_TASK_PROJECT_MISMATCH" } }
            }
            db.insertOrThrow(TABLE, null, ContentValues().apply {
                put("event_id", safe.eventId)
                put("project_id", safe.projectId)
                put("task_id", safe.taskId)
                put("parent_event_id", safe.parentEventId)
                put("correlation_id", safe.correlationId)
                put("occurred_at", safe.occurredAtMillis)
                put("actor_type", safe.actorType.name)
                put("actor_id", safe.actorId)
                put("component", safe.component)
                put("event_type", safe.eventType.name)
                put("action_code", safe.actionCode)
                put("target_type", safe.targetType)
                put("target_id", safe.targetId)
                put("result_status", safe.resultStatus)
                put("error_code", safe.errorCode)
                put("approval_ref", safe.approvalRef)
                put("evidence_refs", JSONArray(safe.evidenceRefs).toString())
                put("data_classification", safe.dataClassification.name)
                put("redaction_applied", 1)
                put("secret_values_stored", 0)
                put("duration_ms", safe.durationMs)
            })
            db.setTransactionSuccessful()
            return safe.eventId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun query(
        query: GlafiraCoreTraceQuery,
        access: GlafiraCoreTraceAccessContext
    ): GlafiraCoreTracePage {
        require(query.projectId == access.projectId) { "TRACE_PROJECT_ACCESS_DENIED" }
        if (query.taskId != null && access.taskIds != null && query.taskId !in access.taskIds) {
            return GlafiraCoreTracePage(emptyList())
        }
        if (access.taskIds != null && access.taskIds.isEmpty() && !access.allowProjectEvents) {
            return GlafiraCoreTracePage(emptyList())
        }

        val where = mutableListOf("project_id = ?")
        val args = mutableListOf(query.projectId)
        query.taskId?.let { where += "task_id = ?"; args += it }
        query.fromMillis?.let { where += "occurred_at >= ?"; args += it.toString() }
        query.toMillis?.let { where += "occurred_at <= ?"; args += it.toString() }

        if (query.eventTypes.isNotEmpty()) {
            where += "event_type IN (${query.eventTypes.joinToString(",") { "?" }})"
            args += query.eventTypes.map { it.name }
        }
        if (query.afterEventId != null) {
            where += """(
                occurred_at > (SELECT occurred_at FROM $TABLE WHERE event_id = ? AND project_id = ?)
                OR (occurred_at = (SELECT occurred_at FROM $TABLE WHERE event_id = ? AND project_id = ?)
                    AND event_id > ?)
            )""".trimIndent()
            args += listOf(query.afterEventId, query.projectId, query.afterEventId, query.projectId, query.afterEventId)
        }

        if (access.taskIds != null) {
            val taskIds = access.taskIds.sorted()
            val taskClause = if (taskIds.isEmpty()) "0" else
                "task_id IN (${taskIds.joinToString(",") { "?" }})"
            if (access.allowProjectEvents) {
                where += "(task_id IS NULL OR $taskClause)"
            } else {
                where += taskClause
            }
            args += taskIds
        }

        val rows = mutableListOf<GlafiraCoreTraceEvent>()
        taskStore.readableDatabase.query(
            TABLE,
            COLUMNS,
            where.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "occurred_at ASC, event_id ASC",
            (query.limit + 1).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toEvent()
        }

        val hasMore = rows.size > query.limit
        val page = rows.take(query.limit)
        return GlafiraCoreTracePage(page, if (hasMore) page.lastOrNull()?.eventId else null)
    }

    private fun android.database.Cursor.toEvent(): GlafiraCoreTraceEvent {
        val evidence = JSONArray(getString(getColumnIndexOrThrow("evidence_refs")))
        val evidenceRefs = buildList {
            for (index in 0 until evidence.length()) add(evidence.getString(index))
        }
        return GlafiraCoreTraceRedactor.redact(GlafiraCoreTraceDraft(
            eventId = getString(getColumnIndexOrThrow("event_id")),
            projectId = getString(getColumnIndexOrThrow("project_id")),
            taskId = nullableString("task_id"),
            parentEventId = nullableString("parent_event_id"),
            correlationId = getString(getColumnIndexOrThrow("correlation_id")),
            occurredAtMillis = getLong(getColumnIndexOrThrow("occurred_at")),
            actorType = GlafiraCoreTraceActorType.valueOf(getString(getColumnIndexOrThrow("actor_type"))),
            actorId = getString(getColumnIndexOrThrow("actor_id")),
            component = getString(getColumnIndexOrThrow("component")),
            eventType = GlafiraCoreTraceEventType.valueOf(getString(getColumnIndexOrThrow("event_type"))),
            actionCode = getString(getColumnIndexOrThrow("action_code")),
            targetType = nullableString("target_type"),
            targetId = nullableString("target_id"),
            resultStatus = nullableString("result_status"),
            errorCode = nullableString("error_code"),
            approvalRef = nullableString("approval_ref"),
            evidenceRefs = evidenceRefs,
            dataClassification = GlafiraCoreTraceDataClassification.valueOf(
                getString(getColumnIndexOrThrow("data_classification"))
            ),
            durationMs = if (isNull(getColumnIndexOrThrow("duration_ms"))) null
                else getLong(getColumnIndexOrThrow("duration_ms"))
        ))
    }

    private fun android.database.Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        internal const val TABLE = "core_trace_events"
        private val COLUMNS = arrayOf(
            "event_id", "project_id", "task_id", "parent_event_id", "correlation_id",
            "occurred_at", "actor_type", "actor_id", "component", "event_type",
            "action_code", "target_type", "target_id", "result_status", "error_code",
            "approval_ref", "evidence_refs", "data_classification", "duration_ms"
        )

        internal fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS $TABLE (
                    event_id TEXT PRIMARY KEY NOT NULL,
                    project_id TEXT NOT NULL,
                    task_id TEXT REFERENCES core_tasks(task_id) ON DELETE CASCADE,
                    parent_event_id TEXT,
                    correlation_id TEXT NOT NULL,
                    occurred_at INTEGER NOT NULL CHECK(occurred_at > 0),
                    actor_type TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    component TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    action_code TEXT NOT NULL,
                    target_type TEXT,
                    target_id TEXT,
                    result_status TEXT,
                    error_code TEXT,
                    approval_ref TEXT,
                    evidence_refs TEXT NOT NULL,
                    data_classification TEXT NOT NULL,
                    redaction_applied INTEGER NOT NULL CHECK(redaction_applied = 1),
                    secret_values_stored INTEGER NOT NULL CHECK(secret_values_stored = 0),
                    duration_ms INTEGER CHECK(duration_ms IS NULL OR duration_ms >= 0)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_core_trace_project_time ON $TABLE(project_id, occurred_at, event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_core_trace_task_time ON $TABLE(project_id, task_id, occurred_at, event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_core_trace_correlation ON $TABLE(project_id, correlation_id, occurred_at)")
        }
    }
}
