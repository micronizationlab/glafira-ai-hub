package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class GlafiraBridgeCoreGateTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val now = 1_050_000L

    private fun signed(
        operation: GlafiraBridgeOperation = GlafiraBridgeOperation.PROJECT_READ,
        payload: Map<String, String> = emptyMap()
    ): GlafiraBridgeRequest = GlafiraBridgeProtocol.sign(
        GlafiraBridgeRequest(
            protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
            requestId = "request-0001",
            idempotencyKey = "idem-000001",
            deviceId = "device-0001",
            projectId = "project-001",
            operation = operation,
            issuedAtEpochMs = 1_000_000L,
            expiresAtEpochMs = 1_120_000L,
            payload = payload
        ),
        secret
    )

    @Test fun projectReadRequiresAuthenticatedDeviceAndScope() {
        val request = signed()
        val allowed = GlafiraBridgeCoreGate.authorize(
            request, "device-0001", secret, setOf("project.read"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeStatus.ACCEPTED, allowed.response.status)

        val denied = GlafiraBridgeCoreGate.authorize(
            request, "device-0001", secret, emptySet(), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeAuthorizationError.SCOPE_DENIED, denied.error)
    }

    @Test fun rejectsDeviceMismatchAndInvalidSignature() {
        val request = signed()
        val mismatch = GlafiraBridgeCoreGate.authorize(
            request, "device-0002", secret, setOf("project.read"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeAuthorizationError.DEVICE_MISMATCH, mismatch.error)

        val tampered = request.copy(projectId = "project-002")
        val invalid = GlafiraBridgeCoreGate.authorize(
            tampered, "device-0001", secret, setOf("project.read"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeAuthorizationError.AUTHENTICATION_FAILED, invalid.error)
    }

    @Test fun conversationAppendNeedsItsDedicatedScope() {
        val request = signed(
            GlafiraBridgeOperation.CONVERSATION_APPEND,
            mapOf("conversationId" to "conversation-01", "messageText" to "Hello")
        )
        val denied = GlafiraBridgeCoreGate.authorize(
            request, "device-0001", secret, setOf("conversation.read"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeAuthorizationError.SCOPE_DENIED, denied.error)

        val allowed = GlafiraBridgeCoreGate.authorize(
            request, "device-0001", secret, setOf("conversation.append"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeStatus.ACCEPTED, allowed.response.status)
    }

    @Test fun approvalRequestQueuesOwnerReviewAndDoesNotGrantExecution() {
        val request = signed(
            GlafiraBridgeOperation.APPROVAL_REQUEST,
            mapOf("toolId" to "message.send", "actionSummary" to "Send approved reply to customer")
        )
        val result = GlafiraBridgeCoreGate.authorize(
            request, "device-0001", secret, setOf("approval.request"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeStatus.PENDING_APPROVAL, result.response.status)
        assertEquals("message.send", result.response.result["toolId"])
    }

    @Test fun rejectsApprovalRequestsForUnknownOrNonPrivilegedTools() {
        val unknown = signed(
            GlafiraBridgeOperation.APPROVAL_REQUEST,
            mapOf("toolId" to "unknown.execute", "actionSummary" to "Run arbitrary command")
        )
        val unknownResult = GlafiraBridgeCoreGate.authorize(
            unknown, "device-0001", secret, setOf("approval.request"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeAuthorizationError.UNKNOWN_TOOL, unknownResult.error)

        val readOnly = signed(
            GlafiraBridgeOperation.APPROVAL_REQUEST,
            mapOf("toolId" to "project.read", "actionSummary" to "Read project")
        )
        val readOnlyResult = GlafiraBridgeCoreGate.authorize(
            readOnly, "device-0001", secret, setOf("approval.request"), nowEpochMs = now
        )
        assertEquals(GlafiraBridgeAuthorizationError.UNKNOWN_TOOL, readOnlyResult.error)
    }
}
