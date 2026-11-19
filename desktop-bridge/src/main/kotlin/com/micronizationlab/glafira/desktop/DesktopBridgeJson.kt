package com.micronizationlab.glafira.desktop

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal

/** Strict bounded JSON object parser for owner-copied Desktop enrollment bundles. */
internal object DesktopBridgeJson {
    fun parseExactObject(
        source: String,
        maxBytes: Int,
        expectedKeys: Set<String>
    ): org.json.JSONObject {
        require(maxBytes in 1..(64 * 1024))
        // Reject oversized character input before allocating a UTF-8 copy of the whole string.
        require(source.length in 1..maxBytes)
        require(source.toByteArray(Charsets.UTF_8).size <= maxBytes)
        val reader = JsonReader(StringReader(source)).apply { setStrictness(Strictness.STRICT) }
        val root = readValue(reader, 0)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        require(root.isJsonObject)
        val actual = root.asJsonObject.keySet()
        require(actual == expectedKeys)
        return org.json.JSONObject(root.toString())
    }

    fun stringField(json: org.json.JSONObject, key: String): String {
        val value = json.get(key)
        require(value is String)
        return value
    }

    fun longFieldExact(json: org.json.JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Number)
        return try {
            BigDecimal(value.toString()).longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("JSON field must be an exact integer.", error)
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= MAX_DEPTH)
        return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val result = JsonObject()
            val seen = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                require(seen.size < MAX_OBJECT_FIELDS)
                val name = reader.nextName()
                require(seen.add(name)) { "Duplicate JSON object key." }
                result.add(name, readValue(reader, depth + 1))
            }
            reader.endObject()
            result
        }
        JsonToken.BEGIN_ARRAY -> {
            val result = JsonArray()
            reader.beginArray()
            while (reader.hasNext()) {
                require(result.size() < MAX_ARRAY_ITEMS)
                result.add(readValue(reader, depth + 1))
            }
            reader.endArray()
            result
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw IllegalArgumentException("Unexpected JSON token.")
        }
    }

    private const val MAX_DEPTH = 8
    private const val MAX_OBJECT_FIELDS = 16
    private const val MAX_ARRAY_ITEMS = 32
}
