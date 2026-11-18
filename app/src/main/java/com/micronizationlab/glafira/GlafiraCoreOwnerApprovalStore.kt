package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * Owner authorization records share the exact SQLite file used by the Core task queue.
 * No approval metadata or grants are kept in a parallel preferences store.
 */
class GlafiraCoreOwnerApprovalStore(
    private val taskStore: GlafiraTaskStore
) : GlafiraTaskGrantSource {

    private val db: SQLiteDatabase
        get() = taskStore.writableDatabase.also(::ensureSchema)

    init {
        ensureSchema(taskStore.writableDatabase)
    }

    private fun ensureSchema(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS core_action_approvals (
                task_id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                tool_id TEXT NOT NULL,
                action_hash TEXT NOT NULL,
                requested_scopes TEXT NOT NULL,
                requested_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','EXPIRED','CONSUMED')),
                approved_at INTEGER,
                grant_expires_at INTEGER,
                decided_by TEXT
            )""".trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS core_action_approval_status_idx ON core_action_approvals(status, requested_at)")
    }

    @Synchronized
    override fun grantedScopes(task: GlafiraTask): Set<String> {
        val summary = runCatching {
            org.json.JSONObject(task.payload).optString("actionSummary").trim()
        }.getOrDefault("")
        if (summary.isBlank()) return emptySet()
        val hash = GlafiraToolRegistry.actionHash(task, summary)
        return db.query(
            "core_action_approvals", arrayOf("requested_scopes"),
            "task_id = ? AND project_id = ? AND tool_id = ? AND action_hash = ? AND status = ? AND grant_expires_at > ?",
            arrayOf(task.taskId, task.projectId, task.taskType, hash, APPROVED, System.currentTimeMillis().toString()),
            null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) emptySet()
            else {
                val json = JSONArray(cursor.getString(0))
                buildSet { for (i in 0 until json.length()) add(json.getString(i)) }
            }
        }
    }

    @Synchronized
    override fun requestApproval(task: GlafiraTask, actionSummary: String, nowMillis: Long): Boolean {
        if (actionSummary.isBlank() || nowMillis <= 0L) return false
        val definition = GlafiraToolRegistry.find(task.taskType) ?: return false
        val hash = GlafiraToolRegistry.actionHash(task, actionSummary)
        val requestedScopes = (definition.requiredScopes + task.requiredScopes).sorted()
        val existing = db.query(
            "core_action_approvals", arrayOf("action_hash", "status", "grant_expires_at"),
            "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) Triple(
                cursor.getString(0), cursor.getString(1),
                if (cursor.isNull(2)) 0L else cursor.getLong(2)
            ) else null
        }
        if (existing != null) {
            if (existing.first != hash || existing.second == REJECTED) return false
            if (existing.second == PENDING) return true
            if (existing.second == APPROVED && existing.third > nowMillis) return true
        }
        val values = ContentValues().apply {
            put("task_id", task.taskId)
            put("project_id", task.projectId)
            put("tool_id", task.taskType)
            put("action_hash", hash)
            put("requested_scopes", JSONArray(requestedScopes).toString())
            put("requested_at", nowMillis)
            put("expires_at", Long.MAX_VALUE)
            put("status", PENDING)
            putNull("approved_at")
            putNull("grant_expires_at")
            putNull("decided_by")
        }
        return if (existing == null) {
            db.insertWithOnConflict("core_action_approvals", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        } else {
            db.update("core_action_approvals", values,
                "task_id = ? AND status IN (?, ?, ?)",
                arrayOf(task.taskId, EXPIRED, CONSUMED, APPROVED)) == 1
        }
    }

    @Synchronized
    override fun approvalGrant(task: GlafiraTask, actionSummary: String, nowMillis: Long): ToolApprovalGrant? {
        if (actionSummary.isBlank()) return null
        val hash = GlafiraToolRegistry.actionHash(task, actionSummary)
        var grant: ToolApprovalGrant? = null
        db.beginTransaction()
        try {
            val row = db.query(
                "core_action_approvals",
                arrayOf("project_id", "tool_id", "action_hash", "status", "approved_at", "grant_expires_at"),
                "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else ApprovalRow(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getLong(5)
                )
            }
            if (row != null && row.status == APPROVED && row.projectId == task.projectId &&
                row.toolId == task.taskType && row.actionHash == hash &&
                row.approvedAt <= nowMillis && row.grantExpiresAt > nowMillis
            ) {
                val consumed = db.update(
                    "core_action_approvals",
                    ContentValues().apply { put("status", CONSUMED) },
                    "task_id = ? AND status = ? AND action_hash = ?",
                    arrayOf(task.taskId, APPROVED, hash)
                )
                if (consumed == 1) grant = ToolApprovalGrant(
                    row.projectId, row.toolId, row.actionHash,
                    row.approvedAt, row.grantExpiresAt, task.taskId
                )
            } else if (row != null && row.status == APPROVED && row.grantExpiresAt <= nowMillis) {
                db.update("core_action_approvals", ContentValues().apply { put("status", EXPIRED) },
                    "task_id = ? AND status = ?", arrayOf(task.taskId, APPROVED))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return grant
    }

    @Synchronized
    fun pendingApprovals(nowMillis: Long = System.currentTimeMillis(), limit: Int = 100): List<GlafiraPendingActionApproval> {
        require(limit in 1..100)
        val rows = db.query(
            "core_action_approvals",
            arrayOf("task_id", "action_hash", "requested_scopes", "requested_at", "expires_at"),
            "status = ?", arrayOf(PENDING), null, null, "requested_at ASC", limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PendingRow(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getLong(3), cursor.getLong(4)
                ))
            }
        }
        return rows.mapNotNull { row ->
            val task = taskStore.getTask(row.taskId) ?: return@mapNotNull null
            if (task.state != GlafiraTaskState.WAITING_FOR_OWNER) return@mapNotNull null
            val json = JSONArray(row.scopesJson)
            val scopes = buildSet { for (i in 0 until json.length()) add(json.getString(i)) }
            GlafiraPendingActionApproval(task, row.actionHash, scopes, row.requestedAt, row.expiresAt)
        }
    }

    @Synchronized
    fun approve(
        taskId: String,
        selectedScopes: Set<String>,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraCoreOwnerApprovalMutation {
        if (!ownerSession.isValid()) return GlafiraCoreOwnerApprovalMutation.OWNER_AUTHENTICATION_REQUIRED
        var result = GlafiraCoreOwnerApprovalMutation.STORAGE_ERROR
        db.beginTransaction()
        try {
            val task = taskStore.getTask(taskId)
            if (task == null) {
                result = GlafiraCoreOwnerApprovalMutation.NOT_FOUND
            } else if (task.state != GlafiraTaskState.WAITING_FOR_OWNER ||
                (task.deadlineAtMillis != null && task.deadlineAtMillis <= nowMillis)
            ) {
                result = GlafiraCoreOwnerApprovalMutation.ALREADY_DECIDED
            } else {
                val definition = GlafiraToolRegistry.find(task.taskType)
                val summary = runCatching { org.json.JSONObject(task.payload).optString("actionSummary").trim() }.getOrDefault("")
                val hash = if (summary.isBlank()) "" else GlafiraToolRegistry.actionHash(task, summary)
                val row = db.query(
                    "core_action_approvals", arrayOf("project_id", "tool_id", "action_hash", "status", "expires_at"),
                    "task_id = ?", arrayOf(taskId), null, null, null, "1"
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else DecisionRow(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getLong(4)
                    )
                }
                if (row == null || definition == null || row.status != PENDING ||
                    row.expiresAt <= nowMillis || row.projectId != task.projectId ||
                    row.toolId != task.taskType || row.actionHash != hash
                ) {
                    result = GlafiraCoreOwnerApprovalMutation.NOT_FOUND
                } else {
                    val required = definition.requiredScopes + task.requiredScopes
                    val validSelection = selectedScopes == required && selectedScopes.isNotEmpty() &&
                        selectedScopes.all { scope ->
                            scope.length <= 96 && scope.all { it.isLetterOrDigit() || it in "._-" }
                        }
                    if (!validSelection) {
                        result = GlafiraCoreOwnerApprovalMutation.INVALID_SCOPE_SELECTION
                    } else {
                        val approved = db.update("core_action_approvals", ContentValues().apply {
                            put("status", APPROVED)
                            put("approved_at", nowMillis)
                            put("grant_expires_at", nowMillis + GRANT_TTL_MILLIS)
                            put("decided_by", ownerSession.sessionId)
                        }, "task_id = ? AND status = ? AND action_hash = ?",
                            arrayOf(taskId, PENDING, hash))
                        val requeued = db.update("core_tasks", ContentValues().apply {
                            put("state", GlafiraTaskState.QUEUED.databaseValue)
                            put("updated_at", nowMillis)
                            putNull("last_error")
                        }, "task_id = ? AND state = ?",
                            arrayOf(taskId, GlafiraTaskState.WAITING_FOR_OWNER.databaseValue))
                        if (approved == 1 && requeued == 1) {
                            result = GlafiraCoreOwnerApprovalMutation.APPROVED
                            db.setTransactionSuccessful()
                        } else result = GlafiraCoreOwnerApprovalMutation.ALREADY_DECIDED
                    }
                }
            }
        } finally {
            db.endTransaction()
        }
        return result
    }

    @Synchronized
    fun reject(
        taskId: String,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraCoreOwnerApprovalMutation {
        if (!ownerSession.isValid(nowMillis)) return GlafiraCoreOwnerApprovalMutation.OWNER_AUTHENTICATION_REQUIRED
        var result = GlafiraCoreOwnerApprovalMutation.NOT_FOUND
        db.beginTransaction()
        try {
            val currentTask = taskStore.getTask(taskId)
            if (currentTask == null || currentTask.state != GlafiraTaskState.WAITING_FOR_OWNER) {
                result = GlafiraCoreOwnerApprovalMutation.ALREADY_DECIDED
            } else {
            val changed = db.update("core_action_approvals", ContentValues().apply {
                put("status", REJECTED)
                put("decided_by", ownerSession.sessionId)
            }, "task_id = ? AND status = ? AND expires_at > ?",
                arrayOf(taskId, PENDING, nowMillis.toString()))
            if (changed == 1) {
                val taskChanged = db.update("core_tasks", ContentValues().apply {
                    put("state", GlafiraTaskState.FAILED.databaseValue)
                    put("updated_at", nowMillis)
                    put("last_error", "OWNER_REJECTED")
                }, "task_id = ? AND state = ?",
                    arrayOf(taskId, GlafiraTaskState.WAITING_FOR_OWNER.databaseValue))
                if (taskChanged == 1) {
                    result = GlafiraCoreOwnerApprovalMutation.REJECTED
                    db.setTransactionSuccessful()
                } else {
                    result = GlafiraCoreOwnerApprovalMutation.ALREADY_DECIDED
                }
            }
            }
        } finally {
            db.endTransaction()
        }
        return result
    }


    private data class ApprovalRow(
        val projectId: String, val toolId: String, val actionHash: String,
        val status: String, val approvedAt: Long, val grantExpiresAt: Long
    )
    private data class PendingRow(
        val taskId: String, val actionHash: String, val scopesJson: String,
        val requestedAt: Long, val expiresAt: Long
    )
    private data class DecisionRow(
        val projectId: String, val toolId: String, val actionHash: String,
        val status: String, val expiresAt: Long
    )

    companion object {
        private const val PENDING = "PENDING"
        private const val APPROVED = "APPROVED"
        private const val REJECTED = "REJECTED"
        private const val EXPIRED = "EXPIRED"
        private const val CONSUMED = "CONSUMED"
        private const val GRANT_TTL_MILLIS = 10L * 60L * 1000L
    }
}
