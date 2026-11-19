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
    fun rejectsNonPositiveLimit() {
        val error = runCatching {
            readBoundedProviderResponse(ByteArrayInputStream(byteArrayOf()), 0)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
