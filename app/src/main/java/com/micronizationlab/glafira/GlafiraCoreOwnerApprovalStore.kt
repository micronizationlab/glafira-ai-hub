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
        val addedOwnerCoreId = ensureColumn(database, "owner_core_id", "TEXT")
        val addedOwnerGeneration = ensureColumn(database, "owner_generation", "INTEGER")
        if (addedOwnerCoreId || addedOwnerGeneration) {
            // Legacy approvals cannot be safely attributed to a canonical owner generation.
            // Fail closed instead of inheriting old grants after this migration.
            database.execSQL(
                "UPDATE core_action_approvals SET status = ?, approved_at = NULL, grant_expires_at = NULL " +
                    "WHERE owner_core_id IS NULL OR owner_generation IS NULL",
                arrayOf(EXPIRED)
            )
        }
        database.execSQL("CREATE INDEX IF NOT EXISTS core_action_approval_owner_idx ON core_action_approvals(task_id, owner_core_id, owner_generation, status)")
        database.execSQL("CREATE INDEX IF NOT EXISTS core_action_approval_status_idx ON core_action_approvals(status, requested_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS core_action_approval_expiry_idx ON core_action_approvals(status, expires_at)")
    }

    private fun ensureColumn(database: SQLiteDatabase, column: String, type: String): Boolean {
        val exists = database.rawQuery("PRAGMA table_info(core_action_approvals)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }
        if (exists) return false
        database.execSQL("ALTER TABLE core_action_approvals ADD COLUMN $column $type")
        return true
    }

    private fun ownershipMatches(task: GlafiraTask, ownerCoreId: String?, ownerGeneration: Long?): Boolean {
        if (ownerCoreId.isNullOrBlank() || ownerGeneration == null || ownerGeneration <= 0L) return false
        val current = taskStore.taskOwnership(task.taskId, task.projectId) ?: return false
        return current.ownerCoreId == ownerCoreId && current.ownerGeneration == ownerGeneration
    }

    private fun <T> inDatabaseTransaction(block: (SQLiteDatabase) -> T): T {
        val database = db
        database.beginTransaction()
        try {
            val result = block(database)
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
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

    /**
     * Invalidates approvals issued under the previous task owner generation.
     * Called inside the ownership-transfer SQLite transaction.
     */
    internal fun invalidateForOwnershipTransfer(
        db: SQLiteDatabase,
        taskId: String,
        oldOwnerCoreId: String,
        oldOwnerGeneration: Long
    ): Int = db.update(
        "core_action_approvals",
        ContentValues().apply {
            put("status", "EXPIRED")
            putNull("approved_at")
            putNull("grant_expires_at")
            put("decided_by", "ownership-transfer")
        },
        "task_id = ? AND owner_core_id = ? AND owner_generation = ? AND status IN ('PENDING','APPROVED')",
        arrayOf(taskId, oldOwnerCoreId, oldOwnerGeneration.toString())
    )

    @Synchronized
    override fun grantedScopes(task: GlafiraTask): Set<String> {
        val summary = runCatching {
            org.json.JSONObject(task.payload).optString("actionSummary").trim()
        }.getOrDefault("")
        if (summary.isBlank()) return emptySet()
        val hash = GlafiraToolRegistry.actionHash(task, summary)
        return inDatabaseTransaction { database ->
            database.query(
                "core_action_approvals", arrayOf("requested_scopes", "owner_core_id", "owner_generation"),
                "task_id = ? AND project_id = ? AND tool_id = ? AND action_hash = ? AND status = ? AND grant_expires_at > ?",
                arrayOf(task.taskId, task.projectId, task.taskType, hash, APPROVED, System.currentTimeMillis().toString()),
                null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) emptySet()
                else {
                    val scopesJson = cursor.getString(0)
                    val ownerCoreId = cursor.getString(1)
                    val ownerGeneration = cursor.getLong(2)
                    if (!ownershipMatches(task, ownerCoreId, ownerGeneration) ||
                        !hasExactRegisteredScopes(task, scopesJson)
                    ) emptySet() else parseRequestedScopes(scopesJson).orEmpty()
                }
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
        return inDatabaseTransaction { database ->
            database.query(
                "core_action_approvals",
                arrayOf("project_id", "tool_id", "action_hash", "status", "approved_at", "grant_expires_at", "requested_scopes", "owner_core_id", "owner_generation"),
                "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val row = ApprovalRow(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getLong(5), cursor.getString(6),
                    cursor.getString(7), cursor.getLong(8)
                )
                if (row.status == APPROVED && ownershipMatches(task, row.ownerCoreId, row.ownerGeneration) &&
                    row.projectId == task.projectId &&
                    row.toolId == task.taskType && row.actionHash == hash &&
                    hasExactRegisteredScopes(task, row.requestedScopesJson) &&
                    row.approvedAt <= nowMillis && row.grantExpiresAt > nowMillis
                ) ToolApprovalGrant(
                    row.projectId, row.toolId, row.actionHash,
                    row.approvedAt, row.grantExpiresAt, task.taskId
                ) else null
            }
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
            val ownership = taskStore.taskOwnership(task.taskId, task.projectId) ?: return false
            if (ownership.ownerCoreId.isBlank() || ownership.ownerGeneration <= 0L) return false
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
                put("owner_core_id", ownership.ownerCoreId)
                put("owner_generation", ownership.ownerGeneration)
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
                "task_id = ? AND state IN (?, ?) AND EXISTS (" +
                    "SELECT 1 FROM core_task_ownership o WHERE o.task_id = core_tasks.task_id " +
                    "AND o.project_id = ? AND o.owner_core_id = ? AND o.owner_generation = ?)",
                arrayOf(
                    task.taskId,
                    GlafiraTaskState.RUNNING.databaseValue,
                    GlafiraTaskState.WAITING_FOR_OWNER.databaseValue,
                    task.projectId, ownership.ownerCoreId, ownership.ownerGeneration.toString()
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
                arrayOf("project_id", "tool_id", "action_hash", "status", "approved_at", "grant_expires_at", "requested_scopes", "owner_core_id", "owner_generation"),
                "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else ApprovalRow(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getLong(5), cursor.getString(6),
                    cursor.getString(7), cursor.getLong(8)
                )
            }
            if (row != null && row.status == APPROVED &&
                ownershipMatches(task, row.ownerCoreId, row.ownerGeneration) &&
                row.projectId == task.projectId &&
                row.toolId == task.taskType && row.actionHash == hash &&
                hasExactRegisteredScopes(task, row.requestedScopesJson) &&
                row.approvedAt <= nowMillis && row.grantExpiresAt > nowMillis
            ) {
                val consumed = db.update(
                    "core_action_approvals",
                    ContentValues().apply { put("status", CONSUMED) },
                    "task_id = ? AND status = ? AND action_hash = ? AND owner_core_id = ? AND owner_generation = ?",
                    arrayOf(task.taskId, APPROVED, hash, row.ownerCoreId.orEmpty(), row.ownerGeneration.toString())
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
            } else if (row != null && row.status == APPROVED &&
                !ownershipMatches(task, row.ownerCoreId, row.ownerGeneration)
            ) {
                db.update(
                    "core_action_approvals",
                    ContentValues().apply {
                        put("status", EXPIRED)
                        putNull("approved_at")
                        putNull("grant_expires_at")
                    },
                    "task_id = ? AND status = ? AND owner_core_id = ? AND owner_generation = ?",
                    arrayOf(task.taskId, APPROVED, row.ownerCoreId.orEmpty(), row.ownerGeneration.toString())
                )
            } else if (row != null && row.status == APPROVED && row.grantExpiresAt <= nowMillis &&
                ownershipMatches(task, row.ownerCoreId, row.ownerGeneration) &&
                row.projectId == task.projectId && row.toolId == task.taskType && row.actionHash == hash
            ) {
                val expired = db.update(
                    "core_action_approvals",
                    ContentValues().apply { put("status", EXPIRED) },
                    "task_id = ? AND status = ? AND owner_core_id = ? AND owner_generation = ?",
                    arrayOf(task.taskId, APPROVED, row.ownerCoreId.orEmpty(), row.ownerGeneration.toString())
                )
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
        return inDatabaseTransaction { database ->
            // Capture only approvals that still belong to the task's current owner generation.
            // Stale approvals are invalidated below, but must not be attributed to a new owner.
            val expiredRows = database.rawQuery(
                "SELECT a.task_id, a.project_id FROM core_action_approvals a " +
                    "INNER JOIN core_task_ownership o ON o.task_id = a.task_id " +
                    "AND o.project_id = a.project_id AND o.owner_core_id = a.owner_core_id " +
                    "AND o.owner_generation = a.owner_generation " +
                    "WHERE a.status = ? AND a.expires_at <= ?",
                arrayOf(PENDING, nowMillis.toString())
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
            // An ownership transfer invalidates every approval from the previous generation.
            database.execSQL(
                "UPDATE core_action_approvals SET status = ?, approved_at = NULL, grant_expires_at = NULL " +
                    "WHERE status IN (?, ?) AND NOT EXISTS (" +
                    "SELECT 1 FROM core_task_ownership o WHERE o.task_id = core_action_approvals.task_id " +
                    "AND o.project_id = core_action_approvals.project_id " +
                    "AND o.owner_core_id = core_action_approvals.owner_core_id " +
                    "AND o.owner_generation = core_action_approvals.owner_generation)",
                arrayOf(EXPIRED, PENDING, APPROVED)
            )
            // Only expire the task when the expired approval still belongs to its
            // current canonical owner generation. A stale generation-N approval must
            // never fail a task that has since moved to generation N+1.
            database.execSQL(
                "UPDATE core_tasks SET state = ?, updated_at = ?, last_error = ? " +
                    "WHERE state = ? AND task_id IN (" +
                    "SELECT a.task_id FROM core_action_approvals a " +
                    "INNER JOIN core_task_ownership o ON o.task_id = a.task_id " +
                    "AND o.project_id = a.project_id " +
                    "AND o.owner_core_id = a.owner_core_id " +
                    "AND o.owner_generation = a.owner_generation " +
                    "WHERE a.status = ? AND a.expires_at <= ?)",
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
            val rows = database.query(
                "core_action_approvals",
                arrayOf("task_id", "action_hash", "requested_scopes", "requested_at", "expires_at", "owner_core_id", "owner_generation"),
                "status = ? AND expires_at > ?", arrayOf(PENDING, nowMillis.toString()),
                null, null, "requested_at ASC", limit.toString()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(PendingRow(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), cursor.getLong(4), cursor.getString(5), cursor.getLong(6)
                    ))
                }
            }
            rows.mapNotNull { row ->
                val task = taskStore.getTask(row.taskId) ?: return@mapNotNull null
                if (task.state != GlafiraTaskState.WAITING_FOR_OWNER ||
                    !ownershipMatches(task, row.ownerCoreId, row.ownerGeneration)
                ) return@mapNotNull null
                val json = JSONArray(row.scopesJson)
                val scopes = buildSet { for (i in 0 until json.length()) add(json.getString(i)) }
                GlafiraPendingActionApproval(task, row.actionHash, scopes, row.requestedAt, row.expiresAt)
            }
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
        // Resolve the task before opening the SQLite write transaction. Cancellation acquires
        // the task-store monitor before the same database writer; this lock order avoids inversion.
        val task = taskStore.getTask(taskId)
        var result = GlafiraCoreOwnerApprovalMutation.STORAGE_ERROR
        db.beginTransaction()
        try {
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
                    "core_action_approvals", arrayOf("project_id", "tool_id", "action_hash", "status", "expires_at", "owner_core_id", "owner_generation"),
                    "task_id = ?", arrayOf(taskId), null, null, null, "1"
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else DecisionRow(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getLong(4), cursor.getString(5), cursor.getLong(6)
                    )
                }
                if (row == null || definition == null || row.status != PENDING ||
                    !ownershipMatches(task, row.ownerCoreId, row.ownerGeneration) ||
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
                        }, "task_id = ? AND status = ? AND action_hash = ? AND owner_core_id = ? AND owner_generation = ?",
                            arrayOf(taskId, PENDING, hash, row.ownerCoreId.orEmpty(), row.ownerGeneration.toString()))
                        val requeued = db.update("core_tasks", ContentValues().apply {
                            put("state", GlafiraTaskState.QUEUED.databaseValue)
                            put("updated_at", nowMillis)
                            putNull("last_error")
                        }, "task_id = ? AND state = ? AND EXISTS (" +
                            "SELECT 1 FROM core_task_ownership o WHERE o.task_id = core_tasks.task_id " +
                            "AND o.project_id = ? AND o.owner_core_id = ? AND o.owner_generation = ?)",
                            arrayOf(taskId, GlafiraTaskState.WAITING_FOR_OWNER.databaseValue,
                                row.projectId, row.ownerCoreId.orEmpty(), row.ownerGeneration.toString()))
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
        // Read the task and ownership before opening the SQLite write transaction so all
        // approval mutations follow the task-store -> database lock order used by cancellation.
        val currentTask = taskStore.getTask(taskId)
        val ownership = currentTask?.let { taskStore.taskOwnership(taskId, it.projectId) }
        var result = GlafiraCoreOwnerApprovalMutation.NOT_FOUND
        db.beginTransaction()
        try {
            if (currentTask == null || currentTask.state != GlafiraTaskState.WAITING_FOR_OWNER ||
                ownership == null || ownership.ownerCoreId.isBlank() || ownership.ownerGeneration <= 0L
            ) {
                result = GlafiraCoreOwnerApprovalMutation.ALREADY_DECIDED
            } else {
            val changed = db.update("core_action_approvals", ContentValues().apply {
                put("status", REJECTED)
                put("decided_by", ownerSession.sessionId)
            }, "task_id = ? AND status = ? AND expires_at > ? AND owner_core_id = ? AND owner_generation = ?",
                arrayOf(taskId, PENDING, nowMillis.toString(), ownership.ownerCoreId, ownership.ownerGeneration.toString()))
            if (changed == 1) {
                val taskChanged = db.update("core_tasks", ContentValues().apply {
                    put("state", GlafiraTaskState.FAILED.databaseValue)
                    put("updated_at", nowMillis)
                    put("last_error", "OWNER_REJECTED")
                }, "task_id = ? AND state = ? AND EXISTS (" +
                    "SELECT 1 FROM core_task_ownership o WHERE o.task_id = core_tasks.task_id " +
                    "AND o.project_id = ? AND o.owner_core_id = ? AND o.owner_generation = ?)",
                    arrayOf(taskId, GlafiraTaskState.WAITING_FOR_OWNER.databaseValue,
                        currentTask.projectId, ownership.ownerCoreId, ownership.ownerGeneration.toString()))
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
        val requestedScopesJson: String,
        val ownerCoreId: String?,
        val ownerGeneration: Long
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
        val requestedAt: Long, val expiresAt: Long,
        val ownerCoreId: String?, val ownerGeneration: Long
    )
    private data class DecisionRow(
        val projectId: String, val toolId: String, val actionHash: String,
        val status: String, val expiresAt: Long,
        val ownerCoreId: String?, val ownerGeneration: Long
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
