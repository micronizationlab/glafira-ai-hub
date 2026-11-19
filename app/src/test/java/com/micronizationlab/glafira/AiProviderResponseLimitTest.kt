package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class AiProviderResponseLimitTest {
    @Test
    fun readsResponseWithinLimit() {
        val result = readBoundedProviderResponse(ByteArrayInputStream("ответ".toByteArray(Charsets.UTF_8)), 64)
        assertEquals("ответ", result)
    }

    @Test
    fun acceptsResponseExactlyAtLimit() {
        val bytes = "12345678".toByteArray(Charsets.UTF_8)
        assertEquals("12345678", readBoundedProviderResponse(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun rejectsResponseOverLimit() {
        val bytes = "123456789".toByteArray(Charsets.UTF_8)
        val error = runCatching {
            readBoundedProviderResponse(ByteArrayInputStream(bytes), 8)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun makesProgressWhenStreamTemporarilyReturnsZero() {
        val source = "ok".toByteArray(Charsets.UTF_8)
        val stream = object : java.io.InputStream() {
            private val delegate = ByteArrayInputStream(source)
            private var first = true
            override fun read(): Int = delegate.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    return 0
                }
                return delegate.read(target, offset, length)
            }
        }
        assertEquals("ok", readBoundedProviderResponse(stream, 8))
    }

    @Test
    fun rejectsMalformedUtf8Response() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        val error = runCatching {
            readBoundedProviderResponse(ByteArrayInputStream(bytes), 8)
        }.exceptionOrNull()
        assertTrue(error is java.nio.charset.CharacterCodingException)
    }

    @Test
    fun rejectsNonPositiveLimit() {
        val error = runCatching {
            readBoundedProviderResponse(ByteArrayInputStream(byteArrayOf()), 0)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
