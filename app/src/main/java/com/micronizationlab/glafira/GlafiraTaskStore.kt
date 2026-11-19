package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Transactional, process-death-safe queue storage for GLAFIRA Core.
 * Payloads are encrypted before they enter SQLite; task metadata remains queryable.
 */
class GlafiraTaskStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION), GlafiraTaskQueue {
    private val appContext = context.applicationContext


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
        createDesktopTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDesktopTables(db)
        if (newVersion > 2) throw IllegalStateException("A reviewed GLAFIRA task-queue migration is required.")
    }

    private fun createDesktopTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_leases (task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE, device_id TEXT NOT NULL, lease_expires_at INTEGER NOT NULL, attempt_count INTEGER NOT NULL, progress_percent INTEGER NOT NULL DEFAULT 0, progress_message TEXT, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_results (task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE, result_ciphertext TEXT, error_code TEXT, completed_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_core_desktop_lease_device ON core_desktop_leases(device_id, lease_expires_at)")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Downgrading the GLAFIRA task database is not supported.")
    }

    @Synchronized
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

    @Synchronized
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

    @Synchronized
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
    @Synchronized
    override fun claimNext(nowMillis: Long): GlafiraTask? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val taskId = db.rawQuery(
                "SELECT task_id FROM $TABLE_TASKS " +
                    "WHERE state IN (?, ?) AND task_type != ? " +
                    "AND (retry_at IS NULL OR retry_at <= ?) " +
                    "AND (deadline_at IS NULL OR deadline_at >= ?) " +
                    "ORDER BY priority DESC, created_at ASC LIMIT 1",
                arrayOf(
                    GlafiraTaskState.QUEUED.databaseValue,
                    GlafiraTaskState.RETRY_SCHEDULED.databaseValue,
                    DESKTOP_TASK_TYPE,
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
    @Synchronized
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


    @Synchronized
    fun queuedDesktopTasks(limit: Int = 100): List<GlafiraTask> {
        require(limit in 1..500)
        return readableDatabase.query(TABLE_TASKS, null, "task_type = ? AND state = ?",
            arrayOf(DESKTOP_TASK_TYPE, GlafiraTaskState.QUEUED.databaseValue),
            null, null, "priority DESC, created_at ASC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTask()) }
        }
    }

    @Synchronized
    fun claimDesktopTask(taskId: String, deviceId: String, supportedCapabilities: Set<String>,
                         maxParallelTasks: Int, nowMillis: Long = System.currentTimeMillis()): GlafiraTask? {
        require(deviceId.matches(Regex("[A-Za-z0-9_.-]{8,128}")))
        require(supportedCapabilities.size <= 16 && supportedCapabilities.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
        require(maxParallelTasks in 1..8)
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Recover crashed Desktop workers: expired leases return to the same Core queue.
            db.execSQL(
                "UPDATE $TABLE_TASKS SET state = ?, updated_at = ?, last_error = ? " +
                    "WHERE task_type = ? AND state = ? AND task_id IN " +
                    "(SELECT task_id FROM core_desktop_leases WHERE lease_expires_at <= ?) AND attempt_count < 3",
                arrayOf(
                    GlafiraTaskState.QUEUED.databaseValue, nowMillis, "DESKTOP_LEASE_EXPIRED",
                    DESKTOP_TASK_TYPE, GlafiraTaskState.RUNNING.databaseValue, nowMillis
                )
            )
            db.execSQL(
                "UPDATE $TABLE_TASKS SET state = ?, updated_at = ?, last_error = ? " +
                    "WHERE task_type = ? AND state = ? AND task_id IN " +
                    "(SELECT task_id FROM core_desktop_leases WHERE lease_expires_at <= ?) AND attempt_count >= 3",
                arrayOf(
                    GlafiraTaskState.FAILED.databaseValue, nowMillis, "DESKTOP_DELIVERY_RETRIES_EXCEEDED",
                    DESKTOP_TASK_TYPE, GlafiraTaskState.RUNNING.databaseValue, nowMillis
                )
            )
            db.delete("core_desktop_leases", "lease_expires_at <= ?", arrayOf(nowMillis.toString()))
            val active = db.rawQuery("SELECT COUNT(*) FROM core_desktop_leases WHERE device_id = ? AND lease_expires_at > ?",
                arrayOf(deviceId, nowMillis.toString())).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            if (active >= maxParallelTasks) { db.setTransactionSuccessful(); return null }
            val task = queryTask(db, taskId) ?: run { db.setTransactionSuccessful(); return null }
            if (task.taskType != DESKTOP_TASK_TYPE || task.state != GlafiraTaskState.QUEUED) {
                db.setTransactionSuccessful(); return null
            }
            val required = runCatching {
                val a = JSONObject(task.payload).getJSONArray("requiredCapabilities")
                (0 until a.length()).map { a.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            if (!supportedCapabilities.containsAll(required)) { db.setTransactionSuccessful(); return null }
            val changed = db.update(TABLE_TASKS, ContentValues().apply {
                put("state", GlafiraTaskState.RUNNING.databaseValue)
                put("updated_at", nowMillis)
                put("attempt_count", task.attemptCount + 1)
            }, "task_id = ? AND state = ?", arrayOf(taskId, GlafiraTaskState.QUEUED.databaseValue))
            if (changed != 1) { db.setTransactionSuccessful(); return null }
            db.insertWithOnConflict("core_desktop_leases", null, ContentValues().apply {
                put("task_id", taskId); put("device_id", deviceId)
                put("lease_expires_at", nowMillis + DESKTOP_LEASE_MS)
                put("attempt_count", task.attemptCount + 1); put("progress_percent", 0); put("updated_at", nowMillis)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return queryTask(db, taskId)
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun releaseDesktopLease(taskId: String, deviceId: String): Boolean =
        writableDatabase.delete("core_desktop_leases", "task_id = ? AND device_id = ?",
            arrayOf(taskId, deviceId)) == 1

    @Synchronized
    fun updateDesktopTask(taskId: String, deviceId: String, state: String, progressPercent: Int,
                          progressMessage: String?, result: String?, errorCode: String?,
                          nowMillis: Long = System.currentTimeMillis()): Boolean {
        require(progressPercent in 0..100)
        require(progressMessage == null || progressMessage.length <= 500)
        require(result == null || result.length <= 24_000)
        require(errorCode == null || errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        require(state in setOf("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val lease = db.query("core_desktop_leases", arrayOf("device_id", "lease_expires_at"),
                "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            } ?: return false
            if (lease.first != deviceId || lease.second <= nowMillis) return false
            if (state in setOf("SUCCEEDED", "FAILED", "CANCELLED")) {
                val terminal = when (state) {
                    "SUCCEEDED" -> GlafiraTaskState.SUCCEEDED
                    "FAILED" -> GlafiraTaskState.FAILED
                    else -> GlafiraTaskState.CANCELLED
                }
                val changed = db.update(TABLE_TASKS, ContentValues().apply {
                    put("state", terminal.databaseValue); put("updated_at", nowMillis)
                    if (errorCode == null) putNull("last_error") else put("last_error", errorCode)
                }, "task_id = ? AND state = ?", arrayOf(taskId, GlafiraTaskState.RUNNING.databaseValue))
                if (changed != 1) return false
                db.insertWithOnConflict("core_desktop_results", null, ContentValues().apply {
                    put("task_id", taskId)
                    if (result == null) putNull("result_ciphertext")
                    else put("result_ciphertext", GlafiraTaskPayloadCrypto.encrypt(result, taskId + "|desktop-result"))
                    if (errorCode == null) putNull("error_code") else put("error_code", errorCode)
                    put("completed_at", nowMillis)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete("core_desktop_leases", "task_id = ?", arrayOf(taskId))
            } else {
                db.update("core_desktop_leases", ContentValues().apply {
                    put("lease_expires_at", nowMillis + DESKTOP_LEASE_MS)
                    put("progress_percent", progressPercent)
                    if (progressMessage != null) put("progress_message", progressMessage)
                    put("updated_at", nowMillis)
                }, "task_id = ? AND device_id = ?", arrayOf(taskId, deviceId))
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun desktopTaskResult(taskId: String): String? =
        readableDatabase.query("core_desktop_results", arrayOf("result_ciphertext"),
            "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null
            else GlafiraTaskPayloadCrypto.decrypt(cursor.getString(0), taskId + "|desktop-result")
        }

    @Synchronized
    fun desktopTaskLeaseExpiry(taskId: String): Long? =
        readableDatabase.query("core_desktop_leases", arrayOf("lease_expires_at"),
            "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
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


    /**
     * Checkpoints SQLite and exports the closed, consistent database file.
     * The caller must hold GlafiraCoreMaintenanceGate for the whole snapshot operation.
     */
    @Synchronized
    internal fun exportConsistentSnapshot(destination: File) {
        val db = writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (cursor.moveToFirst()) {
                check(cursor.getInt(0) == 0) { "SQLite WAL checkpoint is busy; Core snapshot aborted." }
                if (cursor.columnCount >= 3 && cursor.getInt(1) >= 0) {
                    check(cursor.getInt(2) >= cursor.getInt(1)) {
                        "SQLite WAL checkpoint did not include every committed frame."
                    }
                }
            }
        }
        close()

        val source = appContext.getDatabasePath(DATABASE_NAME)
        check(source.isFile) { "Core task database does not exist." }
        val walFile = File(source.path + "-wal")
        check(!walFile.exists() || walFile.length() == 0L) {
            "SQLite WAL still contains data after closing the queue; Core snapshot aborted."
        }
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
        validateDatabaseSnapshot(destination)
    }

    /**
     * Installs a previously verified database snapshot with a same-directory rollback file.
     * The caller must hold GlafiraCoreMaintenanceGate and keep Core dispatch paused.
     */
    @Synchronized
    internal fun restoreConsistentSnapshot(snapshot: File) {
        require(snapshot.isFile && snapshot.length() > 0L) { "Core database snapshot is missing." }
        validateDatabaseSnapshot(snapshot)
        close()

        val target = appContext.getDatabasePath(DATABASE_NAME)
        val parent = requireNotNull(target.parentFile)
        check(parent.exists() || parent.mkdirs()) { "Cannot prepare the Core database directory." }
        val replacement = File(parent, "$DATABASE_NAME.restore-new")
        val rollback = File(parent, "$DATABASE_NAME.restore-rollback")
        replacement.delete()
        rollback.delete()
        snapshot.copyTo(replacement, overwrite = true)
        validateDatabaseSnapshot(replacement)

        if (target.exists()) {
            check(target.renameTo(rollback)) { "Cannot preserve the current Core database for rollback." }
        }
        if (!replacement.renameTo(target)) {
            if (rollback.exists()) rollback.renameTo(target)
            throw IllegalStateException("Cannot activate the restored Core database.")
        }

        try {
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            validateDatabaseSnapshot(target)
            val reopened = readableDatabase
            reopened.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Restored Core database failed its integrity check."
                }
            }
            rollback.delete()
        } catch (error: Exception) {
            close()
            target.delete()
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            if (rollback.exists() && !rollback.renameTo(target)) {
                error.addSuppressed(IllegalStateException("Core database rollback file could not be restored."))
            }
            throw error
        } finally {
            replacement.delete()
        }
    }

    private fun validateDatabaseSnapshot(file: File) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Core database snapshot failed SQLite integrity_check."
                }
            }
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == DATABASE_VERSION) {
                    "Core database snapshot schema version is incompatible."
                }
            }
        } finally {
            database.close()
        }
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
        private const val DATABASE_VERSION = 2
        private const val DESKTOP_LEASE_MS = 10 * 60_000L
        const val DESKTOP_TASK_TYPE = "desktop.execute"
        private const val TABLE_TASKS = "core_tasks"
    }
}
