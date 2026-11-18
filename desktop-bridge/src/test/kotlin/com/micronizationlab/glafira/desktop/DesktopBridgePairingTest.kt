package com.micronizationlab.glafira.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DesktopBridgePairingTest {
    @Test fun createsOneTimeProofWithoutSerializingCapability() {
        val capability = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val request = DesktopBridgePairing.createEnrollmentRequest(
            ticketId = "ticket_identifier_123",
            pairingCapability = capability,
            deviceLabel = "GLAFIRA Desktop",
            requestedScopes = setOf("project.read", "conversation.read")
        )
        assertEquals(64, request.proofHex.length)
        assertEquals(43, request.clientNonce.length)
        assertEquals("DESKTOP", request.platform)
        val json = String(request.toJsonBytes(), Charsets.UTF_8)
        assertTrue(json.contains(request.proofHex))
        assertFalse(json.contains(capability))
        assertTrue(json.contains("project.read"))
    }

    @Test fun rejectsMalformedCapabilityAndScope() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgePairing.createEnrollmentRequest("ticket_identifier_123", "bad", "Desktop", emptySet())
        }
        val capability = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgePairing.createEnrollmentRequest("ticket_identifier_123", capability, "Desktop", setOf("../admin"))
        }
    }
}
