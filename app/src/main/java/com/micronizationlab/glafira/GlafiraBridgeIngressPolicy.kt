package com.micronizationlab.glafira

import java.io.ByteArrayOutputStream
import java.io.InputStream

enum class GlafiraBridgeIngressRejection {
    METHOD_NOT_ALLOWED,
    PATH_NOT_FOUND,
    UNSUPPORTED_MEDIA_TYPE,
    INVALID_CONTENT_LENGTH,
    BODY_TOO_LARGE,
    EMPTY_BODY,
    BODY_READ_FAILED
}

sealed class GlafiraBridgeIngressBodyResult {
    data class Accepted(val bytes: ByteArray) : GlafiraBridgeIngressBodyResult()
    data class Rejected(val reason: GlafiraBridgeIngressRejection) : GlafiraBridgeIngressBodyResult()
}

/**
 * Transport-independent HTTP boundary checks for the Bridge host.
 *
 * Call the remote-address rate limiter before invoking this policy. The policy never parses
 * JSON, authenticates devices, or dispatches operations; accepted bytes must still pass
 * through the typed protocol decoder and GlafiraBridgeRequestAdmission.
 */
object GlafiraBridgeIngressPolicy {
    const val REQUEST_PATH = "/bridge/v1/requests"
    const val PAIRING_PATH = "/bridge/v1/pairing"
    const val MAX_REQUEST_BODY_BYTES = 64 * 1024

    fun validateRequestLine(method: String, rawPath: String): GlafiraBridgeIngressRejection? {
        if (method != "POST") return GlafiraBridgeIngressRejection.METHOD_NOT_ALLOWED
        if (rawPath != REQUEST_PATH && rawPath != PAIRING_PATH) return GlafiraBridgeIngressRejection.PATH_NOT_FOUND
        return null
    }

    fun validateHostHeader(hostHeader: String?, expectedHost: String): Boolean =
        !hostHeader.isNullOrBlank() && hostHeader.equals(expectedHost, ignoreCase = true)

    /** HTTP Content-Length is digits-only; signs, whitespace and overflow are rejected. */
    fun parseContentLength(rawValue: String?): Long? {
        if (rawValue == null || rawValue.length !in 1..10 ||
            rawValue.any { it !in '0'..'9' }
        ) return null
        return rawValue.toLongOrNull()
    }

    fun validateContentType(contentType: String?): Boolean {
        if (contentType == null) return false
        val parts = contentType.split(';').map(String::trim)
        if (!parts.firstOrNull().equals("application/json", ignoreCase = true)) return false
        val parameters = parts.drop(1)
        // Reject duplicate charset parameters rather than accepting ambiguous HTTP metadata.
        if (parameters.size > 1) return false
        return parameters.isEmpty() || parameters.single().let { parameter ->
            parameter.equals("charset=utf-8", ignoreCase = true) ||
                parameter.equals("charset=utf8", ignoreCase = true)
        }
    }

    fun readBoundedBody(
        input: InputStream,
        declaredContentLength: Long,
        maxBytes: Int = MAX_REQUEST_BODY_BYTES
    ): GlafiraBridgeIngressBodyResult {
        if (maxBytes !in 1..MAX_REQUEST_BODY_BYTES ||
            declaredContentLength < -1L ||
            declaredContentLength > maxBytes.toLong()
        ) return GlafiraBridgeIngressBodyResult.Rejected(
            if (declaredContentLength > maxBytes.toLong()) GlafiraBridgeIngressRejection.BODY_TOO_LARGE
            else GlafiraBridgeIngressRejection.INVALID_CONTENT_LENGTH
        )

        return try {
            val output = ByteArrayOutputStream(
                if (declaredContentLength in 1..maxBytes.toLong()) declaredContentLength.toInt()
                else minOf(1024, maxBytes)
            )
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val remainingPlusOne = maxBytes - total + 1
                val read = input.read(buffer, 0, minOf(buffer.size, remainingPlusOne))
                if (read < 0) break
                if (read == 0) {
                    val single = input.read()
                    if (single < 0) break
                    if (total >= maxBytes) {
                        return GlafiraBridgeIngressBodyResult.Rejected(
                            GlafiraBridgeIngressRejection.BODY_TOO_LARGE
                        )
                    }
                    output.write(single)
                    total += 1
                    continue
                }
                if (read > maxBytes - total) {
                    return GlafiraBridgeIngressBodyResult.Rejected(
                        GlafiraBridgeIngressRejection.BODY_TOO_LARGE
                    )
                }
                output.write(buffer, 0, read)
                total += read
            }
            if (declaredContentLength >= 0L && total.toLong() != declaredContentLength) {
                return GlafiraBridgeIngressBodyResult.Rejected(
                    GlafiraBridgeIngressRejection.INVALID_CONTENT_LENGTH
                )
            }
            if (total == 0) GlafiraBridgeIngressBodyResult.Rejected(
                GlafiraBridgeIngressRejection.EMPTY_BODY
            ) else GlafiraBridgeIngressBodyResult.Accepted(output.toByteArray())
        } catch (_: Exception) {
            GlafiraBridgeIngressBodyResult.Rejected(GlafiraBridgeIngressRejection.BODY_READ_FAILED)
        }
    }
}
