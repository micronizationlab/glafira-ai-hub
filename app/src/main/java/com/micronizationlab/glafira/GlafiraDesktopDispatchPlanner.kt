package com.micronizationlab.glafira

import org.json.JSONObject

/** Proposed placement of one queued Desktop task; it does not reserve or execute the task. */
data class GlafiraDesktopDispatchAssignment(
    val taskId: String,
    val projectId: String,
    val deviceId: String,
    val requiredCapabilities: Set<String>,
    val nodeCapacity: Int,
    val projectedActiveTasks: Int
)

/**
 * Capability-aware, least-loaded placement across fresh Desktop nodes.
 * The result is advisory: the actual claim path must revalidate identity, dependencies,
 * leases, runtime budget and owner authorization atomically before issuing a lease.
 */
object GlafiraDesktopDispatchPlanner {
    fun plan(
        tasks: List<GlafiraTask>,
        nodes: List<GlafiraNodeCapabilities>,
        activeLeasesByNode: Map<String, Int>
    ): List<GlafiraDesktopDispatchAssignment> {
        val load = nodes.associate { node ->
            node.nodeId to (activeLeasesByNode[node.nodeId] ?: 0).coerceAtLeast(0)
        }.toMutableMap()
        val assignments = mutableListOf<GlafiraDesktopDispatchAssignment>()

        tasks.asSequence()
            .filter { it.taskType == GlafiraTaskStore.DESKTOP_TASK_TYPE && it.state == GlafiraTaskState.QUEUED }
            .sortedWith(compareByDescending<GlafiraTask> { it.priority }.thenBy { it.createdAtMillis })
            .forEach { task ->
                val required = runCatching {
                    val values = JSONObject(task.payload).getJSONArray("requiredCapabilities")
                    buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
                }.getOrElse { return@forEach }
                val candidate = nodes.asSequence()
                    .filter { it.available && it.capabilities.containsAll(required) }
                    .filter { (load[it.nodeId] ?: 0) < it.maxParallelTasks }
                    .minWithOrNull(
                        compareBy<GlafiraNodeCapabilities> {
                            (load[it.nodeId] ?: 0).toDouble() / it.maxParallelTasks
                        }.thenBy { it.nodeId }
                    ) ?: return@forEach
                val nextLoad = (load[candidate.nodeId] ?: 0) + 1
                load[candidate.nodeId] = nextLoad
                assignments += GlafiraDesktopDispatchAssignment(
                    taskId = task.taskId,
                    projectId = task.projectId,
                    deviceId = candidate.nodeId,
                    requiredCapabilities = required,
                    nodeCapacity = candidate.maxParallelTasks,
                    projectedActiveTasks = nextLoad
                )
            }
        return assignments
    }
}
