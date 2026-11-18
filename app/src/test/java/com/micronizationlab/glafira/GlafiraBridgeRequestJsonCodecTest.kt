package com.micronizationlab.glafira

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraBridgeRequestJsonCodecTest {
    private val request = GlafiraBridgeRequest(
        protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
        requestId = "request-0001",
        idempotencyKey = "idempotency-01",
        deviceId = "device-0001",
        projectId = "project-0001",
        operation = GlafiraBridgeOperation.CONVERSATION_APPEND,
        issuedAtEpochMs = 1_800_000_000_000L,
        expiresAtEpochMs = 1_800_000_060_000L,
        payload = mapOf("conversationId" to "conversation-01", "messageText" to "Привет, Глафира"),
        signatureHex = "a".repeat(64)
    )

    @Test
    fun roundTripsTypedRequestAndUtf8Payload() {
        assertEquals(request, GlafiraBridgeRequestJsonCodec.decode(
            GlafiraBridgeRequestJsonCodec.encode(request)
        ))
    }

    @Test
    fun rejectsUnknownOrMissingEnvelopeFields() {
        val encoded = String(GlafiraBridgeRequestJsonCodec.encode(request), StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(encoded.replace("\"signatureHex\"", "\"unexpected\":\"x\",\"signatureHex\"")
                .toByteArray(StandardCharsets.UTF_8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(encoded.replace(",\"signatureHex\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"", "")
                .toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun rejectsWrongFieldTypesAndUnknownOperations() {
        val encoded = String(GlafiraBridgeRequestJsonCodec.encode(request), StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(encoded.replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\"")
                .toByteArray(StandardCharsets.UTF_8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(encoded.replace("CONVERSATION_APPEND", "RUN_SHELL")
                .toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun rejectsNonStringPayloadValues() {
        val encoded = String(GlafiraBridgeRequestJsonCodec.encode(request), StandardCharsets.UTF_8)
        val changed = encoded.replace("\"messageText\":\"Привет, Глафира\"", "\"messageText\":7")
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(changed.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun rejectsMalformedUtf8AndOversizedInput() {
        assertThrows(Exception::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(ByteArray(GlafiraBridgeRequestJsonCodec.MAX_ENVELOPE_BYTES + 1))
        }
    }
}
