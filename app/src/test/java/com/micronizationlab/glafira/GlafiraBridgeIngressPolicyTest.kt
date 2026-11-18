package com.micronizationlab.glafira

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeIngressPolicyTest {
    @Test
    fun acceptsOnlyFixedPostRoute() {
        assertEquals(null, GlafiraBridgeIngressPolicy.validateRequestLine(
            "POST", GlafiraBridgeIngressPolicy.REQUEST_PATH
        ))
        assertEquals(
            GlafiraBridgeIngressRejection.METHOD_NOT_ALLOWED,
            GlafiraBridgeIngressPolicy.validateRequestLine("GET", GlafiraBridgeIngressPolicy.REQUEST_PATH)
        )
        assertEquals(
            GlafiraBridgeIngressRejection.PATH_NOT_FOUND,
            GlafiraBridgeIngressPolicy.validateRequestLine("POST", "/bridge/v1/requests/extra")
        )
    }

    @Test
    fun acceptsJsonAndUtf8Only() {
        assertTrue(GlafiraBridgeIngressPolicy.validateContentType("application/json"))
        assertTrue(GlafiraBridgeIngressPolicy.validateContentType("Application/JSON; charset=UTF-8"))
        assertFalse(GlafiraBridgeIngressPolicy.validateContentType("text/plain"))
        assertFalse(GlafiraBridgeIngressPolicy.validateContentType("application/json; charset=utf-16"))
        assertFalse(GlafiraBridgeIngressPolicy.validateContentType(
            "application/json; charset=utf-8; charset=utf-8"
        ))
        assertFalse(GlafiraBridgeIngressPolicy.validateContentType(null))
    }

    @Test
    fun readsBodyWithUnknownLengthButEnforcesHardLimit() {
        val payload = "{\"requestId\":\"r1\"}".toByteArray()
        val result = GlafiraBridgeIngressPolicy.readBoundedBody(
            ByteArrayInputStream(payload), -1L, 128
        )
        assertTrue(result is GlafiraBridgeIngressBodyResult.Accepted)
        assertArrayEquals(payload, (result as GlafiraBridgeIngressBodyResult.Accepted).bytes)
    }

    @Test
    fun rejectsDeclaredOversizeBeforeReading() {
        val result = GlafiraBridgeIngressPolicy.readBoundedBody(
            ByteArrayInputStream(byteArrayOf(1)), 129L, 128
        )
        assertEquals(
            GlafiraBridgeIngressRejection.BODY_TOO_LARGE,
            (result as GlafiraBridgeIngressBodyResult.Rejected).reason
        )
    }

    @Test
    fun rejectsChunkedOrUnknownLengthBodyThatExceedsLimit() {
        val result = GlafiraBridgeIngressPolicy.readBoundedBody(
            ByteArrayInputStream(ByteArray(129)), -1L, 128
        )
        assertEquals(
            GlafiraBridgeIngressRejection.BODY_TOO_LARGE,
            (result as GlafiraBridgeIngressBodyResult.Rejected).reason
        )
    }

    @Test
    fun rejectsDeclaredLengthMismatch() {
        val result = GlafiraBridgeIngressPolicy.readBoundedBody(
            ByteArrayInputStream(byteArrayOf(1, 2)), 3L, 128
        )
        assertEquals(
            GlafiraBridgeIngressRejection.INVALID_CONTENT_LENGTH,
            (result as GlafiraBridgeIngressBodyResult.Rejected).reason
        )
    }

    @Test
    fun makesProgressWhenStreamReturnsZeroForBulkRead() {
        val delegate = ByteArrayInputStream(byteArrayOf(4, 5, 6))
        var first = true
        val unusual = object : InputStream() {
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }
        val result = GlafiraBridgeIngressPolicy.readBoundedBody(unusual, -1L, 128)
        assertTrue(result is GlafiraBridgeIngressBodyResult.Accepted)
        assertArrayEquals(byteArrayOf(4, 5, 6), (result as GlafiraBridgeIngressBodyResult.Accepted).bytes)
    }

    @Test
    fun rejectsEmptyBodyAndInvalidLength() {
        val empty = GlafiraBridgeIngressPolicy.readBoundedBody(
            ByteArrayInputStream(byteArrayOf()), 0L, 128
        )
        assertEquals(
            GlafiraBridgeIngressRejection.EMPTY_BODY,
            (empty as GlafiraBridgeIngressBodyResult.Rejected).reason
        )
        val invalid = GlafiraBridgeIngressPolicy.readBoundedBody(
            ByteArrayInputStream(byteArrayOf()), -2L, 128
        )
        assertEquals(
            GlafiraBridgeIngressRejection.INVALID_CONTENT_LENGTH,
            (invalid as GlafiraBridgeIngressBodyResult.Rejected).reason
        )
    }

    @Test
    fun convertsStreamFailureToClosedRejection() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IllegalStateException("stream failure")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IllegalStateException("stream failure")
        }
        val result = GlafiraBridgeIngressPolicy.readBoundedBody(failing, -1L, 128)
        assertEquals(
            GlafiraBridgeIngressRejection.BODY_READ_FAILED,
            (result as GlafiraBridgeIngressBodyResult.Rejected).reason
        )
    }
}
