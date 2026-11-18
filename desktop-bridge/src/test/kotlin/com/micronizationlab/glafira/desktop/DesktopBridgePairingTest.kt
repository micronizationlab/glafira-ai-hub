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

    @Test fun importsOwnerInvitationBundleAndCreatesPinnedEnrollment() {
        val capability = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val bundle = org.json.JSONObject().apply {
            put("format", "GLAFIRA_BRIDGE_INVITATION_V1")
            put("origin", "https://192.168.1.20:47321")
            put("ticketId", "ticket_identifier_123")
            put("pairingCapability", capability)
            put("serverCertificateSha256", "c".repeat(64))
            put("expiresAtEpochMs", 1_800_000_120_000L)
        }.toString()
        val session = DesktopBridgePairing.fromInvitationBundle(
            bundle, "GLAFIRA Desktop", setOf("project.read"), 1_800_000_000_000L
        )
        assertEquals("https://192.168.1.20:47321", session.origin)
        assertEquals("c".repeat(64), session.serverCertificateSha256)
        assertEquals("DESKTOP", session.enrollment.platform)
        assertEquals(64, session.enrollment.proofHex.length)
    }

    @Test fun strictOwnerBundleParserRejectsDuplicateUnknownAndMissingKeys() {
        val expected = setOf("format", "origin")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgeJson.parseExactObject(
                """{"format":"GLAFIRA","format":"OTHER","origin":"https://192.168.1.20"}""",
                1024,
                expected
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgeJson.parseExactObject(
                """{"format":"GLAFIRA","origin":"https://192.168.1.20","extra":true}""",
                1024,
                expected
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgeJson.parseExactObject(
                """{"format":"GLAFIRA"}""",
                1024,
                expected
            )
        }
    }

    @Test fun rejectsMalformedCapabilityAndScope() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgePairing.createEnrollmentRequest("ticket_identifier_123", "bad", "Desktop", emptySet())
        }
        val capability = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgePairing.createEnrollmentRequest("ticket_identifier_123", capability, "Desktop", setOf("../admin"))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopBridgePairing.createEnrollmentRequest("ticket_identifier_123", capability, "Desktop", setOf("unknown.scope"))
        }
    }
}
