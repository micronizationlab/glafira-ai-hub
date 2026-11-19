package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class GlafiraResultProtocolTest {
    private val secret = ByteArray(32) { 7 }
    private fun request(operation: GlafiraBridgeOperation, payload: Map<String, String>) =
        GlafiraBridgeProtocol.sign(GlafiraBridgeRequest(1, "request-001", "delivery-001", "device-001",
            "project-001", operation, 1_000, 20_000, payload), secret)

    @Test
    fun statusAndAckRequireOwnerGrantedReadScopeAndSignedProject() {
        for ((operation, payload) in listOf(
            GlafiraBridgeOperation.TASK_STATUS to mapOf("taskId" to "bridge-task-001", "resultOffset" to "0"),
            GlafiraBridgeOperation.TASK_RESULT_ACK to mapOf("taskId" to "bridge-task-001", "resultId" to "result-bridge-task-001")
        )) {
            val signed = request(operation, payload)
            assertEquals(GlafiraBridgeStatus.ACCEPTED, GlafiraBridgeCoreGate.authorize(signed,
                "device-001", secret, setOf("project.read"), nowEpochMs = 2_000).response.status)
            assertEquals(GlafiraBridgeAuthorizationError.SCOPE_DENIED, GlafiraBridgeCoreGate.authorize(signed,
                "device-001", secret, emptySet(), nowEpochMs = 2_000).error)
            assertEquals(GlafiraBridgeAuthorizationError.AUTHENTICATION_FAILED, GlafiraBridgeCoreGate.authorize(
                signed.copy(projectId = "another-project"), "device-001", secret, setOf("project.read"), nowEpochMs = 2_000).error)
        }
    }

    @Test
    fun optionalJsonResultEncodingPreservesCompatibilityWithLegacyPayload() {
        val payload = mapOf("taskId" to "desktop-task-001", "state" to "SUCCEEDED", "progressPercent" to "100",
            "progressMessage" to "completed", "result" to "none", "errorCode" to "none")
        assertNull(GlafiraBridgeProtocol.validate(request(GlafiraBridgeOperation.DESKTOP_TASK_RESULT, payload), 2_000))
        assertNull(GlafiraBridgeProtocol.validate(request(GlafiraBridgeOperation.DESKTOP_TASK_RESULT,
            payload + mapOf("resultEncoding" to "json", "result" to "\"none\"")), 2_000))
        assertEquals(GlafiraBridgeValidationError.UNKNOWN_PAYLOAD_FIELD, GlafiraBridgeProtocol.validate(
            request(GlafiraBridgeOperation.TASK_STATUS, mapOf("taskId" to "task-001", "resultOffset" to "0", "anyField" to "x")), 2_000))
    }
}
