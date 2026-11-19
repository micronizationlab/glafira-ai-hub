package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Logical, passphrase-archive-only representation. Hardware-bound ciphertext never leaves
 * the source device: each encrypted field is decrypted for export and re-encrypted on import.
 * Authorization, live leases, paired devices and approval grants are deliberately not portable.
 */
internal class GlafiraPortableCoreSnapshot(private val taskStore: GlafiraTaskStore) {
    private data class Table(val name: String, val columns: List<String>, val encrypted: String? = null)

    fun exportTo(file: File) = synchronized(taskStore) {
        val db = taskStore.readableDatabase
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'core_%'", null).use { cursor ->
            while (cursor.moveToNext()) require(cursor.getString(0) in TABLES.map { it.name } + NON_PORTABLE) {
                "Обнаружена новая таблица Core. Требуется обновить формат резервного копирования."
            }
        }
        db.beginTransaction()
        try {
            val tables = JSONObject()
            TABLES.forEach { table ->
                if (!exists(db, table.name)) return@forEach
                val rows = JSONArray()
                db.query(table.name, table.columns.toTypedArray(), null, null, null, null,
                    if (table.name == HISTORY) "event_id ASC" else "task_id ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        require(rows.length() < MAX_ROWS) { "Слишком много записей Core для резервной копии." }
                        val row = JSONObject()
                        table.columns.forEachIndexed { index, column ->
                            row.put(column, when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else -> error("Неподдерживаемый тип данных Core: ${table.name}.$column")
                            })
                        }
                        table.encrypted?.let { column ->
                            if (!row.isNull(column)) row.put(column,
                                GlafiraTaskPayloadCrypto.decrypt(row.getString(column), aad(table.name, row)))
                        }
                        rows.put(row)
                    }
                }
                tables.put(table.name, rows)
            }
            val snapshot = JSONObject().put("format", FORMAT).put("version", VERSION)
                .put("tables", tables)
            validate(snapshot)
            val bytes = snapshot.toString().toByteArray(Charsets.UTF_8)
            try {
                require(bytes.size <= MAX_BYTES) { "Переносимый снимок Core слишком большой." }
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

    fun checkRestoreSupported(snapshot: JSONObject) = synchronized(taskStore) {
        validate(snapshot)
        val tables = snapshot.getJSONObject("tables")
        TABLES.forEach { table ->
            require(!tables.has(table.name) || tables.getJSONArray(table.name).length() == 0 || exists(taskStore.readableDatabase, table.name)) {
                "Текущая версия приложения не поддерживает данные ${table.name}. Обновите приложение."
            }
        }
    }

    /** Replace the same durable queue in one SQL transaction, with no execution authority. */
    fun restore(snapshot: JSONObject, nowMillis: Long = System.currentTimeMillis()) = synchronized(taskStore) {
        checkRestoreSupported(snapshot)
        val db = taskStore.writableDatabase
        val tables = snapshot.getJSONObject("tables")
        db.beginTransaction()
        try {
            // Delete dependent records and every old authorization/lease, including tables
            // whose schema is owned by another component. No old grant survives replacement.
            NON_PORTABLE.filter { exists(db, it) }.forEach { db.delete(it, null, null) }
            TABLES.asReversed().filter { exists(db, it.name) }.forEach { db.delete(it.name, null, null) }
            TABLES.forEach { table ->
                if (!exists(db, table.name)) return@forEach
                if (table.name == HISTORY) db.delete(HISTORY, null, null) // discard insert-trigger baselines
                val rows = tables.optJSONArray(table.name) ?: JSONArray()
                for (index in 0 until rows.length()) {
                    val row = JSONObject(rows.getJSONObject(index).toString())
                    if (table.name == TASKS && row.getString("state") !in TERMINAL) {
                        row.put("state", GlafiraTaskState.WAITING_FOR_OWNER.databaseValue)
                        row.put("updated_at", maxOf(nowMillis, row.getLong("updated_at")))
                        row.put("retry_at", JSONObject.NULL)
                        row.put("last_error", "RESTORED_REQUIRES_OWNER_REVIEW")
                    }
                    if (table.name == PREVIEWS && row.getString("review_status") == "ACKNOWLEDGED") {
                        row.put("review_status", "PENDING").put("reviewed_at", JSONObject.NULL)
                    }
                    if (table.name == "core_task_results") row.put("source_device_id", JSONObject.NULL)
                    val values = ContentValues()
                    table.columns.forEach { column ->
                        val value = row.get(column)
                        when {
                            value === JSONObject.NULL -> values.putNull(column)
                            column == table.encrypted -> values.put(column,
                                GlafiraTaskPayloadCrypto.encrypt(value as String, aad(table.name, row)))
                            value is Number -> values.put(column, value.toLong())
                            else -> values.put(column, value as String)
                        }
                    }
                    db.insertOrThrow(table.name, null, values)
                }
            }
            if (exists(db, HISTORY)) {
                val tasks = tables.getJSONArray(TASKS)
                for (index in 0 until tasks.length()) {
                    val task = tasks.getJSONObject(index)
                    db.insertOrThrow(HISTORY, null, ContentValues().apply {
                        put("task_id", task.getString("task_id")); put("project_id", task.getString("project_id"))
                        put("from_state", task.getString("state"))
                        put("to_state", if (task.getString("state") in TERMINAL) task.getString("state") else "waiting_for_owner")
                        put("changed_at", maxOf(nowMillis, task.getLong("updated_at")))
                        put("attempt_count", task.getLong("attempt_count")); put("reason", "PORTABLE_BACKUP_RESTORED")
                    })
                }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Нарушена целостность связей Core." } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun validate(snapshot: JSONObject) {
        require(snapshot.getString("format") == FORMAT && snapshot.getInt("version") == VERSION) {
            "Неподдерживаемый переносимый снимок Core."
        }
        val tables = snapshot.getJSONObject("tables")
        require(tables.keys().asSequence().all { key -> TABLES.any { it.name == key } }) { "Неизвестная таблица снимка Core." }
        val tasks = tables.getJSONArray(TASKS)
        val projects = mutableMapOf<String, String>()
        val idempotency = mutableSetOf<Pair<String, String>>()
        val resultIds = mutableSetOf<String>()
        TABLES.forEach { table ->
            val rows = tables.optJSONArray(table.name) ?: JSONArray()
            require(rows.length() <= MAX_ROWS)
            val identities = mutableSetOf<String>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == table.columns.toSet()) { "Неполная схема ${table.name}." }
                table.columns.forEach { column ->
                    val value = row.get(column)
                    when {
                        value === JSONObject.NULL -> require(column in NULLABLE) { "Пустое поле ${table.name}.$column" }
                        column in NUMBERS -> require((value is Int || value is Long) && (value as Number).toLong() >= 0) {
                            "Некорректное целое поле ${table.name}.$column"
                        }
                        column == "priority" -> require((value is Int || value is Long) && (value as Number).toLong() in -1000L..1000L)
                        else -> require(value is String && value.length <= MAX_FIELD_CHARS) { "Некорректное поле ${table.name}.$column" }
                    }
                }
                val id = row.getString("task_id")
                require(id.isNotBlank())
                if (table.name != HISTORY) require(identities.add(id)) { "Повторная запись задачи $id" }
                if (table.name == TASKS) {
                    val project = row.getString("project_id")
                    require(project.isNotBlank() && row.getString("task_type").isNotBlank() && row.getString("origin").isNotBlank())
                    require(row.getString("idempotency_key").isNotBlank())
                    require(idempotency.add(project to row.getString("idempotency_key"))) { "Повторный ключ операции." }
                    projects[id] = project
                    GlafiraTaskState.fromDatabaseValue(row.getString("state"))
                    val scopes = JSONArray(row.getString("required_scopes"))
                    require(scopes.length() <= 64)
                    for (scopeIndex in 0 until scopes.length()) require(scopes.get(scopeIndex) is String && scopes.getString(scopeIndex).isNotBlank())
                    require(row.getLong("created_at") > 0 && row.getLong("updated_at") >= row.getLong("created_at"))
                    require(row.isNull("deadline_at") || row.getLong("deadline_at") >= row.getLong("created_at"))
                } else {
                    require(projects.containsKey(id)) { "Результат/история без исходной задачи." }
                    if (row.has("project_id")) require(projects[id] == row.getString("project_id")) { "Пересечение проектов в снимке Core." }
                }
                if (table.name == "core_task_results") {
                    require(row.getString("result_id").isNotBlank() && resultIds.add(row.getString("result_id")))
                    // The result is immutable, while an owner may have changed the task
                    // state concurrently. Preserve the actual output even after that race.
                    require(GlafiraTaskState.values().any {
                        row.getString("terminal_state") == it.name || row.getString("terminal_state") == it.databaseValue
                    }) { "Неизвестное состояние результата Core." }
                    val result = JSONObject(row.getString("result_ciphertext"))
                    require(result.keys().asSequence().all { result.get(it) is String }) { "Некорректный результат Core." }
                }
                if (table.name == HISTORY) {
                    if (!row.isNull("from_state")) GlafiraTaskState.fromDatabaseValue(row.getString("from_state"))
                    GlafiraTaskState.fromDatabaseValue(row.getString("to_state"))
                }
                if (table.name == PREVIEWS) {
                    require(row.getString("risk") in setOf("READ_ONLY", "DRAFT", "EXTERNAL_WRITE", "FINANCIAL", "DESTRUCTIVE"))
                    require(row.getString("simulation_status") in setOf("PREVIEW_ONLY", "NOT_SIMULATABLE"))
                    require(row.getString("review_status") in setOf("PENDING", "ACKNOWLEDGED", "REJECTED"))
                    require(row.getString("plan_hash").matches(Regex("[0-9a-f]{64}")))
                    JSONObject(row.getString("plan_ciphertext"))
                }
            }
        }
        require(tasks.length() == projects.size)
    }

    private fun aad(table: String, row: JSONObject): String = when (table) {
        TASKS -> "${row.getString("task_id")}|${row.getString("project_id")}"
        PREVIEWS -> "${row.getString("task_id")}|dry-run-preview|${row.getString("project_id")}"
        "core_desktop_results" -> "${row.getString("task_id")}|desktop-result"
        "core_task_results" -> "${row.getString("task_id")}|core-result|${row.getString("project_id")}"
        else -> error("Неизвестное зашифрованное поле $table")
    }

    private fun exists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)
    ).use { it.moveToFirst() }

    companion object {
        const val PATH = ".glafira-core-snapshot/core-portable-v1.json"
        private const val FORMAT = "GLAFIRA_PORTABLE_CORE"
        private const val VERSION = 1
        private const val MAX_BYTES = 128 * 1024 * 1024
        private const val MAX_ROWS = 100_000
        private const val MAX_FIELD_CHARS = 1024 * 1024
        private const val TASKS = "core_tasks"
        private const val PREVIEWS = "core_dry_run_previews"
        private const val HISTORY = "core_task_history"
        private val TERMINAL = setOf("succeeded", "failed", "cancelled")
        private val NON_PORTABLE = listOf("core_result_acks", "core_action_approvals", "core_desktop_leases", "core_desktop_nodes")
        private val NULLABLE = setOf("deadline_at", "retry_at", "last_error", "result_ciphertext", "error_code", "reviewed_at", "from_state", "reason", "source_device_id")
        private val NUMBERS = setOf("created_at", "updated_at", "attempt_count", "deadline_at", "retry_at", "completed_at", "reviewed_at", "changed_at")
        private val TABLES = listOf(
            Table(TASKS, listOf("task_id", "project_id", "task_type", "payload_ciphertext", "origin", "priority", "state", "created_at", "updated_at", "attempt_count", "deadline_at", "idempotency_key", "required_scopes", "retry_at", "last_error"), "payload_ciphertext"),
            Table("core_desktop_results", listOf("task_id", "result_ciphertext", "error_code", "completed_at"), "result_ciphertext"),
            Table("core_task_results", listOf("task_id", "project_id", "result_id", "terminal_state", "source_device_id", "result_ciphertext", "completed_at"), "result_ciphertext"),
            Table(PREVIEWS, listOf("task_id", "project_id", "tool_id", "risk", "simulation_status", "plan_hash", "plan_ciphertext", "created_at", "review_status", "reviewed_at"), "plan_ciphertext"),
            Table(HISTORY, listOf("task_id", "project_id", "from_state", "to_state", "changed_at", "attempt_count", "reason"))
        )
    }
}
