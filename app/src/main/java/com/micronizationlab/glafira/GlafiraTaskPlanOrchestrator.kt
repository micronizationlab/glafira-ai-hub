package com.micronizationlab.glafira

/**
 * Normalized high-level instruction presented to a trusted model/planner adapter.
 * The raw instruction is deliberately not persisted in task payloads by this contract.
 */
data class GlafiraTaskPlanRequest(
    val instruction: String,
    val projectId: String,
    val idempotencyKey: String,
    val createdAtMillis: Long,
    val maxNodes: Int = GlafiraTaskPlanPolicy.MAX_NODES
)

/**
 * Model-backed or deterministic planner seam. Implementations return typed task nodes only;
 * they must not execute tools, access credentials, or perform side effects while decomposing.
 */
fun interface GlafiraTaskPlanDecomposer {
    fun decompose(request: GlafiraTaskPlanRequest): GlafiraTaskPlan
}

/**
 * Validates a decomposition result and commits its complete DAG through the durable queue.
 * Persistence remains the sole authority for idempotency and task/dependency atomicity.
 */
class GlafiraTaskPlanOrchestrator(
    private val decomposer: GlafiraTaskPlanDecomposer,
    private val persist: (GlafiraTaskPlan) -> GlafiraTaskPlanEnqueueResult
) {
    fun submit(request: GlafiraTaskPlanRequest): GlafiraTaskPlanEnqueueResult {
        require(request.instruction.isNotBlank()) { "A non-empty instruction is required." }
        require(request.instruction.length <= 32_000) { "Instruction exceeds the planning limit." }
        require(request.projectId.isNotBlank())
        require(request.idempotencyKey.matches(Regex("[A-Za-z0-9_.:-]{8,128}")))
        require(request.createdAtMillis > 0L)
        require(request.maxNodes in 1..GlafiraTaskPlanPolicy.MAX_NODES)

        val plan = decomposer.decompose(request)
        require(plan.projectId == request.projectId) { "Planner returned a plan for another project." }
        require(plan.idempotencyKey == request.idempotencyKey) { "Planner changed the idempotency key." }
        require(plan.createdAtMillis == request.createdAtMillis) { "Planner changed the request timestamp." }
        require(plan.nodes.size <= request.maxNodes) { "Planner exceeded the requested node budget." }
        GlafiraTaskPlanPolicy.validate(plan)
        return persist(plan)
    }
}
