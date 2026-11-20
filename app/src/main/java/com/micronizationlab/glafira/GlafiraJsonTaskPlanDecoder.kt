package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a model's structured JSON response into typed, permission-bounded Core task nodes.
 * The model may propose task types only from the caller's explicit allowlist; it cannot set
 * project identity, owner grants, execution mode, task state, credentials or arbitrary handlers.
 */
object GlafiraJsonTaskPlanDecoder {
    const val MAX_RESPONSE_CHARS = 96 * 1024

    fun decode(
        request: GlafiraCoreTaskPlanRequest,
        responseJson: String,
        allowedTaskTypes: Set<String>
    ): GlafiraCoreTaskPlan {
        require(responseJson.length in 2..MAX_RESPONSE_CHARS) { "Planner response size is invalid." }
        require(request.maxNodes in 1..GlafiraCoreTaskPlanPolicy.MAX_NODES)
        require(allowedTaskTypes.isNotEmpty() && allowedTaskTypes.size <= 64)
        require(allowedTaskTypes.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })

        val root = JSONObject(responseJson)
        val planId = root.getString("planId")
        val rawNodes = root.getJSONArray("nodes")
        require(rawNodes.length() in 1..request.maxNodes) { "Planner node count exceeds the request budget." }

        val nodes = buildList {
            for (index in 0 until rawNodes.length()) {
                val raw = rawNodes.getJSONObject(index)
                val taskId = raw.getString("taskId")
                val taskType = raw.getString("taskType")
                require(taskType in allowedTaskTypes) { "Planner proposed a task type outside the approved allowlist." }
                val definition = GlafiraToolRegistry.find(taskType)
                    ?: throw IllegalArgumentException("Planner proposed an unregistered task type.")
                val payload = raw.getString("payload")
                require(payload.length <= 512 * 1024)
                // Current Core handlers consume typed JSON objects; reject free-form payloads.
                JSONObject(payload)
                val priority = raw.optInt("priority", 0)
                val dependencies = raw.optJSONArray("dependsOnTaskIds").toStringSet()
                val task = GlafiraTask(
                    taskId = taskId,
                    projectId = request.projectId,
                    taskType = taskType,
                    payload = payload,
                    origin = "instruction-planner",
                    priority = priority,
                    createdAtMillis = request.createdAtMillis,
                    updatedAtMillis = request.createdAtMillis,
                    idempotencyKey = request.idempotencyKey + ":" + taskId,
                    // Scopes come from the trusted registry, never from model-authored JSON.
                    requiredScopes = definition.requiredScopes
                )
                add(GlafiraCoreTaskPlanNode(task, dependencies))
            }
        }

        return GlafiraCoreTaskPlan(
            planId = planId,
            projectId = request.projectId,
            idempotencyKey = request.idempotencyKey,
            nodes = nodes,
            createdAtMillis = request.createdAtMillis
        ).also(GlafiraCoreTaskPlanPolicy::validate)
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        require(length() <= 64)
        return buildSet {
            for (index in 0 until length()) add(getString(index))
        }
    }
}

/**
 * Adapter for an approved model provider. The injected generator must return structured JSON;
 * this class only decodes and validates it and never invokes tools itself.
 */
class GlafiraStructuredTaskPlanDecomposer(
    private val allowedTaskTypes: Set<String>,
    private val generateStructuredPlan: (GlafiraCoreTaskPlanRequest) -> String
) : GlafiraCoreTaskPlanDecomposer {
    override fun decompose(request: GlafiraCoreTaskPlanRequest): GlafiraCoreTaskPlan =
        GlafiraJsonTaskPlanDecoder.decode(
            request,
            generateStructuredPlan(request),
            allowedTaskTypes
        )
}
