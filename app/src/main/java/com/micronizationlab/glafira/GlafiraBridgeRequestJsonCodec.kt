package com.micronizationlab.glafira

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONException
import org.json.JSONObject

/**
 * Strict JSON boundary codec for the paired-request HTTP route.
 *
 * Authentication and policy checks are deliberately not performed here. Every decoded
 * envelope must be passed to GlafiraBridgeRequestAdmission before any operation dispatch.
 */
object GlafiraBridgeRequestJsonCodec {
    const val MAX_ENVELOPE_BYTES = GlafiraBridgeIngressPolicy.MAX_REQUEST_BODY_BYTES

    private val requiredKeys = setOf(
        "protocolVersion", "requestId", "idempotencyKey", "deviceId", "projectId",
        "operation", "issuedAtEpochMs", "expiresAtEpochMs", "payload", "signatureHex"
    )

    fun decode(bytes: ByteArray): GlafiraBridgeRequest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENVELOPE_BYTES) {
            "Bridge request envelope size is invalid."
        }
        val json = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

        val root = JSONObject(json)
        require(root.keySet() == requiredKeys) { "Bridge request fields are missing or unknown." }

        val version = root.requiredNumber("protocolVersion").toIntExact()
        val issuedAt = root.requiredNumber("issuedAtEpochMs").toLongExact()
        val expiresAt = root.requiredNumber("expiresAtEpochMs").toLongExact()
        val operationName = root.requiredString("operation")
        val operation = GlafiraBridgeOperation.entries.firstOrNull { it.name == operationName }
            ?: throw IllegalArgumentException("Bridge operation is unsupported.")

        val payloadObject = root.opt("payload") as? JSONObject
            ?: throw IllegalArgumentException("Bridge payload must be a JSON object.")
        val payload = buildMap {
            val keys = payloadObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = payloadObject.opt(key) as? String
                    ?: throw IllegalArgumentException("Bridge payload values must be strings.")
                put(key, value)
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
        val payload = JSONObject()
        request.payload.forEach { (key, value) -> payload.put(key, value) }
        val json = JSONObject().apply {
            put("protocolVersion", request.protocolVersion)
            put("requestId", request.requestId)
            put("idempotencyKey", request.idempotencyKey)
            put("deviceId", request.deviceId)
            put("projectId", request.projectId)
            put("operation", request.operation.name)
            put("issuedAtEpochMs", request.issuedAtEpochMs)
            put("expiresAtEpochMs", request.expiresAtEpochMs)
            put("payload", payload)
            put("signatureHex", request.signatureHex)
        }.toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_ENVELOPE_BYTES) { "Bridge request envelope exceeds the size limit." }
        return bytes
    }

    private fun JSONObject.requiredString(key: String): String =
        opt(key) as? String ?: throw IllegalArgumentException("Bridge field '$key' must be a string.")

    private fun JSONObject.requiredNumber(key: String): Number =
        opt(key) as? Number ?: throw IllegalArgumentException("Bridge field '$key' must be a number.")

    private fun Number.toIntExact(): Int {
        val value = toLongExact()
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Bridge integer is out of range." }
        return value.toInt()
    }

    private fun Number.toLongExact(): Long {
        val value = toDouble()
        require(value.isFinite() && value >= Long.MIN_VALUE.toDouble() &&
            value <= Long.MAX_VALUE.toDouble() && value % 1.0 == 0.0
        ) { "Bridge number must be an exact integer." }
        return toLong()
    }
}
