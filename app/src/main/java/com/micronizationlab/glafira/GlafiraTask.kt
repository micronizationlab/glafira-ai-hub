package com.micronizationlab.glafira

data class GlafiraTask(
    val taskId: String,
    val projectId: String,
    val taskType: String,
    val payload: String,
    val origin: String,
    val priority: Int = 0,
    val state: GlafiraTaskState = GlafiraTaskState.QUEUED,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val attemptCount: Int = 0,
    val deadlineAtMillis: Long? = null,
    val idempotencyKey: String,
    val requiredScopes: Set<String> = emptySet(),
    val retryAtMillis: Long? = null,
    val lastError: String? = null
)

enum class GlafiraTaskState(val databaseValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_FOR_NETWORK("waiting_for_network"),
    WAITING_FOR_OWNER("waiting_for_owner"),
    RETRY_SCHEDULED("retry_scheduled"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromDatabaseValue(value: String): GlafiraTaskState =
            values().firstOrNull { it.databaseValue == value }
                ?: throw IllegalStateException("Unknown persisted GLAFIRA task state.")
    }
}

data class GlafiraTaskEnqueueResult(
    val task: GlafiraTask,
    val inserted: Boolean
)

internal object GlafiraTaskPolicy {
    fun validate(task: GlafiraTask) {
        require(task.taskId.isNotBlank()) { "Task ID is required." }
        require(task.projectId.isNotBlank()) { "Project ID is required." }
        require(task.taskType.isNotBlank()) { "Task type is required." }
        require(task.origin.isNotBlank()) { "Task origin is required." }
        require(task.idempotencyKey.isNotBlank()) { "Idempotency key is required." }
        require(task.payload.length <= 512 * 1024) { "Task payload exceeds the 512 KiB limit." }
        require(task.priority in -1000..1000) { "Task priority is outside the supported range." }
        require(task.state == GlafiraTaskState.QUEUED) { "New tasks must enter the queue in the queued state." }
        require(task.attemptCount == 0) { "New tasks must start with zero attempts." }
        require(task.retryAtMillis == null) { "New tasks cannot have a retry timestamp." }
        require(task.createdAtMillis > 0L) { "Creation timestamp is required." }
        require(task.updatedAtMillis >= task.createdAtMillis) { "Update timestamp cannot precede creation." }
        require(task.deadlineAtMillis == null || task.deadlineAtMillis >= task.createdAtMillis) {
            "Task deadline cannot precede creation."
        }
        require(task.requiredScopes.size <= 64 && task.requiredScopes.all { it.isNotBlank() }) {
            "Task scopes must contain at most 64 non-empty values."
        }
    }

    fun canTransition(from: GlafiraTaskState, to: GlafiraTaskState): Boolean =
        when (from) {
            GlafiraTaskState.QUEUED -> to in setOf(
                GlafiraTaskState.RUNNING,
                GlafiraTaskState.WAITING_FOR_NETWORK,
                GlafiraTaskState.WAITING_FOR_OWNER,
                GlafiraTaskState.CANCELLED,
                GlafiraTaskState.FAILED
            )
            GlafiraTaskState.RUNNING -> to in setOf(
                GlafiraTaskState.WAITING_FOR_NETWORK,
                GlafiraTaskState.WAITING_FOR_OWNER,
                GlafiraTaskState.RETRY_SCHEDULED,
                GlafiraTaskState.SUCCEEDED,
                GlafiraTaskState.FAILED,
                GlafiraTaskState.CANCELLED
            )
            GlafiraTaskState.WAITING_FOR_NETWORK -> to in setOf(
                GlafiraTaskState.QUEUED,
                GlafiraTaskState.RUNNING,
                GlafiraTaskState.FAILED,
                GlafiraTaskState.CANCELLED
            )
            GlafiraTaskState.WAITING_FOR_OWNER -> to in setOf(
                GlafiraTaskState.QUEUED,
                GlafiraTaskState.FAILED,
                GlafiraTaskState.CANCELLED
            )
            GlafiraTaskState.RETRY_SCHEDULED -> to in setOf(
                GlafiraTaskState.RUNNING,
                GlafiraTaskState.FAILED,
                GlafiraTaskState.CANCELLED
            )
            GlafiraTaskState.SUCCEEDED,
            GlafiraTaskState.FAILED,
            GlafiraTaskState.CANCELLED -> false
        }
}


data class GlafiraTaskStateEvent(
    val eventId: Long,
    val taskId: String,
    val projectId: String,
    val fromState: GlafiraTaskState?,
    val toState: GlafiraTaskState,
    val changedAtMillis: Long,
    val attemptCount: Int,
    val reason: String?
)

internal data class GlafiraTaskRecoveryDecision(val state: GlafiraTaskState, val reason: String)

/** An unknown outcome of a write must be reconciled before it can be executed again. */
internal object GlafiraTaskRecoveryPolicy {
    fun interrupted(task: GlafiraTask, maxRetries: Int): GlafiraTaskRecoveryDecision {
        require(maxRetries in 0..20)
        val risk = GlafiraToolRegistry.find(task.taskType)?.risk
        if (risk != ToolRiskLevel.READ_ONLY) return GlafiraTaskRecoveryDecision(
            GlafiraTaskState.WAITING_FOR_OWNER, "CORE_EXECUTION_OUTCOME_UNKNOWN")
        return if (task.attemptCount >= maxRetries + 1) GlafiraTaskRecoveryDecision(
            GlafiraTaskState.FAILED, "CORE_INTERRUPTED_RETRY_LIMIT_REACHED")
        else GlafiraTaskRecoveryDecision(GlafiraTaskState.RETRY_SCHEDULED, "CORE_EXECUTION_INTERRUPTED")
    }
}
