package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * Transactional, process-death-safe queue storage for GLAFIRA Core.
 * Payloads are encrypted before they enter SQLite; task metadata remains queryable.
 */
class GlafiraTaskStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION), GlafiraTaskQueue {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_TASKS (
                task_id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                task_type TEXT NOT NULL,
                payload_ciphertext TEXT NOT NULL,
                origin TEXT NOT NULL,
                priority INTEGER NOT NULL,
                state TEXT NOT NULL CHECK (state IN (
                    'queued', 'running', 'waiting_for_network', 'waiting_for_owner',
                    'retry_scheduled', 'succeeded', 'failed', 'cancelled'
                )),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                attempt_count INTEGER NOT NULL,
                deadline_at INTEGER,
                idempotency_key TEXT NOT NULL,
                required_scopes TEXT NOT NULL,
                retry_at INTEGER,
                last_error TEXT
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_glafira_tasks_project_idempotency " +
                "ON $TABLE_TASKS(project_id, idempotency_key)"
        )
        db.execSQL(
            "CREATE INDEX index_glafira_tasks_state_priority " +
                "ON $TABLE_TASKS(state, priority DESC, created_at ASC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "A reviewed GLAFIRA task-queue migration is required from version " +
                oldVersion + " to " + newVersion + "."
        )
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Downgrading the GLAFIRA task database is not supported.")
    }

    override fun enqueue(task: GlafiraTask): GlafiraTaskEnqueueResult {
        GlafiraTaskPolicy.validate(task)
        val values = task.toContentValues()
        val insertedId = writableDatabase.insertWithOnConflict(
            TABLE_TASKS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (insertedId != -1L) {
            return GlafiraTaskEnqueueResult(task, true)
        }

        val existing = findByIdempotency(task.projectId, task.idempotencyKey)
            ?: throw IllegalStateException("Task ID already exists with a different idempotency key.")
        return GlafiraTaskEnqueueResult(existing, false)
    }

    override fun getTask(taskId: String): GlafiraTask? =
        readableDatabase.query(
            TABLE_TASKS,
            null,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }

    fun listTasks(
        states: Set<GlafiraTaskState> = emptySet(),
        limit: Int = 100
    ): List<GlafiraTask> {
        require(limit in 1..500) { "Task list limit must be between 1 and 500." }
        val selection = if (states.isEmpty()) null else {
            "state IN (" + states.joinToString(",") { "?" } + ")"
        }
        val args = if (states.isEmpty()) null else states.map { it.databaseValue }.toTypedArray()
        return readableDatabase.query(
            TABLE_TASKS,
            null,
            selection,
            args,
            null,
            null,
            "priority DESC, created_at ASC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTask())
            }
        }
    }

    /**
     * Atomically claims one eligible task and increments its attempt count.
     * A worker crash after this point is recovered by a later Core recovery pass.
     */
    override fun claimNext(nowMillis: Long): GlafiraTask? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val taskId = db.rawQuery(
                "SELECT task_id FROM $TABLE_TASKS " +
                    "WHERE state IN (?, ?) " +
                    "AND (retry_at IS NULL OR retry_at <= ?) " +
                    "AND (deadline_at IS NULL OR deadline_at >= ?) " +
                    "ORDER BY priority DESC, created_at ASC LIMIT 1",
                arrayOf(
                    GlafiraTaskState.QUEUED.databaseValue,
                    GlafiraTaskState.RETRY_SCHEDULED.databaseValue,
                    nowMillis.toString(),
                    nowMillis.toString()
                )
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

            if (taskId == null) {
                db.setTransactionSuccessful()
                return null
            }

            val candidate = queryTask(db, taskId)
                ?: throw IllegalStateException("Claim candidate disappeared inside a transaction.")
            val values = ContentValues().apply {
                put("state", GlafiraTaskState.RUNNING.databaseValue)
                put("updated_at", nowMillis)
                put("attempt_count", candidate.attemptCount + 1)
                putNull("retry_at")
            }
            val changed = db.update(
                TABLE_TASKS,
                values,
                "task_id = ? AND state = ?",
                arrayOf(taskId, candidate.state.databaseValue)
            )
            check(changed == 1) { "Task claim lost its transactional state check." }
            db.setTransactionSuccessful()
            return queryTask(db, taskId)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Compare-and-set transition. Callers must supply the states they observed;
     * illegal state transitions are rejected before SQLite is modified.
     */
    override fun transition(
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        nextState: GlafiraTaskState,
        nowMillis: Long,
        retryAtMillis: Long?,
        lastError: String?
    ): Boolean {
        require(expectedStates.isNotEmpty()) { "At least one expected task state is required." }
        require(expectedStates.all { GlafiraTaskPolicy.canTransition(it, nextState) }) {
            "The requested GLAFIRA task state transition is not allowed."
        }
        require(
            (nextState == GlafiraTaskState.RETRY_SCHEDULED) == (retryAtMillis != null)
        ) { "Only retry_scheduled tasks may have a retry timestamp." }

        val values = ContentValues().apply {
            put("state", nextState.databaseValue)
            put("updated_at", nowMillis)
            if (retryAtMillis == null) putNull("retry_at") else put("retry_at", retryAtMillis)
            if (lastError.isNullOrBlank()) putNull("last_error") else put("last_error", lastError.take(2000))
        }
        val placeholders = expectedStates.joinToString(",") { "?" }
        val args = arrayOf(taskId) + expectedStates.map { it.databaseValue }.toTypedArray()
        return writableDatabase.update(
            TABLE_TASKS,
            values,
            "task_id = ? AND state IN ($placeholders)",
            args
        ) == 1
    }

    private fun findByIdempotency(projectId: String, idempotencyKey: String): GlafiraTask? =
        readableDatabase.query(
            TABLE_TASKS,
            null,
            "project_id = ? AND idempotency_key = ?",
            arrayOf(projectId, idempotencyKey),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }

    private fun queryTask(db: SQLiteDatabase, taskId: String): GlafiraTask? =
        db.query(
            TABLE_TASKS,
            null,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }

    private fun GlafiraTask.toContentValues(): ContentValues {
        val aad = taskId + "|" + projectId
        val scopes = JSONArray().apply {
            requiredScopes.sorted().forEach { put(it) }
        }.toString()
        return ContentValues().apply {
            put("task_id", taskId)
            put("project_id", projectId)
            put("task_type", taskType)
            put("payload_ciphertext", GlafiraTaskPayloadCrypto.encrypt(payload, aad))
            put("origin", origin)
            put("priority", priority)
            put("state", state.databaseValue)
            put("created_at", createdAtMillis)
            put("updated_at", updatedAtMillis)
            put("attempt_count", attemptCount)
            if (deadlineAtMillis == null) putNull("deadline_at") else put("deadline_at", deadlineAtMillis)
            put("idempotency_key", idempotencyKey)
            put("required_scopes", scopes)
            if (retryAtMillis == null) putNull("retry_at") else put("retry_at", retryAtMillis)
            if (lastError == null) putNull("last_error") else put("last_error", lastError)
        }
    }

    private fun Cursor.toTask(): GlafiraTask {
        val taskId = getString(getColumnIndexOrThrow("task_id"))
        val projectId = getString(getColumnIndexOrThrow("project_id"))
        val scopeJson = JSONArray(getString(getColumnIndexOrThrow("required_scopes")))
        val scopes = buildSet {
            for (index in 0 until scopeJson.length()) add(scopeJson.getString(index))
        }
        val deadlineIndex = getColumnIndexOrThrow("deadline_at")
        val retryIndex = getColumnIndexOrThrow("retry_at")
        val errorIndex = getColumnIndexOrThrow("last_error")
        return GlafiraTask(
            taskId = taskId,
            projectId = projectId,
            taskType = getString(getColumnIndexOrThrow("task_type")),
            payload = GlafiraTaskPayloadCrypto.decrypt(
                getString(getColumnIndexOrThrow("payload_ciphertext")),
                taskId + "|" + projectId
            ),
            origin = getString(getColumnIndexOrThrow("origin")),
            priority = getInt(getColumnIndexOrThrow("priority")),
            state = GlafiraTaskState.fromDatabaseValue(getString(getColumnIndexOrThrow("state"))),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")),
            attemptCount = getInt(getColumnIndexOrThrow("attempt_count")),
            deadlineAtMillis = if (isNull(deadlineIndex)) null else getLong(deadlineIndex),
            idempotencyKey = getString(getColumnIndexOrThrow("idempotency_key")),
            requiredScopes = scopes,
            retryAtMillis = if (isNull(retryIndex)) null else getLong(retryIndex),
            lastError = if (isNull(errorIndex)) null else getString(errorIndex)
        )
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_TASKS = "core_tasks"
    }
}
