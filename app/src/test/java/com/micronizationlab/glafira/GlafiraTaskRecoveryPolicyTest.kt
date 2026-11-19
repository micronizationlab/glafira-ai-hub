package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class GlafiraTaskRecoveryPolicyTest {
    private fun interrupted(type: String, attempts: Int = 1) = GlafiraTask(
        taskId = "interrupted", projectId = "project", taskType = type, payload = "{}",
        origin = "owner", state = GlafiraTaskState.RUNNING, createdAtMillis = 1_000,
        updatedAtMillis = 2_000, attemptCount = attempts, idempotencyKey = "interrupted"
    )

    @Test fun readOnlyInterruptedAttemptCanRetryWithinBudget() {
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED,
            GlafiraTaskRecoveryPolicy.interrupted(interrupted("project.read"), 5).state)
    }

    @Test fun readOnlyRecoveryCannotExceedRetryBudget() {
        assertEquals(GlafiraTaskState.FAILED,
            GlafiraTaskRecoveryPolicy.interrupted(interrupted("project.read", 6), 5).state)
    }

    @Test fun interruptedWritesDraftsAndUnknownToolsRequireReconciliation() {
        listOf("message.send", "social.publish", "payment.initiate", "record.delete",
            "conversation.append", "content.draft", "unregistered.tool").forEach { tool ->
            val decision = GlafiraTaskRecoveryPolicy.interrupted(interrupted(tool), 5)
            assertEquals(tool, GlafiraTaskState.WAITING_FOR_OWNER, decision.state)
            assertEquals("CORE_EXECUTION_OUTCOME_UNKNOWN", decision.reason)
        }
    }

    @Test fun exhaustedWriteStillRequiresOutcomeReconciliation() {
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER,
            GlafiraTaskRecoveryPolicy.interrupted(interrupted("payment.initiate", 20), 0).state)
    }
}
