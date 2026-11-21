package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingMessageOutcomePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newStore() = PendingMessageStore(temporaryFolder.newFolder(), "test-session")

    private fun message(id: String) = PendingMessage(
        projectId = "provider-project",
        agentName = "ГЛАФИРА",
        text = "synthetic provider request",
        time = "12:00",
        id = id
    )

    @Test
    fun unknownOutcomeRemainsUncertainEvenIfRetryFlagIsAccidentallyTrue() {
        val store = newStore()
        store.add(message("unknown-provider-outcome"))
        assertNotNull(store.claim("unknown-provider-outcome", now = 1_000L))

        store.failed(
            id = "unknown-provider-outcome",
            message = "provider outcome unknown",
            retryable = true,
            unknown = true,
            retryAfterMillis = 1_000L,
            partialResult = "completed peer result"
        )

        val uncertain = store.all().single()
        assertEquals(PendingDeliveryState.UNCERTAIN, uncertain.state)
        assertEquals("completed peer result", uncertain.result)
        assertNull(store.claim("unknown-provider-outcome", now = Long.MAX_VALUE))

        // Re-dispatch is possible only after an explicit owner action.
        store.retry("unknown-provider-outcome")
        assertEquals(PendingDeliveryState.QUEUED, store.all().single().state)
        assertNotNull(store.claim("unknown-provider-outcome", now = Long.MAX_VALUE))
    }

    @Test
    fun explicitlyRejectedRateLimitUsesRetryAfterWithoutBecomingUnknown() {
        val store = newStore()
        store.add(message("rate-limited-provider"))
        assertNotNull(store.claim("rate-limited-provider", now = 1_000L))

        val beforeFailure = System.currentTimeMillis()
        store.failed(
            id = "rate-limited-provider",
            message = "provider rejected request with rate limit",
            retryable = true,
            unknown = false,
            retryAfterMillis = 60_000L
        )

        val queued = store.all().single()
        assertEquals(PendingDeliveryState.QUEUED, queued.state)
        assertTrue(queued.nextAttemptAt >= beforeFailure + 60_000L)
        assertNull(store.claim("rate-limited-provider", now = beforeFailure))
        assertNotNull(store.claim("rate-limited-provider", now = queued.nextAttemptAt))
    }

    @Test
    fun rejectedAuthenticationFailureDoesNotEnterAutomaticRetryQueue() {
        val store = newStore()
        store.add(message("rejected-auth"))
        assertNotNull(store.claim("rejected-auth", now = 1_000L))

        store.failed(
            id = "rejected-auth",
            message = "provider rejected credentials",
            retryable = false,
            unknown = false
        )

        assertEquals(PendingDeliveryState.FAILED, store.all().single().state)
        assertNull(store.claim("rejected-auth", now = Long.MAX_VALUE))
    }

    @Test
    fun definitelyNotSentNetworkFailureCanUseBoundedAutomaticRetry() {
        val store = newStore()
        store.add(message("not-sent-network"))
        assertNotNull(store.claim("not-sent-network", now = 1_000L))

        store.failed(
            id = "not-sent-network",
            message = "connection failed before model dispatch",
            retryable = true,
            unknown = false
        )

        val queued = store.all().single()
        assertEquals(PendingDeliveryState.QUEUED, queued.state)
        assertTrue(queued.nextAttemptAt > System.currentTimeMillis())
        assertNotNull(store.claim("not-sent-network", now = queued.nextAttemptAt))
    }
}
