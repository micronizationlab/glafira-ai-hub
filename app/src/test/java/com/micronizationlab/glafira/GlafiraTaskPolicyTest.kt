package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraTaskPolicyTest {
    @Test
    fun ownerCanCancelOnlyUnclaimedTaskStates() {
        listOf(
            GlafiraTaskState.QUEUED,
            GlafiraTaskState.RETRY_SCHEDULED,
            GlafiraTaskState.WAITING_FOR_NETWORK,
            GlafiraTaskState.WAITING_FOR_OWNER
        ).forEach { assertTrue(GlafiraTaskPolicy.ownerCanCancel(it)) }
        assertFalse(GlafiraTaskPolicy.ownerCanCancel(GlafiraTaskState.RUNNING))
        assertFalse(GlafiraTaskPolicy.ownerCanCancel(GlafiraTaskState.SUCCEEDED))
        assertFalse(GlafiraTaskPolicy.ownerCanCancel(GlafiraTaskState.FAILED))
        assertFalse(GlafiraTaskPolicy.ownerCanCancel(GlafiraTaskState.CANCELLED))
    }


    @Test
    fun unknownExternalOutcomesCannotBeCancelled() {
        val task = GlafiraTask(
            taskId = "task-unknown",
            projectId = "project-1",
            taskType = "desktop.write",
            payload = "{}",
            origin = "owner",
            state = GlafiraTaskState.WAITING_FOR_OWNER,
            createdAtMillis = 10L,
            updatedAtMillis = 20L,
            idempotencyKey = "project-1:task-unknown"
        )
        assertFalse(GlafiraTaskPolicy.ownerCanCancel(task.copy(lastError = "CORE_EXECUTION_OUTCOME_UNKNOWN")))
        assertFalse(GlafiraTaskPolicy.ownerCanCancel(task.copy(lastError = "DESKTOP_EXECUTION_OUTCOME_UNKNOWN")))
        assertTrue(GlafiraTaskPolicy.ownerCanCancel(task.copy(lastError = "OWNER_APPROVAL_REQUIRED")))
    }

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
}
