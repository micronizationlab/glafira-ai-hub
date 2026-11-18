package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlafiraToolRegistryTest {
    @Test
    fun readAndDraftActionsDoNotRequireOwnerApproval() {
        val read = GlafiraToolRegistry.find("project.read")!!
        val draft = GlafiraToolRegistry.find("content.draft")!!

        assertEquals(
            ToolAuthorizationDecision.ALLOWED,
            GlafiraToolRegistry.authorize(read, "project-a", "Read current project", null)
        )
        assertEquals(
            ToolAuthorizationDecision.ALLOWED,
            GlafiraToolRegistry.authorize(draft, "project-a", "Draft a reply", null)
        )
    }

    @Test
    fun bridgeConversationOperationsAreRegisteredWithDedicatedScopes() {
        val conversationRead = GlafiraToolRegistry.find("conversation.read")!!
        val conversationAppend = GlafiraToolRegistry.find("conversation.append")!!
        val approvalRequest = GlafiraToolRegistry.find("approval.request")!!

        assertEquals(ToolRiskLevel.READ_ONLY, conversationRead.risk)
        assertEquals(setOf("conversation.read"), conversationRead.requiredScopes)
        assertEquals(ToolRiskLevel.DRAFT, conversationAppend.risk)
        assertEquals(setOf("conversation.append"), conversationAppend.requiredScopes)
        assertEquals(ToolRiskLevel.DRAFT, approvalRequest.risk)
        assertEquals(setOf("approval.request"), approvalRequest.requiredScopes)

        assertEquals(
            ToolAuthorizationDecision.ALLOWED,
            GlafiraToolRegistry.authorize(
                conversationRead, "project-a", "Read conversation conversation-1", null
            )
        )
        assertEquals(
            ToolAuthorizationDecision.ALLOWED,
            GlafiraToolRegistry.authorize(
                conversationAppend, "project-a", "Append message to conversation conversation-1", null
            )
        )
        assertEquals(
            ToolAuthorizationDecision.ALLOWED,
            GlafiraToolRegistry.authorize(
                approvalRequest, "project-a", "Request approval for message.send", null
            )
        )
    }

    @Test
    fun externalWriteFinancialAndDestructiveActionsRequireApproval() {
        listOf("message.send", "payment.initiate", "record.delete").forEach { id ->
            val definition = GlafiraToolRegistry.find(id)!!
            assertEquals(
                ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED,
                GlafiraToolRegistry.authorize(definition, "project-a", "Specific action", null)
            )
        }
    }

    @Test
    fun approvalGrantIsBoundToProjectToolAndExactAction() {
        val definition = GlafiraToolRegistry.find("message.send")!!
        val grant = GlafiraToolRegistry.issueGrantForOwnerApprovedRequest(
            projectId = "project-a",
            toolId = definition.id,
            actionSummary = "Send approved reply to customer 123",
            approvedAt = 1_000L,
            expiresAt = 10_000L
        )

        assertEquals(
            ToolAuthorizationDecision.ALLOWED,
            GlafiraToolRegistry.authorize(
                definition, "project-a", "Send approved reply to customer 123", grant, now = 2_000L
            )
        )
        assertEquals(
            ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED,
            GlafiraToolRegistry.authorize(
                definition, "project-b", "Send approved reply to customer 123", grant, now = 2_000L
            )
        )
        assertEquals(
            ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED,
            GlafiraToolRegistry.authorize(
                definition, "project-a", "Send a different message", grant, now = 2_000L
            )
        )
        assertEquals(
            ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED,
            GlafiraToolRegistry.authorize(
                definition, "project-a", "Send approved reply to customer 123", grant, now = 10_000L
            )
        )
    }

    @Test
    fun registryContainsOnlyKnownToolDefinitions() {
        assertNotNull(GlafiraToolRegistry.find("social.publish"))
        assertNull(GlafiraToolRegistry.find("unknown.execute"))
    }
}
