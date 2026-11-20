package com.micronizationlab.glafira

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Strict JSON boundary codec for the paired-request HTTP route.
 * Decoding does not authenticate or authorize; every envelope must pass Admission.
 */
object GlafiraBridgeRequestJsonCodec {
    const val MAX_ENVELOPE_BYTES = GlafiraBridgeIngressPolicy.MAX_REQUEST_BODY_BYTES
    private val requiredKeys = setOf(
        "protocolVersion", "requestId", "idempotencyKey", "deviceId", "projectId",
        "operation", "issuedAtEpochMs", "expiresAtEpochMs", "payload", "signatureHex"
    )

    fun decode(bytes: ByteArray): GlafiraBridgeRequest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENVELOPE_BYTES) { "Bridge request envelope size is invalid." }
        val json = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val reader = JsonReader(StringReader(json))
        val root = readValue(reader).asJsonObject
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content is not allowed." }
        require(root.keySet() == requiredKeys) { "Bridge request fields are missing or unknown." }

        val version = root.requiredNumber("protocolVersion").intExact()
        val issuedAt = root.requiredNumber("issuedAtEpochMs").longExact()
        val expiresAt = root.requiredNumber("expiresAtEpochMs").longExact()
        val operationName = root.requiredString("operation")
        val operation = GlafiraBridgeOperation.entries.firstOrNull { it.name == operationName }
            ?: throw IllegalArgumentException("Bridge operation is unsupported.")
        val payloadJson = root.get("payload")
        require(payloadJson != null && payloadJson.isJsonObject) { "Bridge payload must be a JSON object." }
        val payload = buildMap {
            payloadJson.asJsonObject.entrySet().forEach { (key, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "Bridge payload values must be strings."
                }
                put(key, value.asString)
            }
        }
        return GlafiraBridgeRequest(
            protocolVersion = version,
            requestId = root.requiredString("requestId"),
            idempotencyKey = root.requiredString("idempotencyKey"),
            deviceId = root.requiredString("deviceId"),
            projectId = root.requiredString("projectId"),
            operation = operation,
            issuedAtEpochMs = issuedAt,
            expiresAtEpochMs = expiresAt,
            payload = payload,
            signatureHex = root.requiredString("signatureHex")
        )
    }

    fun encode(request: GlafiraBridgeRequest): ByteArray {
        val payload = JsonObject().apply { request.payload.forEach { (key, value) -> addProperty(key, value) } }
        val json = JsonObject().apply {
            addProperty("protocolVersion", request.protocolVersion)
            addProperty("requestId", request.requestId)
            addProperty("idempotencyKey", request.idempotencyKey)
            addProperty("deviceId", request.deviceId)
            addProperty("projectId", request.projectId)
            addProperty("operation", request.operation.name)
            addProperty("issuedAtEpochMs", request.issuedAtEpochMs)
            addProperty("expiresAtEpochMs", request.expiresAtEpochMs)
            add("payload", payload)
            addProperty("signatureHex", request.signatureHex)
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_ENVELOPE_BYTES) { "Bridge request envelope exceeds the size limit." }
        return json
    }

    private fun readValue(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val result = JsonObject()
            val seen = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(seen.add(name)) { "Duplicate JSON object key." }
                result.add(name, readValue(reader))
            }
            reader.endObject()
            result
        }
        JsonToken.BEGIN_ARRAY -> {
            val result = JsonArray()
            reader.beginArray()
            while (reader.hasNext()) result.add(readValue(reader))
            reader.endArray()
            result
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw IllegalArgumentException("Unexpected JSON token.")
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Bridge field '$key' must be a string."
        }
        return value.asString
    }

    private fun JsonObject.requiredNumber(key: String): BigDecimal {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "Bridge field '$key' must be a number."
        }
        return value.asBigDecimal
    }

    private fun BigDecimal.intExact(): Int =
        try { intValueExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Bridge integer must be an exact 32-bit value.")
        }

    private fun BigDecimal.longExact(): Long =
        try { longValueExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Bridge integer must be an exact 64-bit value.")
        }
}
