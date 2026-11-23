package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderObservedStatusInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearBefore() { clear() }
    @After fun clearAfter() { clear() }

    @Test fun persistedObservationIsBoundToExactProviderAndModel() {
        val store = ProviderObservedStatusStore(context)
        val expected = ProviderObservedStatus(
            providerId = ProviderId.OPENAI,
            modelId = "gpt-observed",
            availability = ProviderObservedAvailability.AVAILABLE,
            requestOutcome = ProviderObservedRequestOutcome.SUCCEEDED,
            checkedAtMillis = 1_730_000_000_000L
        )
        assertEquals(true, store.record(expected))

        assertEquals(expected, store.load(ProviderId.OPENAI, "gpt-observed"))
        assertNull(store.load(ProviderId.OPENAI, "different-model"))
        assertNull(store.load(ProviderId.DEEPSEEK, "gpt-observed"))
    }

    @Test fun corruptObservationFailsClosed() {
        context.getSharedPreferences(PREFERENCES, 0).edit()
            .putString("openai_model", "gpt-observed")
            .putString("openai_availability", "NOT_A_STATUS")
            .putString("openai_outcome", ProviderObservedRequestOutcome.SUCCEEDED.name)
            .putLong("openai_checked_at", 1_730_000_000_000L)
            .commit()

        assertNull(ProviderObservedStatusStore(context).load(ProviderId.OPENAI, "gpt-observed"))
    }

    private fun clear() {
        context.getSharedPreferences(PREFERENCES, 0).edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES = "glafira_provider_observed_status"
    }
}
