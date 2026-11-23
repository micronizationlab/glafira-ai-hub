package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionDeadlineTest {
    @Test
    fun sessionRemainsUsableUntilExactDeadline() {
        var now = 10_000_000L
        val deadline = VaultSessionDeadline(5_000_000L) { now }

        assertFalse(deadline.isExpired())
        assertEquals(5L, deadline.remainingMillis())

        now += 4_999_999L
        assertFalse(deadline.isExpired())
        assertEquals(1L, deadline.remainingMillis())

        now += 1L
        assertTrue(deadline.isExpired())
        assertEquals(0L, deadline.remainingMillis())
    }

    @Test
    fun elapsedTimeUsesMonotonicOriginAndDoesNotExtendSession() {
        var now = 50_000_000L
        val deadline = VaultSessionDeadline(3_000_000L) { now }

        now += 2_000_000L
        assertEquals(1L, deadline.remainingMillis())
        now += 2_000_000L
        assertTrue(deadline.isExpired())
    }

    @Test
    fun nonPositiveLifetimeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultSessionDeadline(0L) { 0L }
        }
    }
}
