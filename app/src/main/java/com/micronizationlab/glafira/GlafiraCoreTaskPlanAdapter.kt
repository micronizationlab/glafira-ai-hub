package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Bridges the existing model planner's typed DAG to the durable SQLite task graph. */
internal object GlafiraCoreTaskPlanAdapter {
    fun fromPlannerPlan(
        request: GlafiraCoreTaskPlanRequest,
        plan: GlafiraTaskPlan
    ): GlafiraCoreTaskPlan {
        require(plan.goal.isNotBlank())
        require(plan.tasks.size in 1..minOf(request.maxNodes, GlafiraTaskPlanner.MAX_TASKS))
        val ids = plan.tasks.associate { subtask ->
            subtask.id to UUID.nameUUIDFromBytes(
                (request.projectId + "|" + request.idempotencyKey + "|" + subtask.id)
                    .toByteArray(Charsets.UTF_8)
            ).toString()
        }
        val desktopTool = GlafiraToolRegistry.find(GlafiraTaskStore.DESKTOP_TASK_TYPE)
            ?: throw IllegalStateException("DESKTOP_EXECUTION_TOOL_NOT_REGISTERED")
        val deadline = request.createdAtMillis + GlafiraDistributedTaskOrchestrator.MAX_TASK_TTL_MS

        val nodes = plan.tasks.map { subtask ->
            val required = requiredCapabilities(subtask.executor)
            val payload = JSONObject().apply {
                put("actionSummary", subtask.title)
                put("parentTaskId", stablePlanId(request))
                put("subtaskId", subtask.id)
                put("title", subtask.title)
                put("instruction", subtask.instruction)
                put("executor", subtask.executor.name)
                put("requiredCapabilities", JSONArray(required.sorted()))
                put("planGoal", plan.goal)
            }.toString()
            val task = GlafiraTask(
                taskId = ids.getValue(subtask.id),
                projectId = request.projectId,
                taskType = GlafiraTaskStore.DESKTOP_TASK_TYPE,
                payload = payload,
                origin = "instruction-planner",
                priority = 5,
                createdAtMillis = request.createdAtMillis,
                updatedAtMillis = request.createdAtMillis,
                deadlineAtMillis = deadline,
                idempotencyKey = stableNodeIdempotencyKey(request, subtask.id),
                requiredScopes = desktopTool.requiredScopes
            )
            GlafiraCoreTaskPlanNode(
                task = task,
                dependsOnTaskIds = subtask.dependsOn.map { ids.getValue(it) }.toSet()
            )
        }
        return GlafiraCoreTaskPlan(
            planId = stablePlanId(request),
            projectId = request.projectId,
            idempotencyKey = request.idempotencyKey,
            nodes = nodes,
            createdAtMillis = request.createdAtMillis
        ).also(GlafiraCoreTaskPlanPolicy::validate)
    }

    fun toPlannerPlan(record: GlafiraCoreTaskPlanRecord): GlafiraTaskPlan =
        toPlannerPlan(GlafiraCoreTaskPlan(
            planId = record.planId,
            projectId = record.projectId,
            idempotencyKey = record.idempotencyKey,
            nodes = record.nodes.map { task ->
                GlafiraCoreTaskPlanNode(task, record.dependencies[task.taskId].orEmpty())
            },
            createdAtMillis = record.createdAtMillis
        ))

    fun toPlannerPlan(plan: GlafiraCoreTaskPlan): GlafiraTaskPlan {
        val nodesByTaskId = plan.nodes.associateBy { it.task.taskId }
        val subtaskIds = plan.nodes.associate { node ->
            node.task.taskId to JSONObject(node.task.payload).getString("subtaskId")
        }
        val tasks = plan.nodes.map { node ->
            val payload = JSONObject(node.task.payload)
            val executor = GlafiraTaskExecutor.valueOf(payload.getString("executor"))
            GlafiraSubtask(
                id = payload.getString("subtaskId"),
                title = payload.getString("title"),
                instruction = payload.getString("instruction"),
                executor = executor,
                dependsOn = node.dependsOnTaskIds.map { predecessor ->
                    require(predecessor in nodesByTaskId) { "DURABLE_PLAN_EDGE_OUTSIDE_PLAN" }
                    subtaskIds.getValue(predecessor)
                }
            )
        }
        val goal = JSONObject(plan.nodes.first().task.payload).optString("planGoal")
        return GlafiraTaskPlan(goal, tasks)
    }

    private fun stableNodeIdempotencyKey(
        request: GlafiraCoreTaskPlanRequest,
        subtaskId: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("node|" + request.projectId + "|" + request.idempotencyKey + "|" + subtaskId)
                .toByteArray(Charsets.UTF_8))
        return "node-" + digest.take(24).joinToString("") { "%02x".format(it) }
    }

    private fun stablePlanId(request: GlafiraCoreTaskPlanRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((request.projectId + "|" + request.idempotencyKey).toByteArray(Charsets.UTF_8))
        return "plan-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun requiredCapabilities(executor: GlafiraTaskExecutor): Set<String> = when (executor) {
        GlafiraTaskExecutor.GLAFIRA -> setOf("core.orchestration")
        GlafiraTaskExecutor.ANALYST -> setOf("document.analysis")
        GlafiraTaskExecutor.PROGRAMMER -> setOf("code.build")
        GlafiraTaskExecutor.RESEARCHER -> setOf("web.research")
    }
}
