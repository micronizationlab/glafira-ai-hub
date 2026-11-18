package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlafiraBridgeProtocolTest {
    private fun request(
        operation: GlafiraBridgeOperation = GlafiraBridgeOperation.PROJECT_READ,
        payload: Map<String, String> = emptyMap(),
        issuedAt: Long = 1_000_000L,
        expiresAt: Long = 1_120_000L
    ) = GlafiraBridgeRequest(
        protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
        requestId = "request-0001",
        idempotencyKey = "idem-000001",
        deviceId = "device-0001",
        projectId = "project-001",
        operation = operation,
        issuedAtEpochMs = issuedAt,
        expiresAtEpochMs = expiresAt,
        payload = payload
    )

    @Test
    fun acceptsNarrowProjectReadEnvelope() {
        assertNull(GlafiraBridgeProtocol.validate(request(), nowEpochMs = 1_050_000L))
    }

    @Test
    fun rejectsUnsupportedProtocolVersion() {
        val invalid = request().copy(protocolVersion = 2)
        assertEquals(
            GlafiraBridgeValidationError.UNSUPPORTED_VERSION,
            GlafiraBridgeProtocol.validate(invalid, nowEpochMs = 1_050_000L)
        )
    }

    @Test
    fun rejectsExpiredAndOverlongRequests() {
        assertEquals(
            GlafiraBridgeValidationError.EXPIRED_REQUEST,
            GlafiraBridgeProtocol.validate(request(expiresAt = 1_040_000L), nowEpochMs = 1_040_000L)
        )
        assertEquals(
            GlafiraBridgeValidationError.INVALID_TIME_WINDOW,
            GlafiraBridgeProtocol.validate(request(expiresAt = 1_400_001L), nowEpochMs = 1_050_000L)
        )
    }

    @Test
    fun rejectsFieldsOutsideOperationAllowlist() {
        val invalid = request(payload = mapOf("shellCommand" to "anything"))
        assertEquals(
            GlafiraBridgeValidationError.UNKNOWN_PAYLOAD_FIELD,
            GlafiraBridgeProtocol.validate(invalid, nowEpochMs = 1_050_000L)
        )
    }

    @Test
    fun conversationAppendRequiresOnlyItsDeclaredFields() {
        val invalid = request(
            operation = GlafiraBridgeOperation.CONVERSATION_APPEND,
            payload = mapOf("conversationId" to "conversation-01")
        )
        assertEquals(
            GlafiraBridgeValidationError.MISSING_PAYLOAD_FIELD,
            GlafiraBridgeProtocol.validate(invalid, nowEpochMs = 1_050_000L)
        )

        val valid = invalid.copy(
            payload = mapOf(
                "conversationId" to "conversation-01",
                "messageText" to "Проверь статус проекта"
            )
        )
        assertNull(GlafiraBridgeProtocol.validate(valid, nowEpochMs = 1_050_000L))
    }

    @Test
    fun rejectsMalformedIdentifiersAndEmptyValues() {
        val malformed = request().copy(deviceId = "bad id")
        assertEquals(
            GlafiraBridgeValidationError.INVALID_IDENTIFIER,
            GlafiraBridgeProtocol.validate(malformed, nowEpochMs = 1_050_000L)
        )
        val empty = request(
            operation = GlafiraBridgeOperation.CONVERSATION_READ,
            payload = mapOf("conversationId" to " ")
        )
        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(empty, nowEpochMs = 1_050_000L)
        )
    }
}
