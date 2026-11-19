package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class GlafiraDurableBridgeAdmissionTest {
    private val secret = ByteArray(32) { 7 }
    private val scopes = setOf("project.read", "conversation.read", "conversation.append")

    private fun request(operation: GlafiraBridgeOperation = GlafiraBridgeOperation.PROJECT_READ): GlafiraBridgeRequest {
        val payload = when (operation) {
            GlafiraBridgeOperation.CONVERSATION_READ -> mapOf("conversationId" to "conversation-001")
            GlafiraBridgeOperation.CONVERSATION_APPEND -> mapOf("conversationId" to "conversation-001", "messageText" to "append once")
            GlafiraBridgeOperation.TASK_STATUS -> mapOf("taskId" to "bridge-task-001", "resultOffset" to "0")
            else -> emptyMap()
        }
        return GlafiraBridgeProtocol.sign(GlafiraBridgeRequest(1, "request-001", "operation-001",
            "device-001", "project-001", operation, 1_000, 61_000, payload), secret)
    }

    private class Replay : GlafiraBridgeReplayStore {
        var claims = 0
        override fun claim(authenticatedDeviceId: String, idempotencyKey: String,
            expiresAtEpochMs: Long, nowEpochMs: Long): BridgeReplayClaim =
            if (claims++ == 0) BridgeReplayClaim.ACCEPTED else BridgeReplayClaim.DUPLICATE
    }

    private fun admission(replay: Replay, allowedScopes: Set<String> = scopes,
        paired: Boolean = true, credentialDevice: String = "device-001") = GlafiraBridgeRequestAdmission(
        object : GlafiraBridgeCredentialProvider {
            override fun load(deviceId: String): GlafiraBridgeDeviceCredential? =
                if (paired) GlafiraBridgeDeviceCredential(credentialDevice, secret.copyOf(), allowedScopes) else null
        }, replay
    )

    @Test fun onlyThreeEnqueueOperationsCanAvoidTheSeparateReplayLedger() {
        val replay = Replay()
        val gate = admission(replay)
        for (operation in GlafiraBridgeOperation.entries) {
            if (GlafiraBridgeDurableTaskIdentity.supports(operation)) {
                repeat(2) {
                    assertEquals(GlafiraBridgeStatus.ACCEPTED,
                        gate.admitDurableTask(request(operation), 2_000).status)
                }
            } else {
                assertEquals("DURABLE_CORE_OPERATION_REQUIRED",
                    gate.admitDurableTask(request(operation), 2_000).errorCode)
            }
        }
        assertEquals(0, replay.claims)
    }

    @Test fun durableRetriesStillRequirePairingHmacExpiryScopesAndRateLimit() {
        val replay = Replay()
        val original = request()
        assertEquals("DEVICE_NOT_PAIRED", admission(replay, paired = false)
            .admitDurableTask(original, 2_000).errorCode)
        assertEquals("DEVICE_MISMATCH", admission(replay, credentialDevice = "other-device-001")
            .admitDurableTask(original, 2_000).errorCode)
        assertEquals("AUTHENTICATION_FAILED", admission(replay)
            .admitDurableTask(original.copy(signatureHex = "0".repeat(64)), 2_000).errorCode)
        assertEquals("INVALID_REQUEST", admission(replay).admitDurableTask(original, 61_000).errorCode)
        assertEquals("SCOPE_DENIED", admission(replay, allowedScopes = emptySet())
            .admitDurableTask(original, 2_000).errorCode)
        assertEquals("DEVICE_RATE_LIMITED", admission(replay)
            .admitDurableTask(original, 2_000) { false }.errorCode)
        assertEquals(0, replay.claims)
    }

    @Test fun regularControlAdmissionKeepsItsReplayProtection() {
        val replay = Replay()
        val gate = admission(replay)
        val query = request(GlafiraBridgeOperation.TASK_STATUS)
        assertEquals(GlafiraBridgeStatus.ACCEPTED, gate.admit(query, 2_000).status)
        assertEquals("DUPLICATE_REQUEST", gate.admit(query, 2_000).errorCode)
        assertEquals(2, replay.claims)
    }
}
