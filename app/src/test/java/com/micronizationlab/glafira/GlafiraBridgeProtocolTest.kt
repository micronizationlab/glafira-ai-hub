package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeProtocolTest {
    private val secret = ByteArray(32) { (it * 3 + 7).toByte() }
    private val now = 2_000_000L

    private fun request(
        operation: GlafiraBridgeOperation = GlafiraBridgeOperation.PROJECT_READ,
        payload: Map<String, String> = emptyMap(),
        issuedAt: Long = now - 1_000L,
        expiresAt: Long = now + 60_000L
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

    @Test fun acceptsAValidSignedProjectRead() {
        val signed = GlafiraBridgeProtocol.sign(request(), secret)
        assertNull(GlafiraBridgeProtocol.validate(signed, now))
        assertTrue(GlafiraBridgeProtocol.verifySignature(signed, secret))
    }

    @Test fun acceptsTheExistingDefaultProjectIdentifier() {
        val signed = GlafiraBridgeProtocol.sign(request().copy(projectId = "default"), secret)
        assertNull(GlafiraBridgeProtocol.validate(signed, now))
    }

    @Test fun rejectsUnsupportedProtocolVersion() {
        val signed = GlafiraBridgeProtocol.sign(request().copy(protocolVersion = 99), secret)
        assertEquals(
            GlafiraBridgeValidationError.UNSUPPORTED_VERSION,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun rejectsExpiredRequest() {
        val signed = GlafiraBridgeProtocol.sign(
            request(issuedAt = now - 120_000L, expiresAt = now - 1L), secret
        )
        assertEquals(
            GlafiraBridgeValidationError.EXPIRED_REQUEST,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun rejectsRequestIssuedTooFarInFuture() {
        val signed = GlafiraBridgeProtocol.sign(
            request(issuedAt = now + 31_000L, expiresAt = now + 90_000L), secret
        )
        assertEquals(
            GlafiraBridgeValidationError.REQUEST_TOO_FAR_IN_FUTURE,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun rejectsTtlLongerThanFiveMinutes() {
        val signed = GlafiraBridgeProtocol.sign(
            request(issuedAt = now - 1L, expiresAt = now + 300_001L), secret
        )
        assertEquals(
            GlafiraBridgeValidationError.INVALID_TIME_WINDOW,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun rejectsMissingRequiredPayloadField() {
        val signed = GlafiraBridgeProtocol.sign(
            request(GlafiraBridgeOperation.CONVERSATION_READ), secret
        )
        assertEquals(
            GlafiraBridgeValidationError.MISSING_PAYLOAD_FIELD,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun acceptsDesktopTaskClaimWithoutPayload() {
        val signed = GlafiraBridgeProtocol.sign(
            request(
                GlafiraBridgeOperation.DESKTOP_TASK_CLAIM,
                mapOf("capabilities" to "analysis", "maxParallelTasks" to "1")
            ), secret
        )
        assertNull(GlafiraBridgeProtocol.validate(signed, now))
    }

    @Test fun desktopTaskResultRequiresAllBoundedFields() {
        val signed = GlafiraBridgeProtocol.sign(
            request(
                GlafiraBridgeOperation.DESKTOP_TASK_RESULT,
                mapOf("taskId" to "task-0001", "state" to "SUCCEEDED")
            ), secret
        )
        assertEquals(
            GlafiraBridgeValidationError.MISSING_PAYLOAD_FIELD,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun rejectsUnknownPayloadField() {
        val signed = GlafiraBridgeProtocol.sign(
            request(payload = mapOf("unexpected" to "value")), secret
        )
        assertEquals(
            GlafiraBridgeValidationError.UNKNOWN_PAYLOAD_FIELD,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun rejectsBlankPayloadValue() {
        val signed = GlafiraBridgeProtocol.sign(
            request(
                GlafiraBridgeOperation.CONVERSATION_READ,
                mapOf("conversationId" to " ")
            ), secret
        )
        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(signed, now)
        )
    }

    @Test fun rejectsSignatureTamperingAndWrongSecret() {
        val signed = GlafiraBridgeProtocol.sign(request(), secret)
        assertFalse(GlafiraBridgeProtocol.verifySignature(signed, ByteArray(32) { 1 }))
        assertFalse(
            GlafiraBridgeProtocol.verifySignature(
                signed.copy(payload = mapOf("unexpected" to "changed")), secret
            )
        )
    }

    @Test fun refusesShortSigningSecret() {
        val error = runCatching {
            GlafiraBridgeProtocol.sign(request(), ByteArray(31))
        }.exceptionOrNull()
        assertNotNull(error)
    }
}
