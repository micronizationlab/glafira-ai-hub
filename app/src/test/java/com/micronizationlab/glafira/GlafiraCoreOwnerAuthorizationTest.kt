package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraCoreOwnerAuthorizationTest {
    private class FakeGrantSource : GlafiraTaskGrantSource {
        var scopes: Set<String> = emptySet()
        var grant: ToolApprovalGrant? = null
        var requested: GlafiraTask? = null
        var requestedHash: String? = null

        override fun grantedScopes(task: GlafiraTask) = scopes
        override fun approvalGrant(task: GlafiraTask, actionSummary: String, nowMillis: Long) = grant
        override fun requestApproval(task: GlafiraTask, actionSummary: String, nowMillis: Long): Boolean {
            requested = task
            requestedHash = GlafiraToolRegistry.actionHash(task, actionSummary)
            return true
        }
    }

    private fun task(
        tool: String = "message.send",
        payload: String = """{"actionSummary":"Send the approved customer update"}"""
    ) = GlafiraTask(
        taskId = "task-owner-001",
        projectId = "project-owner-001",
        taskType = tool,
        payload = payload,
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-owner-001",
        requiredScopes = emptySet()
    )

    @Test
    fun missingScopeCreatesOwnerApprovalRequestAndNeverAuthorizes() {
        val grants = FakeGrantSource()
        val decision = RegistryBackedGlafiraTaskAuthorizer(grants).authorize(task(), 2_000L)

        assertEquals(GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER, decision.status)
        assertEquals("OWNER_APPROVAL_REQUIRED", decision.reason)
        assertEquals("task-owner-001", grants.requested?.taskId)
        assertFalse(grants.scopes.contains("communications.send"))
    }

    @Test
    fun riskyActionWithoutExactOwnerGrantWaitsForOwner() {
        val grants = FakeGrantSource().apply { scopes = setOf("communications.send") }
        val decision = RegistryBackedGlafiraTaskAuthorizer(grants).authorize(task(), 2_000L)

        assertEquals(GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER, decision.status)
        assertTrue(grants.requestedHash != null)
    }

    @Test
    fun exactTaskGrantAuthorizesOnlyTheMatchingPayload() {
        val original = task()
        val hash = GlafiraToolRegistry.actionHash(original, "Send the approved customer update")
        val grants = FakeGrantSource().apply {
            scopes = setOf("communications.send")
            grant = ToolApprovalGrant(
                projectId = original.projectId,
                toolId = original.taskType,
                actionHash = hash,
                approvedAt = 1_500L,
                expiresAt = 5_000L,
                taskId = original.taskId
            )
        }
        val authorizer = RegistryBackedGlafiraTaskAuthorizer(grants)

        assertEquals(GlafiraTaskAuthorizationStatus.AUTHORIZED, authorizer.authorize(original, 2_000L).status)
        val modified = original.copy(payload = """{"actionSummary":"Send a different message"}""")
        assertEquals(GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER, authorizer.authorize(modified, 2_000L).status)
    }

    @Test
    fun expiredOrForeignTaskGrantCannotAuthorize() {
        val current = task()
        val hash = GlafiraToolRegistry.actionHash(current, "Send the approved customer update")
        val grants = FakeGrantSource().apply {
            scopes = setOf("communications.send")
            grant = ToolApprovalGrant(
                current.projectId, current.taskType, hash,
                approvedAt = 1_500L, expiresAt = 1_999L, taskId = current.taskId
            )
        }
        assertEquals(
            GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER,
            RegistryBackedGlafiraTaskAuthorizer(grants).authorize(current, 2_000L).status
        )
        grants.grant = grants.grant!!.copy(taskId = "different-task", expiresAt = 5_000L)
        assertEquals(
            GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER,
            RegistryBackedGlafiraTaskAuthorizer(grants).authorize(current, 2_000L).status
        )
    }
}
