package com.micronizationlab.glafira

import com.google.gson.JsonParser
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

    private fun encodedText() = String(GlafiraBridgeRequestJsonCodec.encode(request), StandardCharsets.UTF_8)
    private fun decode(value: String) = GlafiraBridgeRequestJsonCodec.decode(value.toByteArray(StandardCharsets.UTF_8))

    @Test fun roundTripsTypedRequestAndUtf8Payload() {
        assertEquals(request, GlafiraBridgeRequestJsonCodec.decode(GlafiraBridgeRequestJsonCodec.encode(request)))
    }

    @Test fun rejectsUnknownOrMissingEnvelopeFields() {
        val unknown = JsonParser.parseString(encodedText()).asJsonObject.apply { addProperty("unexpected", "x") }.toString()
        assertThrows(IllegalArgumentException::class.java) { decode(unknown) }
        val missing = JsonParser.parseString(encodedText()).asJsonObject.apply { remove("signatureHex") }.toString()
        assertThrows(IllegalArgumentException::class.java) { decode(missing) }
    }

    @Test fun rejectsWrongFieldTypesAndUnknownOperations() {
        val wrongType = encodedText().replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\"")
        assertThrows(IllegalArgumentException::class.java) { decode(wrongType) }
        assertThrows(IllegalArgumentException::class.java) { decode(encodedText().replace("CONVERSATION_APPEND", "RUN_SHELL")) }
    }

    @Test fun rejectsNonStringPayloadValues() {
        val changed = encodedText().replace("\"messageText\":\"Привет, Глафира\"", "\"messageText\":7")
        assertThrows(IllegalArgumentException::class.java) { decode(changed) }
    }

    @Test fun rejectsDuplicateKeysAndNonIntegralNumbers() {
        val duplicate = encodedText().replaceFirst("{", "{\"protocolVersion\":1,")
        assertThrows(IllegalArgumentException::class.java) { decode(duplicate) }
        val fractional = encodedText().replace("\"issuedAtEpochMs\":1800000000000", "\"issuedAtEpochMs\":1800000000000.5")
        assertThrows(IllegalArgumentException::class.java) { decode(fractional) }
    }

    @Test fun rejectsMalformedUtf8AndOversizedInput() {
        assertThrows(Exception::class.java) { GlafiraBridgeRequestJsonCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraBridgeRequestJsonCodec.decode(ByteArray(GlafiraBridgeRequestJsonCodec.MAX_ENVELOPE_BYTES + 1))
        }
    }
}
