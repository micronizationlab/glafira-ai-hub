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
        get() = taskStore.writableDatabase

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS core_owner_scope_grants (
                project_id TEXT NOT NULL,
                origin TEXT NOT NULL,
                scope TEXT NOT NULL,
                granted_at INTEGER NOT NULL,
                expires_at INTEGER,
                PRIMARY KEY(project_id, origin, scope)
            )""".trimIndent()
        )
        db.execSQL(
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
        db.execSQL("CREATE INDEX IF NOT EXISTS core_action_approval_status_idx ON core_action_approvals(status, requested_at)")
    }

    @Synchronized
    override fun grantedScopes(projectId: String, origin: String): Set<String> =
        db.query(
            "core_owner_scope_grants", arrayOf("scope"),
            "project_id = ? AND origin = ? AND (expires_at IS NULL OR expires_at > ?)",
            arrayOf(projectId, origin, System.currentTimeMillis().toString()),
            null, null, "scope"
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @Synchronized
    override fun requestApproval(task: GlafiraTask, actionSummary: String, nowMillis: Long): Boolean {
        if (actionSummary.isBlank() || nowMillis <= 0L) return false
        val definition = GlafiraToolRegistry.find(task.taskType) ?: return false
        val hash = GlafiraToolRegistry.actionHash(task, actionSummary)
        val requestedScopes = (definition.requiredScopes + task.requiredScopes).sorted()
        val existing = db.query(
            "core_action_approvals", arrayOf("action_hash", "status"),
            "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        }
        if (existing != null) {
            if (existing.first != hash || existing.second == REJECTED) return false
            if (existing.second == PENDING || existing.second == APPROVED) return true
        }
        val values = ContentValues().apply {
            put("task_id", task.taskId)
            put("project_id", task.projectId)
            put("tool_id", task.taskType)
            put("action_hash", hash)
            put("requested_scopes", JSONArray(requestedScopes).toString())
            put("requested_at", nowMillis)
            put("expires_at", nowMillis + REQUEST_TTL_MILLIS)
            put("status", PENDING)
            putNull("approved_at")
            putNull("grant_expires_at")
            putNull("decided_by")
        }
        return if (existing == null) {
            db.insertWithOnConflict("core_action_approvals", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        } else {
            db.update("core_action_approvals", values,
                "task_id = ? AND status IN (?, ?)",
                arrayOf(task.taskId, EXPIRED, CONSUMED)) == 1
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
        db.update("core_action_approvals", ContentValues().apply { put("status", EXPIRED) },
            "status = ? AND expires_at <= ?", arrayOf(PENDING, nowMillis.toString()))
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
        if (!ownerSession.isValid(nowMillis)) return GlafiraCoreOwnerApprovalMutation.OWNER_AUTHENTICATION_REQUIRED
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
                        selectedScopes.forEach { scope ->
                            db.insertWithOnConflict("core_owner_scope_grants", null, ContentValues().apply {
                                put("project_id", task.projectId)
                                put("origin", task.origin)
                                put("scope", scope)
                                put("granted_at", nowMillis)
                                putNull("expires_at")
                            }, SQLiteDatabase.CONFLICT_REPLACE)
                        }
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
            val changed = db.update("core_action_approvals", ContentValues().apply {
                put("status", REJECTED)
                put("decided_by", ownerSession.sessionId)
            }, "task_id = ? AND status = ? AND expires_at > ?",
                arrayOf(taskId, PENDING, nowMillis.toString()))
            if (changed == 1) {
                db.update("core_tasks", ContentValues().apply {
                    put("state", GlafiraTaskState.FAILED.databaseValue)
                    put("updated_at", nowMillis)
                    put("last_error", "OWNER_REJECTED")
                }, "task_id = ? AND state = ?",
                    arrayOf(taskId, GlafiraTaskState.WAITING_FOR_OWNER.databaseValue))
                result = GlafiraCoreOwnerApprovalMutation.REJECTED
                db.setTransactionSuccessful()
            }
        } finally {
            db.endTransaction()
        }
        return result
    }

    @Synchronized
    fun revokeScope(
        projectId: String,
        origin: String,
        scope: String,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!ownerSession.isValid(nowMillis)) return false
        return db.delete("core_owner_scope_grants",
            "project_id = ? AND origin = ? AND scope = ?",
            arrayOf(projectId, origin, scope)) == 1
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
        private const val REQUEST_TTL_MILLIS = 24L * 60L * 60L * 1000L
        private const val GRANT_TTL_MILLIS = 10L * 60L * 1000L
    }
}
