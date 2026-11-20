package com.micronizationlab.glafira

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeAdmissionServiceTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }

    private fun signedRequest() = GlafiraBridgeProtocol.sign(
        GlafiraBridgeRequest(
            protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
            requestId = "request-0001",
            idempotencyKey = "idempotency-0001",
            deviceId = "desktop-01",
            projectId = "project-a",
            operation = GlafiraBridgeOperation.PROJECT_READ,
            issuedAtEpochMs = 1_000L,
            expiresAtEpochMs = 120_000L,
            payload = emptyMap()
        ),
        secret
    )

    private class FakeReplayStore(private val result: BridgeReplayClaim) : GlafiraBridgeReplayStore {
        var calls = 0
        override fun claim(
            authenticatedDeviceId: String,
            idempotencyKey: String,
            expiresAtEpochMs: Long,
            nowEpochMs: Long
        ): BridgeReplayClaim {
            calls++
            return result
        }
    }

    @Test fun authenticatesClaimsReplayAndDispatchesExactlyOnce() {
        val request = signedRequest()
        val storedSecret = secret.copyOf()
        val replay = FakeReplayStore(BridgeReplayClaim.ACCEPTED)
        var dispatchCalls = 0
        val service = GlafiraBridgeAdmissionService(
            credentialProvider = object : GlafiraBridgeCredentialProvider {
                override fun load(deviceId: String) =
                    GlafiraBridgeDeviceCredential("desktop-01", storedSecret, setOf("project.read"))
            },
            replayStore = replay,
            dispatch = {
                dispatchCalls++
                GlafiraBridgeResponse(
                    GlafiraBridgeProtocol.CURRENT_VERSION,
                    it.requestId,
                    GlafiraBridgeStatus.SUCCEEDED,
                    mapOf("projectId" to it.projectId)
                )
            }
        )

        val response = service.handle(request, nowEpochMs = 2_000L)
        assertEquals(GlafiraBridgeStatus.SUCCEEDED, response.status)
        assertEquals(1, replay.calls)
        assertEquals(1, dispatchCalls)
        assertTrue(storedSecret.all { it == 0.toByte() })
    }

    @Test fun rejectsDuplicateBeforeDispatchAndDoesNotReplaySideEffects() {
        val replay = FakeReplayStore(BridgeReplayClaim.DUPLICATE)
        var dispatchCalls = 0
        val service = GlafiraBridgeAdmissionService(
            credentialProvider = object : GlafiraBridgeCredentialProvider {
                override fun load(deviceId: String) =
                    GlafiraBridgeDeviceCredential("desktop-01", secret.copyOf(), setOf("project.read"))
            },
            replayStore = replay,
            dispatch = {
                dispatchCalls++
                GlafiraBridgeResponse(1, it.requestId, GlafiraBridgeStatus.SUCCEEDED)
            }
        )
        val response = service.handle(signedRequest(), nowEpochMs = 2_000L)
        assertEquals("DUPLICATE_REQUEST", response.errorCode)
        assertEquals(0, dispatchCalls)
    }

    @Test fun rejectsInvalidHmacBeforeClaimingReplaySlot() {
        val replay = FakeReplayStore(BridgeReplayClaim.ACCEPTED)
        var dispatchCalls = 0
        val service = GlafiraBridgeAdmissionService(
            credentialProvider = GlafiraBridgeCredentialProvider {
                GlafiraBridgeDeviceCredential("desktop-01", secret.copyOf(), setOf("project.read"))
            },
            replayStore = replay,
            dispatch = {
                dispatchCalls++
                GlafiraBridgeResponse(1, it.requestId, GlafiraBridgeStatus.SUCCEEDED)
            }
        )
        val response = service.handle(signedRequest().copy(signatureHex = "00".repeat(32)), nowEpochMs = 2_000L)
        assertEquals(GlafiraBridgeAuthorizationError.AUTHENTICATION_FAILED.name, response.errorCode)
        assertEquals(0, replay.calls)
        assertEquals(0, dispatchCalls)
    }
}
