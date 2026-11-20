package com.micronizationlab.glafira

/** One executable unit in a durable, owner-approved orchestration plan. */
data class GlafiraTaskPlanNode(
    val task: GlafiraTask,
    /** IDs of predecessor nodes; a node is runnable only after all predecessors succeed. */
    val dependsOnTaskIds: Set<String> = emptySet()
)

/**
 * A bounded DAG of Core tasks created from one higher-level instruction.
 *
 * Decomposition is supplied by a trusted planner; this model never executes model-generated
 * code. The plan and every node/edge are persisted atomically before workers are notified.
 */
data class GlafiraTaskPlan(
    val planId: String,
    val projectId: String,
    val idempotencyKey: String,
    val nodes: List<GlafiraTaskPlanNode>,
    val createdAtMillis: Long
)

enum class GlafiraTaskPlanStatus {
    QUEUED, RUNNING, WAITING_FOR_OWNER, SUCCEEDED, FAILED, CANCELLED
}

data class GlafiraTaskPlanRecord(
    val planId: String,
    val projectId: String,
    val idempotencyKey: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val nodes: List<GlafiraTask>
) {
    val status: GlafiraTaskPlanStatus
        get() = when {
            nodes.any { it.state == GlafiraTaskState.FAILED } -> GlafiraTaskPlanStatus.FAILED
            nodes.any { it.state == GlafiraTaskState.CANCELLED } -> GlafiraTaskPlanStatus.CANCELLED
            nodes.all { it.state == GlafiraTaskState.SUCCEEDED } -> GlafiraTaskPlanStatus.SUCCEEDED
            nodes.any { it.state == GlafiraTaskState.RUNNING } -> GlafiraTaskPlanStatus.RUNNING
            nodes.any { it.state == GlafiraTaskState.WAITING_FOR_OWNER } -> GlafiraTaskPlanStatus.WAITING_FOR_OWNER
            else -> GlafiraTaskPlanStatus.QUEUED
        }
    val completedCount: Int
        get() = nodes.count { it.state in setOf(
            GlafiraTaskState.SUCCEEDED, GlafiraTaskState.FAILED, GlafiraTaskState.CANCELLED
        ) }
}

data class GlafiraTaskPlanEnqueueResult(
    val plan: GlafiraTaskPlanRecord,
    val inserted: Boolean
)

internal object GlafiraTaskPlanPolicy {
    const val MAX_NODES = 64

    fun validate(plan: GlafiraTaskPlan) {
        require(plan.planId.matches(Regex("[A-Za-z0-9_.:-]{8,128}"))) { "Invalid task plan ID." }
        require(plan.projectId.isNotBlank()) { "Task plan project ID is required." }
        require(plan.idempotencyKey.matches(Regex("[A-Za-z0-9_.:-]{8,128}"))) {
            "Invalid task plan idempotency key."
        }
        require(plan.createdAtMillis > 0L)
        require(plan.nodes.isNotEmpty() && plan.nodes.size <= MAX_NODES) {
            "A task plan must contain between 1 and $MAX_NODES nodes."
        }
        val ids = plan.nodes.map { it.task.taskId }
        require(ids.size == ids.toSet().size) { "Task plan node IDs must be unique." }
        require(plan.nodes.all { it.task.projectId == plan.projectId }) {
            "Every task plan node must belong to the plan project."
        }
        val nodeIds = ids.toSet()
        plan.nodes.forEach { node ->
            GlafiraTaskPolicy.validate(node.task)
            require(node.dependsOnTaskIds.size <= GlafiraTaskDependencyPolicy.MAX_DEPENDENCIES_PER_TASK)
            require(node.dependsOnTaskIds.none { it in nodeIds && it == node.task.taskId }) {
                "A task plan node cannot depend on itself."
            }
        }
        // Validate the complete in-plan graph before touching persistent storage.
        val graph = mutableMapOf<String, Set<String>>()
        plan.nodes.forEach { node ->
            GlafiraTaskDependencyPolicy.validate(node.task.taskId, node.dependsOnTaskIds, graph)
            graph[node.task.taskId] = node.dependsOnTaskIds
        }
        require(plan.nodes.sumOf { it.dependsOnTaskIds.size } <= MAX_NODES * 8) {
            "Task plan dependency graph exceeds its edge budget."
        }
    }
}
