package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraDryRunPlannerTest {
    private fun task(
        type: String = "project.read",
        payload: String = """{"actionSummary":"Inspect project","projectId":"project-1","token":"sensitive-value"}"""
    ) = GlafiraTask(
        taskId = "task-1",
        projectId = "project-1",
        taskType = type,
        payload = payload,
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-1"
    )

    @Test
    fun planHashIsDeterministicForSameTask() {
        val first = GlafiraDryRunPlanner.build(task())
        val second = GlafiraDryRunPlanner.build(task())
        assertEquals(first.planHash, second.planHash)
    }

    @Test
    fun planHashChangesWhenActionPayloadChanges() {
        val first = GlafiraDryRunPlanner.build(task())
        val changed = GlafiraDryRunPlanner.build(
            task(payload = """{"actionSummary":"Inspect another target","projectId":"project-1"}""")
        )
        assertNotEquals(first.planHash, changed.planHash)
    }

    @Test
    fun safePreviewContainsNoPayloadValuesOrSecrets() {
        val json = GlafiraDryRunPlanner.build(
            task(payload = """{"actionSummary":"Private action","recipient":"private@example.com","token":"sensitive-value"}""")
        ).toSafeJson()
        assertTrue(json.contains("recipient"))
        assertFalse(json.contains("private@example.com"))
        assertFalse(json.contains("sensitive-value"))
        assertFalse(json.contains("Private action"))
    }

    @Test
    fun externalWriteIsNotSimulatedAndRequiresApprovalPoint() {
        val plan = GlafiraDryRunPlanner.build(
            task(
                type = "message.send",
                payload = """{"actionSummary":"Send a message","recipient":"private@example.com"}"""
            )
        )
        assertEquals(GlafiraDryRunSimulationStatus.NOT_SIMULATABLE, plan.simulationStatus)
        assertEquals(ToolRiskLevel.EXTERNAL_WRITE, plan.risk)
        assertTrue(plan.approvalPoints.any { it.contains("подтверждение владельца") })
    }

    @Test
    fun unknownToolFailsClosedAsNotSimulatable() {
        val plan = GlafiraDryRunPlanner.build(task(type = "unregistered.tool"))
        assertEquals(GlafiraDryRunSimulationStatus.NOT_SIMULATABLE, plan.simulationStatus)
        assertEquals(ToolRiskLevel.DESTRUCTIVE, plan.risk)
    }
}
