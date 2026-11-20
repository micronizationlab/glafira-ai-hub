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

data class GlafiraBridgePairingHttpRequest(
    val ticketId: String,
    val clientNonce: String,
    val proofHex: String,
    val deviceLabel: String,
    val platform: GlafiraBridgeClientPlatform,
    val requestedScopes: Set<String>
)

/** Strict parser for the unauthenticated but one-time-proof protected pairing ingress. */
object GlafiraBridgePairingJsonCodec {
    private val requiredKeys = setOf(
        "ticketId", "clientNonce", "proofHex", "deviceLabel", "platform", "requestedScopes"
    )

    fun decode(bytes: ByteArray): GlafiraBridgePairingHttpRequest {
        require(bytes.isNotEmpty() && bytes.size <= GlafiraBridgeIngressPolicy.MAX_REQUEST_BODY_BYTES)
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val reader = JsonReader(StringReader(text))
        val root = readValue(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        require(root.isJsonObject && root.asJsonObject.keySet() == requiredKeys)
        val obj = root.asJsonObject
        fun string(name: String): String {
            val value = obj.get(name)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString
        }
        val platform = runCatching { GlafiraBridgeClientPlatform.valueOf(string("platform")) }
            .getOrElse { throw IllegalArgumentException("Unsupported Bridge client platform.") }
        val scopesJson = obj.get("requestedScopes")
        require(scopesJson != null && scopesJson.isJsonArray)
        val scopes = scopesJson.asJsonArray.map { item ->
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString)
            item.asString
        }
        require(scopes.size <= GlafiraBridgeOwnerApprovalService.MAX_REQUESTED_SCOPES)
        require(scopes.distinct().size == scopes.size)
        return GlafiraBridgePairingHttpRequest(
            ticketId = string("ticketId"),
            clientNonce = string("clientNonce"),
            proofHex = string("proofHex"),
            deviceLabel = string("deviceLabel"),
            platform = platform,
            requestedScopes = scopes.toSet()
        )
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

    fun encode(request: GlafiraBridgePairingHttpRequest): ByteArray {
        val json = JsonObject().apply {
            addProperty("ticketId", request.ticketId)
            addProperty("clientNonce", request.clientNonce)
            addProperty("proofHex", request.proofHex)
            addProperty("deviceLabel", request.deviceLabel)
            addProperty("platform", request.platform.name)
            add("requestedScopes", JsonArray().apply {
                request.requestedScopes.sorted().forEach(::add)
            })
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= GlafiraBridgeIngressPolicy.MAX_REQUEST_BODY_BYTES)
        return json
    }
}
