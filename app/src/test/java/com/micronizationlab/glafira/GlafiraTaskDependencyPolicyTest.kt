package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GlafiraTaskDependencyPolicyTest {
    @Test fun independentTasksAreReadyWithoutPredecessors() {
        assertTrue(GlafiraTaskDependencyPolicy.isReady(emptyList()))
    }

    @Test fun taskWaitsUntilEveryPredecessorSucceeds() {
        assertFalse(GlafiraTaskDependencyPolicy.isReady(
            listOf(GlafiraTaskState.SUCCEEDED, GlafiraTaskState.RUNNING)
        ))
        assertTrue(GlafiraTaskDependencyPolicy.isReady(
            listOf(GlafiraTaskState.SUCCEEDED, GlafiraTaskState.SUCCEEDED)
        ))
    }

    @Test fun failedOrCancelledPredecessorIsDetected() {
        assertTrue(GlafiraTaskDependencyPolicy.hasFailedPredecessor(
            listOf(GlafiraTaskState.SUCCEEDED, GlafiraTaskState.FAILED)
        ))
        assertTrue(GlafiraTaskDependencyPolicy.hasFailedPredecessor(
            listOf(GlafiraTaskState.CANCELLED)
        ))
        assertFalse(GlafiraTaskDependencyPolicy.hasFailedPredecessor(
            listOf(GlafiraTaskState.SUCCEEDED, GlafiraTaskState.RUNNING)
        ))
    }

    @Test fun rejectsDirectAndTransitiveCycles() {
        assertCycleRejected("task-a", setOf("task-a"), emptyMap())
        assertCycleRejected(
            "task-a", setOf("task-b"),
            mapOf("task-b" to setOf("task-c"), "task-c" to setOf("task-a"))
        )
    }

    @Test fun allowsAcyclicFanInAndFanOut() {
        GlafiraTaskDependencyPolicy.validate(
            "task-d", setOf("task-b", "task-c"),
            mapOf("task-b" to setOf("task-a"), "task-c" to setOf("task-a"))
        )
    }

    @Test fun enforcesDependencyLimit() {
        try {
            GlafiraTaskDependencyPolicy.validate(
                "task", (1..65).map { "dep-$it" }.toSet(), emptyMap()
            )
            fail("Expected dependency limit rejection.")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertCycleRejected(
        taskId: String,
        dependencies: Set<String>,
        graph: Map<String, Set<String>>
    ) {
        try {
            GlafiraTaskDependencyPolicy.validate(taskId, dependencies, graph)
            fail("Expected dependency cycle rejection.")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

// CI freshness trigger: execute dependency-policy tests against this exact PR head.
