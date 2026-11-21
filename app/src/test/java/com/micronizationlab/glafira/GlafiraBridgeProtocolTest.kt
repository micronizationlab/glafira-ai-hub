package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlafiraBridgeProtocolTest {
    private val now = 1_730_000_000_000L

    @Test
    fun desktopTaskResultRequiresLeaseIdentityAndFencingToken() {
        val request = taskResult(
            mapOf(
                "taskId" to "task_1",
                "state" to "SUCCEEDED",
                "progressPercent" to "100",
                "progressMessage" to "completed",
                "result" to "done",
                "errorCode" to "none"
            )
        )

        assertEquals(
            GlafiraBridgeValidationError.MISSING_PAYLOAD_FIELD,
            GlafiraBridgeProtocol.validate(request, now)
        )
    }

    @Test
    fun desktopTaskResultAcceptsValidLeaseFencing() {
        val request = taskResult(
            mapOf(
                "taskId" to "task_1",
                "ownerCoreId" to "windows-core-0001",
                "ownerGeneration" to "1",
                "leaseId" to "lease-0001",
                "fencingToken" to "42",
                "state" to "SUCCEEDED",
                "progressPercent" to "100",
                "progressMessage" to "completed",
                "result" to "done",
                "errorCode" to "none"
            )
        )

        assertNull(GlafiraBridgeProtocol.validate(request, now))
    }

    @Test
    fun desktopTaskResultRejectsNonPositiveFenceAndInvalidProgress() {
        val invalidFence = taskResult(validResultPayload().toMutableMap().apply {
            put("fencingToken", "0")
        })
        val invalidProgress = taskResult(validResultPayload().toMutableMap().apply {
            put("progressPercent", "101")
        })

        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(invalidFence, now)
        )
        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(invalidProgress, now)
        )
    }

    @Test
    fun desktopTaskResultRejectsMalformedOwnershipGeneration() {
        val staleGeneration = taskResult(validResultPayload().toMutableMap().apply {
            put("ownerGeneration", "0")
        })
        val malformedOwner = taskResult(validResultPayload().toMutableMap().apply {
            put("ownerCoreId", "bad owner")
        })

        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(staleGeneration, now)
        )
        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(malformedOwner, now)
        )
    }

    private fun validResultPayload(): Map<String, String> = mapOf(
        "taskId" to "task_1",
        "ownerCoreId" to "windows-core-0001",
        "ownerGeneration" to "1",
        "leaseId" to "lease-0001",
        "fencingToken" to "42",
        "state" to "SUCCEEDED",
        "progressPercent" to "100",
        "progressMessage" to "completed",
        "result" to "done",
        "errorCode" to "none"
    )

    private fun taskResult(payload: Map<String, String>) = GlafiraBridgeRequest(
        protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
        requestId = "request-0001",
        idempotencyKey = "idem-0001",
        deviceId = "device-0001",
        projectId = "project-0001",
        operation = GlafiraBridgeOperation.DESKTOP_TASK_RESULT,
        issuedAtEpochMs = now - 1_000L,
        expiresAtEpochMs = now + 60_000L,
        payload = payload
    )
}
