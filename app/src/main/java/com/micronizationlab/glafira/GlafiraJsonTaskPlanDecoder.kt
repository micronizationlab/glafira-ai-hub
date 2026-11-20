package com.micronizationlab.glafira

/**
 * Reuses GLAFIRA's existing strict planner schema and converts its validated DAG to the
 * durable SQLite graph. There is one model-facing plan format, not two competing planners.
 */
object GlafiraJsonTaskPlanDecoder {
    fun decode(
        request: GlafiraCoreTaskPlanRequest,
        responseJson: String
    ): GlafiraCoreTaskPlan {
        require(request.maxNodes in 1..GlafiraCoreTaskPlanPolicy.MAX_NODES)
        val plannerPlan = GlafiraTaskPlanner.parseAndValidate(responseJson)
        require(plannerPlan.tasks.size <= request.maxNodes) {
            "Planner exceeded the requested node budget."
        }
        return GlafiraCoreTaskPlanAdapter.fromPlannerPlan(request, plannerPlan)
    }
}

/** The provider callback returns the same JSON contract used by GlafiraTaskPlanner. */
class GlafiraStructuredTaskPlanDecomposer(
    private val generateStructuredPlan: suspend (GlafiraCoreTaskPlanRequest) -> String
) : GlafiraCoreTaskPlanDecomposer {
    override suspend fun decompose(request: GlafiraCoreTaskPlanRequest): GlafiraCoreTaskPlan =
        GlafiraJsonTaskPlanDecoder.decode(request, generateStructuredPlan(request))
}
