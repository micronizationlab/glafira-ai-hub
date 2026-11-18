package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeRequestAdmissionTest {
    private val secret = ByteArray(32) { (it + 11).toByte() }
    private val now = 3_000_000L
    private val deviceId = "device-0001"

    private class FakeCredentials(
        private val credential: GlafiraBridgeDeviceCredential?
    ) : GlafiraBridgeCredentialProvider {
        override fun load(deviceId: String): GlafiraBridgeDeviceCredential? =
            credential?.takeIf { it.deviceId == deviceId }
    }

    private class FakeReplayStore(
        var next: BridgeReplayClaim = BridgeReplayClaim.ACCEPTED
    ) : GlafiraBridgeReplayStore {
        var calls = 0
        override fun claim(
            authenticatedDeviceId: String,
            idempotencyKey: String,
            expiresAtEpochMs: Long,
            nowEpochMs: Long
        ): BridgeReplayClaim {
            calls++
            return next
        }
    }

    private fun signedRequest(
        operation: GlafiraBridgeOperation = GlafiraBridgeOperation.PROJECT_READ,
        payload: Map<String, String> = emptyMap()
    ) = GlafiraBridgeProtocol.sign(
        GlafiraBridgeRequest(
            protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
            requestId = "request-0001",
            idempotencyKey = "idem-000001",
            deviceId = deviceId,
            projectId = "project-001",
            operation = operation,
            issuedAtEpochMs = now - 1_000L,
            expiresAtEpochMs = now + 60_000L,
            payload = payload
        ),
        secret
    )

    private fun credential(scopes: Set<String> = setOf("project.read")) =
        GlafiraBridgeDeviceCredential(deviceId, secret, scopes)

    @Test fun rejectsUnpairedDeviceBeforeReplayClaim() {
        val replay = FakeReplayStore()
        val admission = GlafiraBridgeRequestAdmission(FakeCredentials(null), replay)
        val response = admission.admit(signedRequest(), now)
        assertEquals("DEVICE_NOT_PAIRED", response.errorCode)
        assertEquals(0, replay.calls)
    }

    @Test fun acceptsAuthorizedRequestOnlyAfterReplayClaim() {
        val replay = FakeReplayStore(BridgeReplayClaim.ACCEPTED)
        val admission = GlafiraBridgeRequestAdmission(FakeCredentials(credential()), replay)
        val response = admission.admit(signedRequest(), now)
        assertEquals(GlafiraBridgeStatus.ACCEPTED, response.status)
        assertEquals(1, replay.calls)
    }

    @Test fun rejectsDuplicateRequest() {
        val replay = FakeReplayStore(BridgeReplayClaim.DUPLICATE)
        val admission = GlafiraBridgeRequestAdmission(FakeCredentials(credential()), replay)
        val response = admission.admit(signedRequest(), now)
        assertEquals("DUPLICATE_REQUEST", response.errorCode)
    }

    @Test fun failsClosedWhenReplayStorageFails() {
        val replay = FakeReplayStore(BridgeReplayClaim.STORAGE_ERROR)
        val admission = GlafiraBridgeRequestAdmission(FakeCredentials(credential()), replay)
        val response = admission.admit(signedRequest(), now)
        assertEquals("REPLAY_GUARD_STORAGE_ERROR", response.errorCode)
    }

    @Test fun doesNotClaimReplayKeyWhenCoreAuthorizationDeniesScope() {
        val replay = FakeReplayStore()
        val admission = GlafiraBridgeRequestAdmission(
            FakeCredentials(credential(emptySet())), replay
        )
        val response = admission.admit(signedRequest(), now)
        assertEquals("SCOPE_DENIED", response.errorCode)
        assertEquals(0, replay.calls)
    }

    @Test fun approvalRequestFailsClosedUntilDurableOwnerQueueExists() {
        val replay = FakeReplayStore(BridgeReplayClaim.ACCEPTED)
        val admission = GlafiraBridgeRequestAdmission(
            FakeCredentials(credential(setOf("approval.request"))), replay
        )
        val request = signedRequest(
            GlafiraBridgeOperation.APPROVAL_REQUEST,
            mapOf("toolId" to "message.send", "actionSummary" to "Send a reply")
        )
        val response = admission.admit(request, now)
        assertEquals(GlafiraBridgeStatus.FAILED, response.status)
        assertEquals("APPROVAL_QUEUE_UNAVAILABLE", response.errorCode)
        assertEquals(0, replay.calls)
    }
}
