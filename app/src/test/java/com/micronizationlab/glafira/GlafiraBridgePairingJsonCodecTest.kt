package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraBridgePairingJsonCodecTest {
    private fun decode(value: String) = GlafiraBridgePairingJsonCodec.decode(value.toByteArray(Charsets.UTF_8))

    private val request = GlafiraBridgePairingHttpRequest(
        ticketId = "ticket_identifier_123",
        clientNonce = "A".repeat(43),
        proofHex = "a".repeat(64),
        deviceLabel = "GLAFIRA Desktop",
        platform = GlafiraBridgeClientPlatform.DESKTOP,
        requestedScopes = setOf("project.read", "conversation.read")
    )

    @Test fun roundTripsPairingRequest() {
        val decoded = GlafiraBridgePairingJsonCodec.decode(GlafiraBridgePairingJsonCodec.encode(request))
        assertEquals(request, decoded)
    }

    @Test fun rejectsDuplicateUnknownAndMissingFields() {
        val encoded = String(GlafiraBridgePairingJsonCodec.encode(request), Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            decode(encoded.replaceFirst("{", "{\"ticketId\":\"duplicate_value\","))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(encoded.replace("\"platform\":\"DESKTOP\",", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(encoded.replace("}", ",\"unexpected\":true}"))
        }
    }

    @Test fun rejectsWrongTypesAndDuplicateScopes() {
        val encoded = String(GlafiraBridgePairingJsonCodec.encode(request), Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            decode(encoded.replace("\"deviceLabel\":\"GLAFIRA Desktop\"", "\"deviceLabel\":17"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(encoded.replace("[\"conversation.read\",\"project.read\"]", "[\"project.read\",\"project.read\"]"))
        }
    }
}
