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
    private val traceStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { GlafiraCoreTraceStore(taskStore) }

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
        database.execSQL("CREATE INDEX IF NOT EXISTS core_action_approval_expiry_idx ON core_action_approvals(status, expires_at)")
    }

    private fun appendApprovalTrace(
        database: SQLiteDatabase,
        task: GlafiraTask,
        eventType: GlafiraCoreTraceEventType,
        actionCode: String,
        nowMillis: Long,
        actorType: GlafiraCoreTraceActorType = GlafiraCoreTraceActorType.CORE,
        actorId: String = "core",
        resultStatus: String
    ) {
        traceStore.appendInTransaction(
            database,
            GlafiraCoreTraceDraft(
                projectId = task.projectId,
                taskId = task.taskId,
                correlationId = task.taskId,
                occurredAtMillis = nowMillis,
                actorType = actorType,
                actorId = actorId,
                component = "CoreOwnerApprovalStore",
                eventType = eventType,
                actionCode = actionCode,
                approvalRef = task.taskId,
                resultStatus = resultStatus
            )
        )
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
                val scopesJson = cursor.getString(0)
                if (!hasExactRegisteredScopes(task, scopesJson)) emptySet()
                else parseRequestedScopes(scopesJson).orEmpty()
            }
        }
    }

    @Synchronized
    override fun peekApprovalGrant(
        task: GlafiraTask,
        actionSummary: String,
        nowMillis: Long
    ): ToolApprovalGrant? {
        if (actionSummary.isBlank()) return null
        val hash = GlafiraToolRegistry.actionHash(task, actionSummary)
        return db.query(
            "core_action_approvals",
            arrayOf("project_id", "tool_id", "action_hash", "status", "approved_at", "grant_expires_at", "requested_scopes"),
            "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val row = ApprovalRow(
                cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getLong(4), cursor.getLong(5), cursor.getString(6)
            )
            if (row.status == APPROVED && row.projectId == task.projectId &&
                row.toolId == task.taskType && row.actionHash == hash &&
                hasExactRegisteredScopes(task, row.requestedScopesJson) &&
                row.approvedAt <= nowMillis && row.grantExpiresAt > nowMillis
            ) ToolApprovalGrant(
                row.projectId, row.toolId, row.actionHash,
                row.approvedAt, row.grantExpiresAt, task.taskId
            ) else null
        }
    }

    @Synchronized
    override fun requestApproval(task: GlafiraTask, actionSummary: String, nowMillis: Long): Boolean {
        if (actionSummary.isBlank() || nowMillis <= 0L) return false
        val definition = GlafiraToolRegistry.find(task.taskType) ?: return false
        val hash = GlafiraToolRegistry.actionHash(task, actionSummary)
        val requestedScopes = (definition.requiredScopes + task.requiredScopes).sorted()
        if (requestedScopes.isEmpty() || requestedScopes.any { !GlafiraToolRegistry.isKnownScope(it) }) return false
        val database = db
        var queued = false
        database.beginTransaction()
        try {
            val currentTask = taskStore.getTask(task.taskId) ?: return false
            if ((currentTask.state != GlafiraTaskState.RUNNING &&
                currentTask.state != GlafiraTaskState.WAITING_FOR_OWNER) ||
                (currentTask.deadlineAtMillis != null && currentTask.deadlineAtMillis <= nowMillis)
            ) return false

            val existing = database.query(
                "core_action_approvals", arrayOf("action_hash", "status"),
                "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
            }
            if (existing != null) {
                if (existing.first != hash || existing.second == REJECTED) return false
            }

            val values = ContentValues().apply {
                put("task_id", task.taskId)
                put("project_id", task.projectId)
                put("tool_id", task.taskType)
                put("action_hash", hash)
                put("requested_scopes", JSONArray(requestedScopes).toString())
                put("requested_at", nowMillis)
                put("expires_at", approvalExpiry(nowMillis, currentTask.deadlineAtMillis))
                put("status", PENDING)
                putNull("approved_at")
                putNull("grant_expires_at")
                putNull("decided_by")
            }
            val approvalChanged = if (existing == null) {
                database.insertWithOnConflict(
                    "core_action_approvals", null, values, SQLiteDatabase.CONFLICT_IGNORE
                ).let { if (it == -1L) 0 else 1 }
            } else {
                database.update(
                    "core_action_approvals", values,
                    "task_id = ? AND status IN (?, ?, ?, ?)",
                    arrayOf(task.taskId, EXPIRED, CONSUMED, APPROVED, PENDING)
                )
            }
            val taskChanged = database.update(
                "core_tasks",
                ContentValues().apply {
                    put("state", GlafiraTaskState.WAITING_FOR_OWNER.databaseValue)
                    put("updated_at", nowMillis)
                    put("last_error", "OWNER_APPROVAL_REQUIRED")
                },
                "task_id = ? AND state IN (?, ?)",
                arrayOf(
                    task.taskId,
                    GlafiraTaskState.RUNNING.databaseValue,
                    GlafiraTaskState.WAITING_FOR_OWNER.databaseValue
                )
            )
            queued = approvalChanged == 1 && taskChanged == 1
            if (queued) {
                appendApprovalTrace(
                    database, currentTask,
                    GlafiraCoreTraceEventType.APPROVAL_REQUESTED,
                    "OWNER_APPROVAL_REQUESTED", nowMillis,
                    resultStatus = "pending"
                )
                database.setTransactionSuccessful()
            }
        } finally {
            database.endTransaction()
        }
        return queued
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
                arrayOf("project_id", "tool_id", "action_hash", "status", "approved_at", "grant_expires_at", "requested_scopes"),
                "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else ApprovalRow(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getLong(5), cursor.getString(6)
                )
            }
            if (row != null && row.status == APPROVED && row.projectId == task.projectId &&
                row.toolId == task.taskType && row.actionHash == hash &&
                hasExactRegisteredScopes(task, row.requestedScopesJson) &&
                row.approvedAt <= nowMillis && row.grantExpiresAt > nowMillis
            ) {
                val consumed = db.update(
                    "core_action_approvals",
                    ContentValues().apply { put("status", CONSUMED) },
                    "task_id = ? AND status = ? AND action_hash = ?",
                    arrayOf(task.taskId, APPROVED, hash)
                )
                if (consumed == 1) {
                    appendApprovalTrace(
                        db, task,
                        GlafiraCoreTraceEventType.APPROVAL_CONSUMED,
                        "OWNER_APPROVAL_CONSUMED", nowMillis,
                        resultStatus = "consumed"
                    )
                    grant = ToolApprovalGrant(
                        row.projectId, row.toolId, row.actionHash,
                        row.approvedAt, row.grantExpiresAt, task.taskId
                    )
                }
            } else if (row != null && row.status == APPROVED && row.grantExpiresAt <= nowMillis) {
                val expired = db.update("core_action_approvals", ContentValues().apply { put("status", EXPIRED) },
                    "task_id = ? AND status = ?", arrayOf(task.taskId, APPROVED))
                if (expired == 1) {
                    appendApprovalTrace(
                        db, task,
                        GlafiraCoreTraceEventType.APPROVAL_EXPIRED,
                        "OWNER_APPROVAL_EXPIRED", nowMillis,
                        resultStatus = "expired"
                    )
                }
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
        val database = db
        database.beginTransaction()
        try {
            val expiredRows = database.query(
                "core_action_approvals",
                arrayOf("task_id", "project_id"),
                "status = ? AND expires_at <= ?",
                arrayOf(PENDING, nowMillis.toString()),
                null, null, null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
            database.update(
                "core_action_approvals",
                ContentValues().apply { put("status", EXPIRED) },
                "status = ? AND expires_at <= ?",
                arrayOf(PENDING, nowMillis.toString())
            )
            database.execSQL(
                "UPDATE core_tasks SET state = ?, updated_at = ?, last_error = ? " +
                    "WHERE state = ? AND task_id IN " +
                    "(SELECT task_id FROM core_action_approvals WHERE status = ? AND expires_at <= ?)",
                arrayOf(
                    GlafiraTaskState.FAILED.databaseValue, nowMillis, "OWNER_APPROVAL_EXPIRED",
                    GlafiraTaskState.WAITING_FOR_OWNER.databaseValue, EXPIRED, nowMillis
                )
            )
            expiredRows.forEach { (taskId, projectId) ->
                val task = taskStore.getTask(taskId)
                if (task != null && task.projectId == projectId) {
                    appendApprovalTrace(
                        database, task,
                        GlafiraCoreTraceEventType.APPROVAL_EXPIRED,
                        "OWNER_APPROVAL_EXPIRED", nowMillis,
                        resultStatus = "expired"
                    )
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        val rows = database.query(
            "core_action_approvals",
            arrayOf("task_id", "action_hash", "requested_scopes", "requested_at", "expires_at"),
            "status = ? AND expires_at > ?", arrayOf(PENDING, nowMillis.toString()),
            null, null, "requested_at ASC", limit.toString()
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
        if (!ownerSession.consume()) return GlafiraCoreOwnerApprovalMutation.OWNER_AUTHENTICATION_REQUIRED
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
                            GlafiraToolRegistry.isKnownScope(scope) &&
                                scope.length <= 96 && scope.all { it.isLetterOrDigit() || it in "._-" }
                        }
                    if (!validSelection) {
                        result = GlafiraCoreOwnerApprovalMutation.INVALID_SCOPE_SELECTION
                    } else {
                        val approved = db.update("core_action_approvals", ContentValues().apply {
                            put("status", APPROVED)
                            put("approved_at", nowMillis)
                            put("grant_expires_at", minOf(safeAdd(nowMillis, GRANT_TTL_MILLIS), task.deadlineAtMillis ?: Long.MAX_VALUE))
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
                            appendApprovalTrace(
                                db, task,
                                GlafiraCoreTraceEventType.APPROVAL_GRANTED,
                                "OWNER_APPROVAL_GRANTED", nowMillis,
                                actorType = GlafiraCoreTraceActorType.OWNER,
                                actorId = "owner",
                                resultStatus = "approved"
                            )
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
        if (!ownerSession.consume()) return GlafiraCoreOwnerApprovalMutation.OWNER_AUTHENTICATION_REQUIRED
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
                    appendApprovalTrace(
                        db, currentTask,
                        GlafiraCoreTraceEventType.APPROVAL_REJECTED,
                        "OWNER_APPROVAL_REJECTED", nowMillis,
                        actorType = GlafiraCoreTraceActorType.OWNER,
                        actorId = "owner",
                        resultStatus = "rejected"
                    )
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


    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private fun approvalExpiry(nowMillis: Long, taskDeadlineMillis: Long?): Long {
        val ttlExpiry = if (Long.MAX_VALUE - nowMillis < PENDING_APPROVAL_TTL_MILLIS) {
            Long.MAX_VALUE
        } else {
            nowMillis + PENDING_APPROVAL_TTL_MILLIS
        }
        return minOf(taskDeadlineMillis ?: ttlExpiry, ttlExpiry)
    }

    private data class ApprovalRow(
        val projectId: String, val toolId: String, val actionHash: String,
        val status: String, val approvedAt: Long, val grantExpiresAt: Long,
        val requestedScopesJson: String
    )

    private fun expectedRegisteredScopes(task: GlafiraTask): Set<String>? {
        val definition = GlafiraToolRegistry.find(task.taskType) ?: return null
        val required = definition.requiredScopes + task.requiredScopes
        return required.takeIf { it.isNotEmpty() && it.all(GlafiraToolRegistry::isKnownScope) }
    }

    private fun parseRequestedScopes(json: String): Set<String>? = runCatching {
        val array = JSONArray(json)
        buildSet {
            for (index in 0 until array.length()) {
                val scope = array.getString(index)
                require(scope.isNotBlank() && add(scope))
            }
        }
    }.getOrNull()

    private fun hasExactRegisteredScopes(task: GlafiraTask, json: String): Boolean {
        val expected = expectedRegisteredScopes(task) ?: return false
        val stored = parseRequestedScopes(json) ?: return false
        return stored == expected && stored.all(GlafiraToolRegistry::isKnownScope)
    }
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
        private const val PENDING_APPROVAL_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
