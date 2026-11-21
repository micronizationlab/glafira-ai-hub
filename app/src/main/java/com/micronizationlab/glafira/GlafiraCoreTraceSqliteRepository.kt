package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.Cursor
import org.json.JSONArray

/** SQLite-backed append-only Core Trace repository sharing the Core task database. */
class GlafiraCoreTraceSqliteRepository(
    private val taskStore: GlafiraTaskStore
) : GlafiraCoreTraceRepository {

    override fun append(event: GlafiraCoreTraceDraft): String = synchronized(taskStore) {
        val safe = GlafiraCoreTraceRedactor.redact(event)
        val db = taskStore.writableDatabase

        safe.taskId?.let { taskId ->
            db.query(
                "core_tasks",
                arrayOf("project_id"),
                "task_id = ?",
                arrayOf(taskId),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst() && cursor.getString(0) != safe.projectId) {
                    throw SecurityException("TRACE_TASK_PROJECT_MISMATCH")
                }
            }
        }

        safe.parentEventId?.let { parentId ->
            db.query(
                TABLE,
                arrayOf("project_id"),
                "event_id = ?",
                arrayOf(parentId),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != safe.projectId) {
                    throw SecurityException("TRACE_PARENT_EVENT_NOT_IN_PROJECT")
                }
            }
        }

        val values = ContentValues().apply {
            put("event_id", safe.eventId)
            put("project_id", safe.projectId)
            putNullable("task_id", safe.taskId)
            putNullable("parent_event_id", safe.parentEventId)
            put("correlation_id", safe.correlationId)
            put("occurred_at_ms", safe.occurredAtMillis)
            put("actor_type", safe.actorType.name)
            put("actor_id", safe.actorId)
            put("component", safe.component)
            put("event_type", safe.eventType.name)
            put("action_code", safe.actionCode)
            putNullable("target_type", safe.targetType)
            putNullable("target_id", safe.targetId)
            putNullable("result_status", safe.resultStatus)
            putNullable("error_code", safe.errorCode)
            putNullable("approval_ref", safe.approvalRef)
            put("evidence_refs", JSONArray().apply {
                safe.evidenceRefs.forEach { put(it) }
            }.toString())
            put("data_classification", safe.dataClassification.name)
            put("redaction_applied", 1)
            put("secret_values_stored", 0)
            putNullable("duration_ms", safe.durationMs)
        }
        db.insertOrThrow(TABLE, null, values)
        safe.eventId
    }

    override fun query(
        query: GlafiraCoreTraceQuery,
        access: GlafiraCoreTraceAccessContext
    ): GlafiraCoreTracePage = synchronized(taskStore) {
        if (query.projectId != access.projectId) {
            throw SecurityException("TRACE_PROJECT_ACCESS_DENIED")
        }
        if (query.taskId != null && access.taskIds != null && query.taskId !in access.taskIds) {
            return@synchronized GlafiraCoreTracePage(emptyList())
        }

        val db = taskStore.readableDatabase
        val where = mutableListOf("project_id = ?")
        val args = mutableListOf(query.projectId)

        query.taskId?.let {
            where += "task_id = ?"
            args += it
        }

        access.taskIds?.let { allowedTaskIds ->
            val scope = mutableListOf<String>()
            val orderedIds = allowedTaskIds.sorted()
            if (orderedIds.isNotEmpty()) {
                scope += "task_id IN (${orderedIds.joinToString(",") { "?" }})"
                args += orderedIds
            }
            if (access.allowProjectEvents) scope += "task_id IS NULL"
            if (scope.isEmpty()) return@synchronized GlafiraCoreTracePage(emptyList())
            where += "(${scope.joinToString(" OR ")})"
        }

        if (query.eventTypes.isNotEmpty()) {
            val types = query.eventTypes.map { it.name }.sorted()
            where += "event_type IN (${types.joinToString(",") { "?" }})"
            args += types
        }
        query.fromMillis?.let {
            where += "occurred_at_ms >= ?"
            args += it.toString()
        }
        query.toMillis?.let {
            where += "occurred_at_ms <= ?"
            args += it.toString()
        }
        query.afterEventId?.let { eventId ->
            val sequence = db.query(
                TABLE,
                arrayOf("sequence_id"),
                "project_id = ? AND event_id = ?",
                arrayOf(query.projectId, eventId),
                null,
                null,
                null,
                "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: return@synchronized GlafiraCoreTracePage(emptyList())
            where += "sequence_id > ?"
            args += sequence.toString()
        }

        val fetched = db.query(
            TABLE,
            EVENT_COLUMNS,
            where.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "sequence_id ASC",
            (query.limit + 1).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTraceEvent())
            }
        }
        val hasMore = fetched.size > query.limit
        val visible = fetched.take(query.limit).filter(access::canRead)
        GlafiraCoreTracePage(
            events = visible,
            nextCursor = if (hasMore) visible.lastOrNull()?.eventId else null
        )
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.toTraceEvent(): GlafiraCoreTraceEvent {
        val evidence = JSONArray(getString(getColumnIndexOrThrow("evidence_refs")))
        val evidenceRefs = buildList {
            for (index in 0 until evidence.length()) add(evidence.getString(index))
        }
        return GlafiraCoreTraceEvent(
            eventId = getString(getColumnIndexOrThrow("event_id")),
            projectId = getString(getColumnIndexOrThrow("project_id")),
            taskId = getNullableString("task_id"),
            parentEventId = getNullableString("parent_event_id"),
            correlationId = getString(getColumnIndexOrThrow("correlation_id")),
            occurredAtMillis = getLong(getColumnIndexOrThrow("occurred_at_ms")),
            actorType = GlafiraCoreTraceActorType.valueOf(getString(getColumnIndexOrThrow("actor_type"))),
            actorId = getString(getColumnIndexOrThrow("actor_id")),
            component = getString(getColumnIndexOrThrow("component")),
            eventType = GlafiraCoreTraceEventType.valueOf(getString(getColumnIndexOrThrow("event_type"))),
            actionCode = getString(getColumnIndexOrThrow("action_code")),
            targetType = getNullableString("target_type"),
            targetId = getNullableString("target_id"),
            resultStatus = getNullableString("result_status"),
            errorCode = getNullableString("error_code"),
            approvalRef = getNullableString("approval_ref"),
            evidenceRefs = evidenceRefs,
            dataClassification = GlafiraCoreTraceDataClassification.valueOf(
                getString(getColumnIndexOrThrow("data_classification"))
            ),
            redactionApplied = getInt(getColumnIndexOrThrow("redaction_applied")) == 1,
            secretValuesStored = getInt(getColumnIndexOrThrow("secret_values_stored")) != 0,
            durationMs = getNullableLong("duration_ms")
        )
    }

    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getNullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        private const val TABLE = "core_trace_events"
        private val EVENT_COLUMNS = arrayOf(
            "event_id", "project_id", "task_id", "parent_event_id", "correlation_id",
            "occurred_at_ms", "actor_type", "actor_id", "component", "event_type",
            "action_code", "target_type", "target_id", "result_status", "error_code",
            "approval_ref", "evidence_refs", "data_classification", "redaction_applied",
            "secret_values_stored", "duration_ms"
        )
    }
}
