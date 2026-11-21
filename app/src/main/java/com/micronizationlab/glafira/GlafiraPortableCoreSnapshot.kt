package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

/** Logical data only: source-device ciphertext, leases and authority are never portable. */
internal class GlafiraPortableCoreSnapshot(
    private val store: GlafiraTaskStore,
    private val encrypt: (String, String) -> String = GlafiraTaskPayloadCrypto::encrypt,
    private val decrypt: (String, String) -> String = GlafiraTaskPayloadCrypto::decrypt
) {
    private data class Table(val name: String, val columns: List<String>, val encrypted: String? = null)

    fun exportTo(file: File) = synchronized(store) {
        val db = store.readableDatabase
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'core_%'", null).use { cursor ->
            while (cursor.moveToNext()) require(cursor.getString(0) in TABLES.map { it.name } + NON_PORTABLE) {
                "Новая таблица Core требует обновления переносимого формата."
            }
        }
        db.beginTransaction()
        try {
            val tables = JSONObject()
            TABLES.forEach { table ->
                require(exists(db, table.name)) { "Неполная схема Core: ${table.name}" }
                val rows = JSONArray()
                db.query(table.name, table.columns.toTypedArray(), null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        require(rows.length() < MAX_ROWS)
                        val row = JSONObject()
                        table.columns.forEachIndexed { index, column ->
                            row.put(column, when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else -> error("Неподдерживаемое поле Core.")
                            })
                        }
                        rows.put(row)
                    }
                }
                tables.put(table.name, rows)
            }
            val projects = taskProjects(tables)
            TABLES.forEach { table ->
                table.encrypted?.let { column ->
                    forRows(tables, table.name) { row ->
                        if (!row.isNull(column)) row.put(column, decrypt(row.getString(column), aad(table.name, row, projects)))
                    }
                }
            }
            val snapshot = JSONObject().put("format", FORMAT).put("version", VERSION).put("tables", tables)
            validate(snapshot)
            val bytes = snapshot.toString().toByteArray(Charsets.UTF_8)
            try {
                require(bytes.size <= MAX_BYTES)
                file.outputStream().use { it.write(bytes) }
            } finally { bytes.fill(0) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun readVerified(file: File): JSONObject {
        require(file.length() in 1..MAX_BYTES.toLong()) { "Недопустимый размер снимка Core." }
        val bytes = file.readBytes()
        return try { JSONObject(String(bytes, Charsets.UTF_8)).also(::validate) }
        finally { bytes.fill(0) }
    }

    fun checkRestoreSupported(snapshot: JSONObject) = validate(snapshot)

    fun restore(snapshot: JSONObject, now: Long = System.currentTimeMillis()) = synchronized(store) {
        validate(snapshot)
        val tables = snapshot.getJSONObject("tables")
        val projects = taskProjects(tables)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            store.setTaskHistoryTraceTriggerEnabled(db, enabled = false)
            store.setTraceMutationProtectionEnabled(db, enabled = false)
            try {
                NON_PORTABLE.filter { exists(db, it) }.forEach { db.delete(it, null, null) }
            TABLES.asReversed().forEach { db.delete(it.name, null, null) }
            TABLES.forEach { table ->
                // Task inserts create history baselines; the original history replaces them.
                if (table.name == HISTORY) db.delete(HISTORY, null, null)
                forRows(tables, table.name) { source ->
                    val row = JSONObject(source.toString())
                    if (table.name == TASKS && row.getString("state") !in TERMINAL) {
                        row.put("state", "waiting_for_owner").put("retry_at", JSONObject.NULL)
                            .put("last_error", "RESTORED_REQUIRES_OWNER_REVIEW")
                            .put("updated_at", maxOf(now, row.getLong("updated_at")))
                    }
                    if (table.name == PREVIEWS) {
                        row.put("review_status", "PENDING").put("reviewed_at", JSONObject.NULL)
                    }
                    val values = ContentValues()
                    table.columns.forEach { column ->
                        val value = row.get(column)
                        when {
                            value === JSONObject.NULL -> values.putNull(column)
                            column == table.encrypted -> values.put(column, encrypt(value as String, aad(table.name, row, projects)))
                            value is Number -> values.put(column, value.toLong())
                            else -> values.put(column, value as String)
                        }
                    }
                    db.insertOrThrow(table.name, null, values)
                }
            }
            forRows(tables, TASKS) { row ->
                db.insertOrThrow(HISTORY, null, ContentValues().apply {
                    put("task_id", row.getString("task_id")); put("project_id", row.getString("project_id"))
                    put("from_state", row.getString("state"))
                    put("to_state", if (row.getString("state") in TERMINAL) row.getString("state") else "waiting_for_owner")
                    put("changed_at", maxOf(now, row.getLong("updated_at"))); put("attempt_count", row.getLong("attempt_count"))
                    put("reason", "PORTABLE_BACKUP_RESTORED")
                })
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Нарушены связи снимка Core." } }
            val traceRepository = GlafiraCoreTraceSqliteRepository(store)
            val restoreTime = maxOf(1L, System.currentTimeMillis())
            taskProjects(tables).values.toSet().filter(GlafiraCoreTraceEvent::isSafeIdentifier).forEach { projectId ->
                val eventId = UUID.randomUUID().toString()
                traceRepository.append(GlafiraCoreTraceDraft(
                    eventId = eventId,
                    projectId = projectId,
                    correlationId = "restore-$eventId",
                    occurredAtMillis = restoreTime,
                    actorType = GlafiraCoreTraceActorType.CORE,
                    actorId = "core",
                    component = "PortableCoreSnapshot",
                    eventType = GlafiraCoreTraceEventType.RESTORE_SUCCEEDED,
                    actionCode = "core.restore.completed",
                    targetType = "PROJECT",
                    targetId = projectId,
                    resultStatus = "RESTORED",
                    dataClassification = GlafiraCoreTraceDataClassification.INTERNAL
                ))
            }
            } finally {
                store.setTaskHistoryTraceTriggerEnabled(db, enabled = true)
                store.setTraceMutationProtectionEnabled(db, enabled = true)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun validate(snapshot: JSONObject) {
        require(snapshot.getString("format") == FORMAT) { "Неподдерживаемый снимок Core." }
        val version = snapshot.getInt("version")
        require(version in LEGACY_VERSION..VERSION) { "Неподдерживаемая версия снимка Core." }
        val tables = snapshot.getJSONObject("tables")
        if (version == LEGACY_VERSION) migrateV2Snapshot(snapshot, tables)
        require(snapshot.getInt("version") == VERSION &&
            tables.keys().asSequence().toSet() == TABLES.map { it.name }.toSet()
        ) { "Неполный набор таблиц Core." }
        val projects = taskProjects(tables)
        val planProjects = mutableMapOf<String, String>()
        val idempotency = mutableSetOf<Pair<String, String>>()
        val traceSequences = mutableSetOf<Long>()
        TABLES.forEach { table ->
            val rows = tables.getJSONArray(table.name)
            require(rows.length() <= MAX_ROWS)
            val identities = mutableSetOf<String>()
            forRows(tables, table.name) { row ->
                require(row.keys().asSequence().toSet() == table.columns.toSet()) { "Несовместимая схема ${table.name}." }
                table.columns.forEach { column ->
                    val value = row.get(column)
                    when {
                        value === JSONObject.NULL -> require(
                            column in NULLABLE || (table.name == TRACE && column in TRACE_NULLABLE)
                        )
                        column == "priority" -> require((value is Int || value is Long) && (value as Number).toLong() in -1000L..1000L)
                        column in NUMBERS -> require((value is Int || value is Long) && (value as Number).toLong() >= 0)
                        else -> require(value is String && value.length <= MAX_FIELD_CHARS)
                    }
                }
                val identity = when (table.name) {
                    PLANS -> row.getString("plan_id")
                    NODES -> row.getString("plan_id") + ":" + row.getString("task_id")
                    DEPENDENCIES -> row.getString("task_id") + ":" + row.getString("depends_on_task_id")
                    HISTORY -> row.getLong("event_id").toString()
                    TRACE -> row.getString("event_id")
                    else -> row.getString("task_id")
                }
                require(identity.isNotBlank() && identities.add(identity)) { "Повторная запись Core." }
                if (table.name == TRACE) {
                    val sequence = row.getLong("sequence_id")
                    require(sequence > 0L && traceSequences.add(sequence))
                    val event = traceEventFromJson(row)
                    event.taskId?.let { taskId ->
                        projects[taskId]?.let { taskProject -> require(taskProject == event.projectId) }
                    }
                } else if (table.name == TASKS) {
                    val project = row.getString("project_id")
                    require(project.isNotBlank() && row.getString("task_type").isNotBlank())
                    require(idempotency.add(project to row.getString("idempotency_key")))
                    GlafiraTaskState.fromDatabaseValue(row.getString("state"))
                    val scopes = JSONArray(row.getString("required_scopes"))
                    require(scopes.length() <= 64)
                    for (i in 0 until scopes.length()) require(scopes.get(i) is String && scopes.getString(i).isNotBlank())
                    require(row.getLong("created_at") > 0 && row.getLong("updated_at") >= row.getLong("created_at"))
                } else if (table.name == PLANS) {
                    planProjects[row.getString("plan_id")] = row.getString("project_id")
                } else {
                    val project = projects[row.getString("task_id")] ?: error("Запись без исходной задачи.")
                    if (row.has("project_id")) require(project == row.getString("project_id"))
                    if (table.name == DEPENDENCIES) require(project == projects[row.getString("depends_on_task_id")])
                    if (table.name == NODES) require(project == planProjects[row.getString("plan_id")])
                }
            }
        }
    }

    private fun migrateV2Snapshot(snapshot: JSONObject, tables: JSONObject) {
        val legacyTables = TABLES.map { it.name }.filterNot { it == TRACE }.toSet()
        require(tables.keys().asSequence().toSet() == legacyTables) { "Неполный набор таблиц старого снимка Core." }
        val history = tables.getJSONArray(HISTORY)
        val trace = JSONArray()
        for (index in 0 until history.length()) {
            val row = history.getJSONObject(index)
            val historyId = row.getLong("event_id")
            val rawProjectId = row.getString("project_id")
            val rawTaskId = row.getString("task_id")
            val taskId = rawTaskId.takeIf(GlafiraCoreTraceEvent::isSafeIdentifier)
            val draft = GlafiraCoreTraceDraft(
                eventId = "task-history-$historyId",
                projectId = rawProjectId.takeIf(GlafiraCoreTraceEvent::isSafeIdentifier) ?: "redacted",
                taskId = taskId,
                correlationId = "task-history-$historyId",
                occurredAtMillis = maxOf(1L, row.getLong("changed_at")),
                actorType = GlafiraCoreTraceActorType.CORE,
                actorId = "core",
                component = "TaskStore",
                eventType = traceEventTypeForTaskHistory(
                    if (row.isNull("from_state")) null else row.getString("from_state"),
                    row.getString("to_state")
                ),
                actionCode = "task.state.changed",
                targetType = if (taskId == null) null else "TASK",
                targetId = taskId,
                resultStatus = row.getString("to_state").uppercase(),
                dataClassification = GlafiraCoreTraceDataClassification.INTERNAL
            )
            trace.put(traceEventToJson(GlafiraCoreTraceRedactor.redact(draft), index + 1L))
        }
        tables.put(TRACE, trace)
        snapshot.put("version", VERSION)
    }

    private fun traceEventToJson(event: GlafiraCoreTraceEvent, sequenceId: Long): JSONObject =
        JSONObject()
            .put("sequence_id", sequenceId)
            .put("event_id", event.eventId)
            .put("project_id", event.projectId)
            .put("task_id", event.taskId ?: JSONObject.NULL)
            .put("parent_event_id", event.parentEventId ?: JSONObject.NULL)
            .put("correlation_id", event.correlationId)
            .put("occurred_at_ms", event.occurredAtMillis)
            .put("actor_type", event.actorType.name)
            .put("actor_id", event.actorId)
            .put("component", event.component)
            .put("event_type", event.eventType.name)
            .put("action_code", event.actionCode)
            .put("target_type", event.targetType ?: JSONObject.NULL)
            .put("target_id", event.targetId ?: JSONObject.NULL)
            .put("result_status", event.resultStatus ?: JSONObject.NULL)
            .put("error_code", event.errorCode ?: JSONObject.NULL)
            .put("approval_ref", event.approvalRef ?: JSONObject.NULL)
            .put("evidence_refs", JSONArray(event.evidenceRefs).toString())
            .put("data_classification", event.dataClassification.name)
            .put("redaction_applied", if (event.redactionApplied) 1 else 0)
            .put("secret_values_stored", if (event.secretValuesStored) 1 else 0)
            .put("duration_ms", event.durationMs ?: JSONObject.NULL)

    private fun traceEventFromJson(row: JSONObject): GlafiraCoreTraceEvent {
        val evidence = JSONArray(row.getString("evidence_refs"))
        val evidenceRefs = buildList {
            for (index in 0 until evidence.length()) add(evidence.getString(index))
        }
        return GlafiraCoreTraceEvent(
            eventId = row.getString("event_id"),
            projectId = row.getString("project_id"),
            taskId = nullableString(row, "task_id"),
            parentEventId = nullableString(row, "parent_event_id"),
            correlationId = row.getString("correlation_id"),
            occurredAtMillis = row.getLong("occurred_at_ms"),
            actorType = GlafiraCoreTraceActorType.valueOf(row.getString("actor_type")),
            actorId = row.getString("actor_id"),
            component = row.getString("component"),
            eventType = GlafiraCoreTraceEventType.valueOf(row.getString("event_type")),
            actionCode = row.getString("action_code"),
            targetType = nullableString(row, "target_type"),
            targetId = nullableString(row, "target_id"),
            resultStatus = nullableString(row, "result_status"),
            errorCode = nullableString(row, "error_code"),
            approvalRef = nullableString(row, "approval_ref"),
            evidenceRefs = evidenceRefs,
            dataClassification = GlafiraCoreTraceDataClassification.valueOf(row.getString("data_classification")),
            redactionApplied = row.getLong("redaction_applied") == 1L,
            secretValuesStored = row.getLong("secret_values_stored") != 0L,
            durationMs = nullableLong(row, "duration_ms")
        )
    }

    private fun nullableString(row: JSONObject, key: String): String? =
        if (row.isNull(key)) null else row.getString(key)

    private fun nullableLong(row: JSONObject, key: String): Long? =
        if (row.isNull(key)) null else row.getLong(key)

    private fun taskProjects(tables: JSONObject): Map<String, String> {
        val result = mutableMapOf<String, String>()
        forRows(tables, TASKS) { row ->
            require(result.put(row.getString("task_id"), row.getString("project_id")) == null)
        }
        return result
    }

    private fun aad(table: String, row: JSONObject, projects: Map<String, String>): String {
        val id = row.getString("task_id")
        val project = projects.getValue(id)
        return when (table) {
            TASKS -> "$id|$project"
            PREVIEWS -> "$id|dry-run-preview|$project"
            "core_desktop_results" -> "$id|desktop-result"
            "core_task_results" -> "$id|core-result|$project"
            else -> error("Неизвестное шифрованное поле Core.")
        }
    }

    private fun forRows(tables: JSONObject, table: String, block: (JSONObject) -> Unit) {
        val rows = tables.getJSONArray(table)
        for (index in 0 until rows.length()) block(rows.getJSONObject(index))
    }

    private fun exists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)
    ).use { it.moveToFirst() }

    companion object {
        const val PATH = ".glafira-core-snapshot/core-portable-v2.json"
        private const val FORMAT = "GLAFIRA_PORTABLE_CORE"
        private const val LEGACY_VERSION = 2
        private const val VERSION = 3
        private const val MAX_BYTES = 128 * 1024 * 1024
        private const val MAX_ROWS = 100_000
        private const val MAX_FIELD_CHARS = 1024 * 1024
        private const val TASKS = "core_tasks"
        private const val PLANS = "core_task_plans"
        private const val NODES = "core_task_plan_nodes"
        private const val DEPENDENCIES = "core_task_dependencies"
        private const val PREVIEWS = "core_dry_run_previews"
        private const val HISTORY = "core_task_history"
        private const val TRACE = "core_trace_events"
        private val TERMINAL = setOf("succeeded", "failed", "cancelled")
        private val NON_PORTABLE = listOf(
            "core_action_approvals", "core_desktop_leases", "core_desktop_nodes",
            GlafiraCoreLiveRunStore.TABLE
        )
        private val NULLABLE = setOf("deadline_at", "retry_at", "last_error", "result_ciphertext", "error_code", "reviewed_at", "from_state", "reason")
        private val TRACE_NULLABLE = setOf("task_id", "parent_event_id", "target_type", "target_id", "result_status", "error_code", "approval_ref", "duration_ms")
        private val NUMBERS = setOf("event_id", "ordinal", "created_at", "updated_at", "attempt_count", "deadline_at", "retry_at", "completed_at", "reviewed_at", "changed_at", "sequence_id", "occurred_at_ms", "redaction_applied", "secret_values_stored", "duration_ms")
        private val TABLES = listOf(
            Table(TRACE, listOf("sequence_id", "event_id", "project_id", "task_id", "parent_event_id", "correlation_id", "occurred_at_ms", "actor_type", "actor_id", "component", "event_type", "action_code", "target_type", "target_id", "result_status", "error_code", "approval_ref", "evidence_refs", "data_classification", "redaction_applied", "secret_values_stored", "duration_ms")),
            Table(TASKS, listOf("task_id", "project_id", "task_type", "payload_ciphertext", "origin", "priority", "state", "created_at", "updated_at", "attempt_count", "deadline_at", "idempotency_key", "required_scopes", "retry_at", "last_error"), "payload_ciphertext"),
            Table(PLANS, listOf("plan_id", "project_id", "idempotency_key", "created_at", "updated_at")),
            Table(NODES, listOf("plan_id", "task_id", "ordinal")),
            Table(DEPENDENCIES, listOf("task_id", "depends_on_task_id", "created_at")),
            Table("core_desktop_results", listOf("task_id", "result_ciphertext", "error_code", "completed_at"), "result_ciphertext"),
            Table("core_task_results", listOf("task_id", "result_ciphertext", "completed_at"), "result_ciphertext"),
            Table(PREVIEWS, listOf("task_id", "project_id", "tool_id", "risk", "simulation_status", "plan_hash", "plan_ciphertext", "created_at", "review_status", "reviewed_at"), "plan_ciphertext"),
            Table(HISTORY, listOf("event_id", "task_id", "project_id", "from_state", "to_state", "changed_at", "attempt_count", "reason"))
        )
    }
}
