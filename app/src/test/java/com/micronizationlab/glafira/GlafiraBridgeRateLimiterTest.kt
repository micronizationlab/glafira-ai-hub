package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeRateLimiterTest {
    @Test
    fun enforcesBurstCapacity() {
        val limiter = GlafiraBridgeRateLimiter(10, 3, 1.0, 60_000_000_000L)

        assertTrue(limiter.tryAcquire("192.168.1.10", 1L))
        assertTrue(limiter.tryAcquire("192.168.1.10", 2L))
        assertTrue(limiter.tryAcquire("192.168.1.10", 3L))
        assertFalse(limiter.tryAcquire("192.168.1.10", 4L))
    }

    @Test
    fun replenishesTokensUsingMonotonicElapsedTime() {
        val limiter = GlafiraBridgeRateLimiter(10, 2, 1.0, 60_000_000_000L)

        assertTrue(limiter.tryAcquire("device-001", 1_000_000_000L))
        assertTrue(limiter.tryAcquire("device-001", 1_000_000_001L))
        assertFalse(limiter.tryAcquire("device-001", 1_000_000_002L))
        assertTrue(limiter.tryAcquire("device-001", 2_000_000_002L))
    }

    @Test
    fun doesNotEvictActiveIdentityWhenTableIsFull() {
        val limiter = GlafiraBridgeRateLimiter(2, 1, 1.0, 100L)

        assertTrue(limiter.tryAcquire("client-a", 1L))
        assertTrue(limiter.tryAcquire("client-b", 1L))
        assertFalse(limiter.tryAcquire("client-c", 50L))
        assertEquals(2, limiter.trackedIdentityCount())
    }

    @Test
    fun evictsIdleIdentityToAdmitNewClient() {
        val limiter = GlafiraBridgeRateLimiter(1, 1, 1.0, 100L)

        assertTrue(limiter.tryAcquire("client-a", 1L))
        assertTrue(limiter.tryAcquire("client-b", 101L))
        assertEquals(1, limiter.trackedIdentityCount())
    }

    @Test
    fun clockRollbackFailsClosed() {
        val limiter = GlafiraBridgeRateLimiter(2, 2, 1.0, 1_000L)

        assertTrue(limiter.tryAcquire("client-a", 100L))
        assertFalse(limiter.tryAcquire("client-a", 99L))
        assertFalse(limiter.tryAcquire("client-a", 101L).not())
    }

    @Test
    fun invalidIdentitiesAreRejected() {
        val limiter = GlafiraBridgeRateLimiter(2, 2, 1.0, 1_000L)

        assertFalse(limiter.tryAcquire("", 100L))
        assertFalse(limiter.tryAcquire("bad\nidentity", 100L))
        assertFalse(limiter.tryAcquire("client-a", 0L))
        assertEquals(0, limiter.trackedIdentityCount())
    }
}
