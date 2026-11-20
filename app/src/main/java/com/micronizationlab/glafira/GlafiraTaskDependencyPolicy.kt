package com.micronizationlab.glafira

/**
 * Dependency rules for durable Core tasks.
 *
 * An edge task -> predecessor means task may start only after predecessor succeeds.
 * The graph is intentionally project-agnostic here; the SQLite owner enforces project scope.
 */
internal object GlafiraTaskDependencyPolicy {
    const val MAX_DEPENDENCIES_PER_TASK = 64

    fun validate(
        taskId: String,
        dependencyIds: Set<String>,
        existingGraph: Map<String, Set<String>>
    ) {
        require(taskId.isNotBlank()) { "Task ID is required." }
        require(dependencyIds.size <= MAX_DEPENDENCIES_PER_TASK) {
            "A task cannot have more than $MAX_DEPENDENCIES_PER_TASK dependencies."
        }
        require(dependencyIds.none { it.isBlank() }) { "Dependency IDs cannot be blank." }
        require(taskId !in dependencyIds) { "A task cannot depend on itself." }

        // If any proposed predecessor already depends (directly or transitively) on this
        // task, adding the edge would introduce a cycle.
        for (predecessorId in dependencyIds) {
            val visited = mutableSetOf<String>()
            val pending = ArrayDeque<String>()
            pending.add(predecessorId)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current == taskId) {
                    throw IllegalArgumentException("Task dependency cycle detected.")
                }
                if (visited.add(current)) {
                    existingGraph[current].orEmpty().forEach(pending::addLast)
                }
            }
        }
    }

    fun isReady(predecessorStates: Collection<GlafiraTaskState>): Boolean =
        predecessorStates.all { it == GlafiraTaskState.SUCCEEDED }

    fun hasFailedPredecessor(predecessorStates: Collection<GlafiraTaskState>): Boolean =
        predecessorStates.any {
            it == GlafiraTaskState.FAILED || it == GlafiraTaskState.CANCELLED
        }
}
