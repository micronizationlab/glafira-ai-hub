package com.micronizationlab.glafira

import android.content.ContentValues
import org.json.JSONObject

/** Commits a real result and the successful state as one storage operation. */
fun interface GlafiraTaskResultPersistence {
    fun complete(task: GlafiraTask, result: Map<String, String>, nowMillis: Long): Boolean
}

data class GlafiraStoredTaskResult(
    val taskId: String,
    val projectId: String,
    val resultId: String,
    val state: String,
    val values: Map<String, String>,
    val completedAtMillis: Long
)

/**
 * Immutable encrypted result journal in the task DB. A terminal receipt doubles as the
 * durable inbox deduplication record. Never prune a receipt while its operation can be retried.
 * Keystore ciphertext is device-local; portable backups must decrypt/re-encrypt this table.
 */
class GlafiraTaskResultStore(private val tasks: GlafiraTaskStore) : GlafiraTaskResultPersistence {
    init {
        synchronized(tasks) {
            tasks.writableDatabase.execSQL("""CREATE TABLE IF NOT EXISTS core_task_results (
                task_id TEXT PRIMARY KEY REFERENCES core_tasks(task_id) ON DELETE CASCADE,
                project_id TEXT NOT NULL, result_id TEXT NOT NULL UNIQUE,
                terminal_state TEXT NOT NULL, source_device_id TEXT,
                result_ciphertext TEXT NOT NULL, completed_at INTEGER NOT NULL
            )""".trimIndent())
            tasks.writableDatabase.execSQL("""CREATE TABLE IF NOT EXISTS core_result_acks (
                result_id TEXT NOT NULL REFERENCES core_task_results(result_id) ON DELETE CASCADE,
                device_id TEXT NOT NULL, acknowledged_at INTEGER NOT NULL,
                PRIMARY KEY(result_id, device_id)
            )""".trimIndent())
        }
    }

    override fun complete(task: GlafiraTask, result: Map<String, String>, nowMillis: Long): Boolean =
        synchronized(tasks) {
            val db = tasks.writableDatabase
            db.beginTransaction()
            try {
                val existing = get(task.taskId, task.projectId)
                if (existing != null) return@synchronized existing.state == "SUCCEEDED" && existing.values == result
                val changed = db.update("core_tasks", ContentValues().apply {
                    put("state", GlafiraTaskState.SUCCEEDED.databaseValue)
                    put("updated_at", nowMillis)
                    putNull("retry_at"); putNull("last_error")
                }, "task_id = ? AND project_id = ? AND state = ?", arrayOf(
                    task.taskId, task.projectId, GlafiraTaskState.RUNNING.databaseValue
                ))
                if (changed != 1) {
                    // Cancellation/owner intervention can win while a handler finishes. Keep its
                    // actual output without overriding that newer decision or executing it again.
                    val current = tasks.getTask(task.taskId)?.takeIf { it.projectId == task.projectId }
                        ?: return@synchronized false
                    insert(task.taskId, task.projectId, current.state.name, null, result, nowMillis)
                    db.setTransactionSuccessful()
                    return@synchronized false
                }
                insert(task.taskId, task.projectId, "SUCCEEDED", null, result, nowMillis)
                db.setTransactionSuccessful()
                true
            } finally { db.endTransaction() }
        }

    /** Fresh authenticated transport requests may redeliver the same terminal payload. */
    fun receiveDesktop(
        taskId: String, projectId: String, deviceId: String, state: String,
        progress: Int, message: String?, result: String?, errorCode: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = synchronized(tasks) {
        val task = tasks.getTask(taskId)?.takeIf { it.projectId == projectId } ?: return@synchronized false
        val terminal = state in setOf("SUCCEEDED", "FAILED", "CANCELLED")
        val values = buildMap {
            if (result != null) put("result", result)
            if (errorCode != null) put("errorCode", errorCode)
        }
        val db = tasks.writableDatabase
        db.beginTransaction()
        try {
            val existing = get(taskId, projectId)
            if (existing != null) {
                val source = db.rawQuery("SELECT source_device_id FROM core_task_results WHERE task_id = ?", arrayOf(taskId))
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                return@synchronized terminal && source == deviceId && existing.state == state && existing.values == values
            }
            if (!tasks.updateDesktopTask(task.taskId, deviceId, state, progress, message, result, errorCode, nowMillis)) {
                return@synchronized false
            }
            if (terminal) insert(taskId, projectId, state, deviceId, values, nowMillis)
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction() }
    }

    fun get(taskId: String, projectId: String): GlafiraStoredTaskResult? = synchronized(tasks) {
        tasks.readableDatabase.rawQuery("""SELECT result_id, terminal_state, result_ciphertext, completed_at
            FROM core_task_results WHERE task_id = ? AND project_id = ?""".trimIndent(), arrayOf(taskId, projectId)).use { cursor ->
            if (!cursor.moveToFirst()) return@synchronized null
            val json = JSONObject(GlafiraTaskPayloadCrypto.decrypt(cursor.getString(2), "$taskId|core-result|$projectId"))
            val values = buildMap { json.keys().forEach { key -> put(key, json.getString(key)) } }
            GlafiraStoredTaskResult(taskId, projectId, cursor.getString(0), cursor.getString(1), values, cursor.getLong(3))
        }
    }

    fun acknowledge(taskId: String, projectId: String, resultId: String, deviceId: String, nowMillis: Long): Boolean =
        synchronized(tasks) {
            val result = get(taskId, projectId) ?: return@synchronized false
            if (result.resultId != resultId) return@synchronized false
            tasks.writableDatabase.execSQL("INSERT OR IGNORE INTO core_result_acks VALUES (?, ?, ?)", arrayOf(resultId, deviceId, nowMillis))
            true
        }

    private fun insert(taskId: String, projectId: String, state: String, deviceId: String?, values: Map<String, String>, now: Long) {
        val json = JSONObject(values.toSortedMap()).toString()
        require(json.toByteArray(Charsets.UTF_8).size <= 256_000) { "CORE_RESULT_TOO_LARGE" }
        tasks.writableDatabase.insertOrThrow("core_task_results", null, ContentValues().apply {
            put("task_id", taskId); put("project_id", projectId)
            put("result_id", "result-$taskId"); put("terminal_state", state)
            if (deviceId == null) putNull("source_device_id") else put("source_device_id", deviceId)
            put("result_ciphertext", GlafiraTaskPayloadCrypto.encrypt(json, "$taskId|core-result|$projectId"))
            put("completed_at", now)
        })
    }
}
