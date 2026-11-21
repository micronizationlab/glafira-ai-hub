package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * Append-only, project-scoped persistence for metadata-only Core trace events.
 *
 * The store shares the Core task database so future task-transition producers can append
 * trace events in the same SQLite transaction. It intentionally exposes no update/delete API.
 */
class GlafiraCoreTraceStore(private val taskStore: GlafiraTaskStore) : GlafiraCoreTraceRepository {

    init {
        createSchema(taskStore.writableDatabase)
    }

    override fun append(event: GlafiraCoreTraceDraft): String = synchronized(taskStore) {
        val db = taskStore.writableDatabase
        db.beginTransaction()
        try {
            val eventId = appendInTransaction(db, event)
            db.setTransactionSuccessful()
            eventId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Appends inside a caller-owned Core transaction. Used by task lifecycle producers so the
     * state transition and its trace event can commit or roll back together.
     */
    internal fun appendInTransaction(db: SQLiteDatabase, event: GlafiraCoreTraceDraft): String {
        check(db.inTransaction()) { "TRACE_TRANSACTION_REQUIRED" }
        val safe = GlafiraCoreTraceRedactor.redact(event)
        readEventById(db, safe.eventId, safe.projectId)?.let { existing ->
            check(existing == safe) { "TRACE_EVENT_ID_CONFLICT" }
            return safe.eventId
        }

        safe.parentEventId?.let { parentEventId ->
            check(parentEventId != safe.eventId &&
                readEventById(db, parentEventId, safe.projectId) != null
            ) { "TRACE_PARENT_EVENT_NOT_FOUND" }
        }

        safe.taskId?.let { taskId ->
            val taskProject = db.query(
                "core_tasks",
                arrayOf("project_id"),
                "task_id = ?",
                arrayOf(taskId),
                null,
                null,
                null,
                "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            check(taskProject == safe.projectId) { "TRACE_TASK_PROJECT_MISMATCH" }
        }

        val inserted = db.insertWithOnConflict(
            TABLE,
            null,
            safe.toContentValues(),
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted == -1L) {
            val existing = readEventById(db, safe.eventId, safe.projectId)
            check(existing == safe) { "TRACE_EVENT_ID_CONFLICT" }
        }
        return safe.eventId
    }

    override fun query(
        query: GlafiraCoreTraceQuery,
        access: GlafiraCoreTraceAccessContext
    ): GlafiraCoreTracePage = synchronized(taskStore) {
        if (query.projectId != access.projectId) return@synchronized GlafiraCoreTracePage(emptyList())
        val allowedTaskIds = access.taskIds
        if (allowedTaskIds != null && allowedTaskIds.size > MAX_TASK_SCOPE_IDS) {
            throw IllegalArgumentException("TRACE_TASK_SCOPE_TOO_LARGE")
        }
        if (query.taskId != null && allowedTaskIds != null && query.taskId !in allowedTaskIds) {
            return@synchronized GlafiraCoreTracePage(emptyList())
        }
        if (query.taskId == null && allowedTaskIds != null &&
            allowedTaskIds.isEmpty() && !access.allowProjectEvents
        ) {
            return@synchronized GlafiraCoreTracePage(emptyList())
        }

        val db = taskStore.readableDatabase
        val cursorEvent = query.afterEventId?.let { eventId ->
            readEventById(db, eventId, query.projectId)
                ?.takeIf { access.canRead(it) && it.matches(query) }
                ?: return@synchronized GlafiraCoreTracePage(emptyList())
        }

        val clauses = mutableListOf("project_id = ?")
        val args = mutableListOf(query.projectId)

        if (query.taskId != null) {
            clauses += "task_id = ?"
            args += query.taskId
        } else if (allowedTaskIds != null) {
            if (allowedTaskIds.isEmpty()) {
                clauses += "task_id IS NULL"
            } else {
                val placeholders = allowedTaskIds.size.let { List(it) { "?" }.joinToString(",") }
                val taskClause = "task_id IN ($placeholders)"
                clauses += if (access.allowProjectEvents) "($taskClause OR task_id IS NULL)" else taskClause
                args += allowedTaskIds.sorted()
            }
        }

        if (query.eventTypes.isNotEmpty()) {
            val types = query.eventTypes.map { it.name }.sorted()
            clauses += "event_type IN (${types.joinToString(",") { "?" }})"
            args += types
        }
        query.fromMillis?.let { clauses += "occurred_at >= ?"; args += it.toString() }
        query.toMillis?.let { clauses += "occurred_at <= ?"; args += it.toString() }
        cursorEvent?.let {
            clauses += "(occurred_at < ? OR (occurred_at = ? AND event_id < ?))"
            args += it.occurredAtMillis.toString()
            args += it.occurredAtMillis.toString()
            args += it.eventId
        }

        val rows = db.query(
            TABLE,
            COLUMNS,
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "occurred_at DESC, event_id DESC",
            (query.limit + 1).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val event = cursor.toEvent()
                    if (access.canRead(event)) add(event)
                }
            }
        }

        val hasMore = rows.size > query.limit
        val page = rows.take(query.limit)
        GlafiraCoreTracePage(
            events = page,
            nextCursor = if (hasMore) page.lastOrNull()?.eventId else null
        )
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun GlafiraCoreTraceEvent.toContentValues() = ContentValues().apply {
        put("event_id", eventId)
        put("project_id", projectId)
        putNullable("task_id", taskId)
        putNullable("parent_event_id", parentEventId)
        put("correlation_id", correlationId)
        put("occurred_at", occurredAtMillis)
        put("actor_type", actorType.name)
        put("actor_id", actorId)
        put("component", component)
        put("event_type", eventType.name)
        put("action_code", actionCode)
        putNullable("target_type", targetType)
        putNullable("target_id", targetId)
        putNullable("result_status", resultStatus)
        putNullable("error_code", errorCode)
        putNullable("approval_ref", approvalRef)
        put("evidence_refs", JSONArray(evidenceRefs).toString())
        put("data_classification", dataClassification.name)
        put("redaction_applied", if (redactionApplied) 1 else 0)
        put("secret_values_stored", if (secretValuesStored) 1 else 0)
        if (durationMs == null) putNull("duration_ms") else put("duration_ms", durationMs)
    }

    private fun Cursor.toEvent(): GlafiraCoreTraceEvent {
        require(getInt(18) == 1 && getInt(19) == 0) { "TRACE_STORAGE_POLICY_VIOLATION" }
        val evidence = JSONArray(getString(16))
        val draft = GlafiraCoreTraceDraft(
            eventId = getString(0),
            projectId = getString(1),
            taskId = nullableString(2),
            parentEventId = nullableString(3),
            correlationId = getString(4),
            occurredAtMillis = getLong(5),
            actorType = enumValueOf(getString(6)),
            actorId = getString(7),
            component = getString(8),
            eventType = enumValueOf(getString(9)),
            actionCode = getString(10),
            targetType = nullableString(11),
            targetId = nullableString(12),
            resultStatus = nullableString(13),
            errorCode = nullableString(14),
            approvalRef = nullableString(15),
            evidenceRefs = buildList {
                for (index in 0 until evidence.length()) add(evidence.getString(index))
            },
            dataClassification = enumValueOf(getString(17)),
            durationMs = nullableLong(20)
        )
        return GlafiraCoreTraceRedactor.redact(draft)
    }

    private fun Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun readEventById(db: SQLiteDatabase, eventId: String, projectId: String): GlafiraCoreTraceEvent? =
        db.query(
            TABLE,
            COLUMNS,
            "event_id = ? AND project_id = ?",
            arrayOf(eventId, projectId),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toEvent() else null }

    private fun GlafiraCoreTraceEvent.matches(query: GlafiraCoreTraceQuery): Boolean =
        (query.taskId == null || taskId == query.taskId) &&
            (query.eventTypes.isEmpty() || eventType in query.eventTypes) &&
            (query.fromMillis == null || occurredAtMillis >= query.fromMillis) &&
            (query.toMillis == null || occurredAtMillis <= query.toMillis)

    companion object {
        const val TABLE = "core_trace_events"
        private const val MAX_TASK_SCOPE_IDS = 400
        private val COLUMNS = arrayOf(
            "event_id", "project_id", "task_id", "parent_event_id", "correlation_id",
            "occurred_at", "actor_type", "actor_id", "component", "event_type", "action_code",
            "target_type", "target_id", "result_status", "error_code", "approval_ref",
            "evidence_refs", "data_classification", "redaction_applied", "secret_values_stored",
            "duration_ms"
        )

        internal fun createSchema(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE (
                    event_id TEXT PRIMARY KEY NOT NULL,
                    project_id TEXT NOT NULL,
                    task_id TEXT,
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
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_core_trace_project_time ON $TABLE(project_id, occurred_at DESC, event_id DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_core_trace_task_time ON $TABLE(project_id, task_id, occurred_at DESC, event_id DESC)")
            createAppendOnlyTriggers(db)
            createCoreTaskLifecycleTriggers(db)
        }

        /**
         * The only deletion exception to Trace append-only storage: explicit owner project deletion.
         * The caller must hold Core maintenance and the surrounding SQLite transaction.
         */
        internal fun purgeProjectForOwnerDeletion(db: SQLiteDatabase, projectId: String) {
            check(db.inTransaction()) { "TRACE_PURGE_TRANSACTION_REQUIRED" }
            dropAppendOnlyTriggers(db)
            try {
                db.delete(TABLE, "project_id = ?", arrayOf(projectId))
            } finally {
                createAppendOnlyTriggers(db)
            }
        }

        internal fun dropAppendOnlyTriggers(db: SQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS core_trace_events_no_update")
            db.execSQL("DROP TRIGGER IF EXISTS core_trace_events_no_delete")
        }

        internal fun dropCoreTaskLifecycleTriggers(db: SQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS core_trace_task_created")
            db.execSQL("DROP TRIGGER IF EXISTS core_trace_task_state_changed")
        }

        internal fun createCoreTaskLifecycleTriggers(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS core_trace_task_created
                AFTER INSERT ON core_tasks
                BEGIN
                    INSERT INTO $TABLE (
                        event_id, project_id, task_id, parent_event_id, correlation_id, occurred_at,
                        actor_type, actor_id, component, event_type, action_code, target_type, target_id,
                        result_status, error_code, approval_ref, evidence_refs, data_classification,
                        redaction_applied, secret_values_stored, duration_ms
                    ) VALUES (
                        lower(hex(randomblob(16))), NEW.project_id, NEW.task_id, NULL, NEW.task_id,
                        NEW.created_at, 'CORE', 'core', 'CoreTaskStore', 'TASK_CREATED', 'TASK_CREATED',
                        NULL, NULL, NEW.state, NULL, NULL, '[]', 'INTERNAL', 1, 0, NULL
                    );
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS core_trace_task_state_changed
                AFTER UPDATE OF state ON core_tasks
                WHEN OLD.state != NEW.state
                BEGIN
                    INSERT INTO $TABLE (
                        event_id, project_id, task_id, parent_event_id, correlation_id, occurred_at,
                        actor_type, actor_id, component, event_type, action_code, target_type, target_id,
                        result_status, error_code, approval_ref, evidence_refs, data_classification,
                        redaction_applied, secret_values_stored, duration_ms
                    ) VALUES (
                        lower(hex(randomblob(16))), NEW.project_id, NEW.task_id, NULL, NEW.task_id,
                        NEW.updated_at, 'CORE', 'core', 'CoreTaskStore',
                        CASE NEW.state
                            WHEN 'queued' THEN 'TASK_QUEUED'
                            WHEN 'retry_scheduled' THEN 'TASK_QUEUED'
                            WHEN 'running' THEN 'TASK_STARTED'
                            WHEN 'waiting_for_owner' THEN 'TASK_PAUSED'
                            WHEN 'waiting_for_network' THEN 'TASK_PAUSED'
                            WHEN 'succeeded' THEN 'TASK_SUCCEEDED'
                            WHEN 'failed' THEN 'TASK_FAILED'
                            WHEN 'cancelled' THEN 'TASK_CANCELLED'
                            ELSE 'TASK_FAILED'
                        END,
                        'TASK_STATE_CHANGED', NULL, NULL, replace(NEW.state, '_', '-'), NULL, NULL, '[]', 'INTERNAL', 1, 0, NULL
                    );
                END
            """.trimIndent())
        }

        internal fun createAppendOnlyTriggers(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS core_trace_events_no_update
                BEFORE UPDATE ON $TABLE
                BEGIN SELECT RAISE(ABORT, 'TRACE_APPEND_ONLY'); END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS core_trace_events_no_delete
                BEFORE DELETE ON $TABLE
                BEGIN SELECT RAISE(ABORT, 'TRACE_APPEND_ONLY'); END
            """.trimIndent())
        }
    }
}
