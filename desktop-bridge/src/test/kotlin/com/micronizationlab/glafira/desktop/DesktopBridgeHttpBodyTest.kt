package com.micronizationlab.glafira.desktop

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopBridgeHttpBodyTest {
    @Test fun acceptsResponseAtExactLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, DesktopBridgeHttpBody.readBounded(ByteArrayInputStream(bytes), 4))
    }

    @Test fun rejectsResponseOneByteOverLimit() {
        assertThrows(IOException::class.java) {
            DesktopBridgeHttpBody.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 4)
        }
    }

    @Test fun acceptsShortResponse() {
        assertEquals(2, DesktopBridgeHttpBody.readBounded(ByteArrayInputStream(byteArrayOf(1, 2)), 8).size)
    }
}
