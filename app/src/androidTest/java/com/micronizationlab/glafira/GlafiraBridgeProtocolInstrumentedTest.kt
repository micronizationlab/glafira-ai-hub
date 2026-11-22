package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraBridgeProtocolInstrumentedTest {
    private val now = 1_730_000_000_000L

    @Test
    fun ownershipDiscoveryRequiresEmptyPayload() {
        val valid = bridgeRequest(GlafiraBridgeOperation.OWNERSHIP_TRANSFER_DISCOVER, emptyMap())
        val unexpected = bridgeRequest(
            GlafiraBridgeOperation.OWNERSHIP_TRANSFER_DISCOVER,
            mapOf("transferId" to "transfer-0001")
        )

        assertNull(GlafiraBridgeProtocol.validate(valid, now))
        assertEquals(
            GlafiraBridgeValidationError.UNKNOWN_PAYLOAD_FIELD,
            GlafiraBridgeProtocol.validate(unexpected, now)
        )
    }

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
    fun desktopTaskResultRejectsZeroFenceAndInvalidProgress() {
        val zeroFence = taskResult(validPayload().toMutableMap().apply {
            put("fencingToken", "0")
        })
        val invalidProgress = taskResult(validPayload().toMutableMap().apply {
            put("progressPercent", "101")
        })

        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(zeroFence, now)
        )
        assertEquals(
            GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE,
            GlafiraBridgeProtocol.validate(invalidProgress, now)
        )
    }

    @Test
    fun desktopTaskResultRejectsMalformedOwnershipGeneration() {
        val staleGeneration = taskResult(validPayload().toMutableMap().apply {
            put("ownerGeneration", "0")
        })
        val malformedOwner = taskResult(validPayload().toMutableMap().apply {
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

    private fun validPayload(): Map<String, String> = mapOf(
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

    private fun bridgeRequest(
        operation: GlafiraBridgeOperation,
        payload: Map<String, String>
    ) = GlafiraBridgeRequest(
        protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
        requestId = "request-0001",
        idempotencyKey = "idem-0001",
        deviceId = "device-0001",
        projectId = "project-0001",
        operation = operation,
        issuedAtEpochMs = now - 1_000L,
        expiresAtEpochMs = now + 60_000L,
        payload = payload
    )
}
