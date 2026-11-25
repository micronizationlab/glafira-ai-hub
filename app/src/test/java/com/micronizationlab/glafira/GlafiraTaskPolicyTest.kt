package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraTaskPolicyTest {
    @Test
    fun runningTaskCanWaitForNetworkOrOwnerAndCanFinish() {
        assertTrue(GlafiraTaskPolicy.canTransition(
            GlafiraTaskState.RUNNING,
            GlafiraTaskState.WAITING_FOR_NETWORK
        ))
        assertTrue(GlafiraTaskPolicy.canTransition(
            GlafiraTaskState.RUNNING,
            GlafiraTaskState.WAITING_FOR_OWNER
        ))
        assertTrue(GlafiraTaskPolicy.canTransition(
            GlafiraTaskState.RUNNING,
            GlafiraTaskState.SUCCEEDED
        ))
    }

    @Test
    fun terminalStatesCannotBeReopened() {
        listOf(
            GlafiraTaskState.SUCCEEDED,
            GlafiraTaskState.FAILED,
            GlafiraTaskState.CANCELLED
        ).forEach { terminal ->
            GlafiraTaskState.values().forEach { next ->
                assertFalse(GlafiraTaskPolicy.canTransition(terminal, next))
            }
        }
    }

    @Test
    fun ownerWaitCanOnlyResumeOrTerminate() {
        assertTrue(GlafiraTaskPolicy.canTransition(
            GlafiraTaskState.WAITING_FOR_OWNER,
            GlafiraTaskState.QUEUED
        ))
        assertTrue(GlafiraTaskPolicy.canTransition(
            GlafiraTaskState.WAITING_FOR_OWNER,
            GlafiraTaskState.CANCELLED
        ))
        assertFalse(GlafiraTaskPolicy.canTransition(
            GlafiraTaskState.WAITING_FOR_OWNER,
            GlafiraTaskState.RUNNING
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun taskRequiresAnIdempotencyKey() {
        GlafiraTaskPolicy.validate(
            GlafiraTask(
                taskId = "task-1",
                projectId = "project-1",
                taskType = "internal",
                payload = "{}",
                origin = "owner",
                createdAtMillis = 10L,
                updatedAtMillis = 10L,
                idempotencyKey = " "
            )
        )
    }

    @Test
    fun validTaskPassesContractValidation() {
        GlafiraTaskPolicy.validate(
            GlafiraTask(
                taskId = "task-1",
                projectId = "project-1",
                taskType = "internal",
                payload = "{}",
                origin = "owner",
                createdAtMillis = 10L,
                updatedAtMillis = 10L,
                idempotencyKey = "project-1:task-1",
                requiredScopes = setOf("core.read")
            )
        )
    }
    @Test
    fun ownerCanCancelOnlyNeverClaimedTaskStates() {
        listOf(
            GlafiraTaskState.QUEUED,
            GlafiraTaskState.WAITING_FOR_NETWORK,
            GlafiraTaskState.WAITING_FOR_OWNER
        ).forEach { state ->
            val task = GlafiraTask(
                taskId = "cancel-$state", projectId = "project-1", taskType = "project.read",
                payload = "{}", origin = "owner", state = state, createdAtMillis = 10L,
                updatedAtMillis = 10L, idempotencyKey = "project-1:cancel-$state"
            )
            assertTrue(GlafiraTaskPolicy.ownerCanCancel(task))
            val previouslyClaimed = task.copy(
                taskId = "previously-claimed-$state",
                attemptCount = 1,
                idempotencyKey = "project-1:previously-claimed-$state"
            )
            assertFalse("Attempt-count guard must reject $state after a claim",
                GlafiraTaskPolicy.ownerCanCancel(previouslyClaimed))
        }
        val disallowedWithoutAttempt = GlafiraTask(
            taskId = "running-zero-attempts", projectId = "project-1", taskType = "project.read",
            payload = "{}", origin = "owner", state = GlafiraTaskState.RUNNING,
            createdAtMillis = 10L, updatedAtMillis = 20L, attemptCount = 0,
            idempotencyKey = "project-1:running-zero-attempts"
        )
        assertFalse("State allowlist must reject RUNNING even with zero attempts",
            GlafiraTaskPolicy.ownerCanCancel(disallowedWithoutAttempt))
        val retried = GlafiraTask(
            taskId = "retried", projectId = "project-1", taskType = "project.read",
            payload = "{}", origin = "owner", state = GlafiraTaskState.RETRY_SCHEDULED,
            createdAtMillis = 10L, updatedAtMillis = 20L, attemptCount = 1,
            idempotencyKey = "project-1:retried"
        )
        assertFalse(GlafiraTaskPolicy.ownerCanCancel(retried))
    }

    @Test
    fun persistenceFailureIsUncertainOnlyForPotentialExternalEffects() {
        val readOnly = GlafiraTask(
            taskId = "read-only-persist-failure", projectId = "project-1", taskType = "project.read",
            payload = "{}", origin = "owner", state = GlafiraTaskState.WAITING_FOR_OWNER,
            createdAtMillis = 10L, updatedAtMillis = 20L,
            lastError = "CORE_RESULT_PERSIST_FAILED:read-only-result",
            idempotencyKey = "project-1:read-only-persist-failure"
        )
        val externalWrite = readOnly.copy(
            taskId = "write-persist-failure",
            taskType = "unregistered.external.write",
            idempotencyKey = "project-1:write-persist-failure"
        )
        assertFalse(GlafiraTaskPolicy.hasUnresolvedExecutionOutcome(readOnly))
        assertTrue(GlafiraTaskPolicy.hasUnresolvedExecutionOutcome(externalWrite))
    }

    @Test
    fun ownerCannotCancelTasksWithUnresolvedExecutionOutcomes() {
        listOf(
            "CORE_EXECUTION_OUTCOME_UNKNOWN",
            "DESKTOP_EXECUTION_OUTCOME_UNKNOWN",
            "EXECUTION_OUTCOME_UNCERTAIN",
            "CORE_RESULT_PERSIST_FAILED:CORE_EXECUTION_OUTCOME_UNKNOWN",
            "CORE_RESULT_PERSIST_FAILED:timeout-after-commit"
        ).forEachIndexed { index, marker ->
            val task = GlafiraTask(
                taskId = "unknown-$index", projectId = "project-1", taskType = "project.write",
                payload = "{}", origin = "owner", state = GlafiraTaskState.WAITING_FOR_OWNER,
                createdAtMillis = 10L, updatedAtMillis = 20L, lastError = marker,
                idempotencyKey = "project-1:unknown-$index"
            )
            assertFalse(GlafiraTaskPolicy.ownerCanCancel(task))
        }
    }

}
