package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

fun interface GlafiraLiveTaskAdmission {
    fun allows(task: GlafiraTask, nowMillis: Long): Boolean

    companion object {
        val ALLOW_ALL = GlafiraLiveTaskAdmission { _, _ -> true }
        val DENY_ALL = GlafiraLiveTaskAdmission { _, _ -> false }
    }
}

enum class GlafiraLiveRunApprovalMutation {
    APPROVED,
    OWNER_AUTHENTICATION_REQUIRED,
    NOT_FOUND,
    PREVIEW_NOT_ACKNOWLEDGED,
    TOOL_NOT_ALLOWED,
    TASK_EXPIRED,
    ALREADY_DECIDED,
    STORAGE_ERROR
}

/**
 * Durable, fail-closed admission for the first narrow Core LIVE vertical.
 *
 * Only explicitly allow-listed read handlers can receive a grant. The grant is bound to the
 * immutable task contents and acknowledged Dry Run plan; restoring a backup deliberately drops
 * grants so LIVE always needs a new local owner authentication.
 */
class GlafiraCoreLiveRunStore(private val taskStore: GlafiraTaskStore) : GlafiraLiveTaskAdmission {
    private val db: SQLiteDatabase get() = taskStore.writableDatabase

    @Synchronized
    fun approveReadOnlyRun(
        taskId: String,
        expectedPlanHash: String,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraLiveRunApprovalMutation {
        require(expectedPlanHash.matches(Regex("[0-9a-f]{64}")))
        if (!ownerSession.consume()) {
            return GlafiraLiveRunApprovalMutation.OWNER_AUTHENTICATION_REQUIRED
        }
        var result = GlafiraLiveRunApprovalMutation.STORAGE_ERROR
        db.beginTransaction()
        try {
            val task = taskStore.getTask(taskId)
            val preview = taskStore.dryRunPreview(taskId)
            if (task == null || preview == null || preview.planHash != expectedPlanHash) {
                result = GlafiraLiveRunApprovalMutation.NOT_FOUND
            } else if (task.state != GlafiraTaskState.WAITING_FOR_OWNER) {
                result = GlafiraLiveRunApprovalMutation.ALREADY_DECIDED
            } else if (task.deadlineAtMillis != null && task.deadlineAtMillis <= nowMillis) {
                result = GlafiraLiveRunApprovalMutation.TASK_EXPIRED
            } else if (!isAllowedReadHandler(task.taskType)) {
                result = GlafiraLiveRunApprovalMutation.TOOL_NOT_ALLOWED
            } else if (preview.projectId != task.projectId || preview.toolId != task.taskType ||
                preview.risk != ToolRiskLevel.READ_ONLY.name ||
                preview.simulationStatus != GlafiraDryRunSimulationStatus.PREVIEW_ONLY.name ||
                preview.reviewStatus != GlafiraDryRunReviewStatus.ACKNOWLEDGED
            ) {
                result = GlafiraLiveRunApprovalMutation.PREVIEW_NOT_ACKNOWLEDGED
            } else {
                val actionSummary = actionSummary(task)
                if (actionSummary.isBlank()) {
                    result = GlafiraLiveRunApprovalMutation.PREVIEW_NOT_ACKNOWLEDGED
                } else {
                    val expiresAt = minOf(
                        task.deadlineAtMillis ?: Long.MAX_VALUE,
                        safeAdd(nowMillis, LIVE_GRANT_TTL_MILLIS)
                    )
                    if (expiresAt <= nowMillis) {
                        result = GlafiraLiveRunApprovalMutation.TASK_EXPIRED
                    } else {
                        val inserted = db.insertWithOnConflict(
                            TABLE,
                            null,
                            ContentValues().apply {
                                put("task_id", task.taskId)
                                put("project_id", task.projectId)
                                put("tool_id", task.taskType)
                                put("plan_hash", expectedPlanHash)
                                put("action_hash", GlafiraToolRegistry.actionHash(task, actionSummary))
                                put("approved_at", nowMillis)
                                put("expires_at", expiresAt)
                                put("approved_session_id", ownerSession.sessionId)
                            },
                            SQLiteDatabase.CONFLICT_REPLACE
                        )
                        val requeued = db.update(
                            "core_tasks",
                            ContentValues().apply {
                                put("state", GlafiraTaskState.QUEUED.databaseValue)
                                put("updated_at", nowMillis)
                                putNull("last_error")
                            },
                            "task_id = ? AND state = ?",
                            arrayOf(taskId, GlafiraTaskState.WAITING_FOR_OWNER.databaseValue)
                        )
                        if (inserted != -1L && requeued == 1) {
                            result = GlafiraLiveRunApprovalMutation.APPROVED
                            db.setTransactionSuccessful()
                        } else {
                            result = GlafiraLiveRunApprovalMutation.ALREADY_DECIDED
                        }
                    }
                }
            }
        } finally {
            db.endTransaction()
        }
        return result
    }

    @Synchronized
    override fun allows(task: GlafiraTask, nowMillis: Long): Boolean {
        if (task.deadlineAtMillis != null && task.deadlineAtMillis <= nowMillis) return false

        // These mutations may enter LIVE admission only because the executor immediately
        // runs RegistryBackedGlafiraTaskAuthorizer next. That authorizer requires the exact
        // owner-approved task hash and all registered scopes before invoking a handler.
        // Provider/model output is never an authorization source.
        if (task.taskType in OWNER_APPROVAL_GATED_MUTATIONS) return true

        if (!isAllowedReadHandler(task.taskType)) return false
        val actionSummary = actionSummary(task)
        if (actionSummary.isBlank()) return false
        val preview = taskStore.dryRunPreview(task.taskId) ?: return false
        if (preview.projectId != task.projectId || preview.toolId != task.taskType ||
            preview.reviewStatus != GlafiraDryRunReviewStatus.ACKNOWLEDGED ||
            preview.risk != ToolRiskLevel.READ_ONLY.name ||
            preview.simulationStatus != GlafiraDryRunSimulationStatus.PREVIEW_ONLY.name
        ) return false
        return db.query(
            TABLE,
            arrayOf("project_id", "tool_id", "plan_hash", "action_hash", "approved_at", "expires_at"),
            "task_id = ?", arrayOf(task.taskId), null, null, null, "1"
        ).use { cursor ->
            cursor.moveToFirst() &&
                cursor.getString(0) == task.projectId &&
                cursor.getString(1) == task.taskType &&
                cursor.getString(2) == preview.planHash &&
                cursor.getString(3) == GlafiraToolRegistry.actionHash(task, actionSummary) &&
                cursor.getLong(4) <= nowMillis &&
                cursor.getLong(5) > nowMillis
        }
    }

    private fun actionSummary(task: GlafiraTask): String = runCatching {
        JSONObject(task.payload).optString("actionSummary").trim()
    }.getOrDefault("")

    private fun isAllowedReadHandler(toolId: String): Boolean =
        toolId in ALLOWED_READ_HANDLERS &&
            GlafiraToolRegistry.find(toolId)?.risk == ToolRiskLevel.READ_ONLY

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    companion object {
        const val TABLE = "core_live_run_grants"
        private const val LIVE_GRANT_TTL_MILLIS = 10L * 60L * 1000L
        private val ALLOWED_READ_HANDLERS = setOf(
            "project.read", "conversation.read",
            "github.repository.tree", "github.file.read"
        )
        private val OWNER_APPROVAL_GATED_MUTATIONS = setOf(
            "github.branch.create", "github.file.write", "github.pull_request.draft"
        )

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS $TABLE (
                    task_id TEXT PRIMARY KEY NOT NULL REFERENCES core_tasks(task_id) ON DELETE CASCADE,
                    project_id TEXT NOT NULL,
                    tool_id TEXT NOT NULL,
                    plan_hash TEXT NOT NULL CHECK(length(plan_hash) = 64),
                    action_hash TEXT NOT NULL CHECK(length(action_hash) = 64),
                    approved_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    approved_session_id TEXT NOT NULL
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS core_live_run_expiry_idx ON $TABLE(expires_at)")
        }
    }
}
