package com.micronizationlab.glafira

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Typed Bridge envelope. It is not a network listener or a device-pairing implementation. */
enum class GlafiraBridgeOperation { PROJECT_READ, CONVERSATION_READ, CONVERSATION_APPEND, APPROVAL_REQUEST }
enum class GlafiraBridgeStatus { ACCEPTED, PENDING_APPROVAL, SUCCEEDED, FAILED, CANCELLED }

data class GlafiraBridgeRequest(
    val protocolVersion: Int,
    val requestId: String,
    val idempotencyKey: String,
    val deviceId: String,
    val projectId: String,
    val operation: GlafiraBridgeOperation,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val payload: Map<String, String>,
    val signatureHex: String = ""
)

data class GlafiraBridgeResponse(
    val protocolVersion: Int,
    val requestId: String,
    val status: GlafiraBridgeStatus,
    val result: Map<String, String> = emptyMap(),
    val errorCode: String? = null
)

enum class GlafiraBridgeValidationError {
    UNSUPPORTED_VERSION, INVALID_IDENTIFIER, INVALID_TIME_WINDOW, EXPIRED_REQUEST,
    REQUEST_TOO_FAR_IN_FUTURE, PAYLOAD_TOO_LARGE, UNKNOWN_PAYLOAD_FIELD,
    MISSING_PAYLOAD_FIELD, INVALID_PAYLOAD_VALUE
}

object GlafiraBridgeProtocol {
    const val CURRENT_VERSION = 1
    private const val MAX_TTL_MS = 5 * 60 * 1000L
    private const val MAX_FUTURE_SKEW_MS = 30 * 1000L
    private const val MAX_PAYLOAD_CHARS = 16_384

    private val requiredFields = mapOf(
        GlafiraBridgeOperation.PROJECT_READ to emptySet(),
        GlafiraBridgeOperation.CONVERSATION_READ to setOf("conversationId"),
        GlafiraBridgeOperation.CONVERSATION_APPEND to setOf("conversationId", "messageText"),
        GlafiraBridgeOperation.APPROVAL_REQUEST to setOf("toolId", "actionSummary")
    )
    private val allowedFields = requiredFields

    fun sign(request: GlafiraBridgeRequest, deviceSecret: ByteArray): GlafiraBridgeRequest {
        require(deviceSecret.size >= 32) { "Device secret must contain at least 256 bits." }
        return request.copy(signatureHex = hmacSha256(canonicalBytes(request), deviceSecret).toHex())
    }

    fun verifySignature(request: GlafiraBridgeRequest, deviceSecret: ByteArray): Boolean {
        if (deviceSecret.size < 32 || request.signatureHex.length != 64) return false
        val supplied = runCatching { request.signatureHex.hexToBytes() }.getOrNull() ?: return false
        val expected = hmacSha256(canonicalBytes(request.copy(signatureHex = "")), deviceSecret)
        return MessageDigest.isEqual(expected, supplied)
    }

    private fun canonicalBytes(request: GlafiraBridgeRequest): ByteArray {
        val fields = buildList {
            add(request.protocolVersion.toString())
            add(request.requestId)
            add(request.idempotencyKey)
            add(request.deviceId)
            add(request.projectId)
            add(request.operation.name)
            add(request.issuedAtEpochMs.toString())
            add(request.expiresAtEpochMs.toString())
            request.payload.toSortedMap().forEach { (key, value) -> add(key); add(value) }
        }
        return fields.joinToString(separator = "") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    }

    private fun hmacSha256(data: ByteArray, secret: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data)
    }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0 && all { it in "0123456789abcdefABCDEF" })
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun validate(request: GlafiraBridgeRequest, nowEpochMs: Long = System.currentTimeMillis()): GlafiraBridgeValidationError? {
        if (request.protocolVersion != CURRENT_VERSION) return GlafiraBridgeValidationError.UNSUPPORTED_VERSION
        if (!isIdentifier(request.requestId) || !isIdentifier(request.idempotencyKey) ||
            !isIdentifier(request.deviceId) || !isIdentifier(request.projectId)
        ) return GlafiraBridgeValidationError.INVALID_IDENTIFIER
        val ttl = request.expiresAtEpochMs - request.issuedAtEpochMs
        if (request.issuedAtEpochMs <= 0L || request.expiresAtEpochMs <= request.issuedAtEpochMs ||
            ttl > MAX_TTL_MS
        ) return GlafiraBridgeValidationError.INVALID_TIME_WINDOW
        if (request.issuedAtEpochMs > nowEpochMs + MAX_FUTURE_SKEW_MS)
            return GlafiraBridgeValidationError.REQUEST_TOO_FAR_IN_FUTURE
        if (request.expiresAtEpochMs <= nowEpochMs) return GlafiraBridgeValidationError.EXPIRED_REQUEST
        if (request.payload.entries.sumOf { it.key.length + it.value.length } > MAX_PAYLOAD_CHARS)
            return GlafiraBridgeValidationError.PAYLOAD_TOO_LARGE
        val allowed = allowedFields.getValue(request.operation)
        if (request.payload.keys.any { it !in allowed }) return GlafiraBridgeValidationError.UNKNOWN_PAYLOAD_FIELD
        if (!request.payload.keys.containsAll(requiredFields.getValue(request.operation)))
            return GlafiraBridgeValidationError.MISSING_PAYLOAD_FIELD
        if (request.payload.values.any { it.isBlank() || it.length > MAX_PAYLOAD_CHARS })
            return GlafiraBridgeValidationError.INVALID_PAYLOAD_VALUE
        return null
    }

    private fun isIdentifier(value: String): Boolean =
        value.length in 8..128 && value.all { it.isLetterOrDigit() || it in "-_." }
}
