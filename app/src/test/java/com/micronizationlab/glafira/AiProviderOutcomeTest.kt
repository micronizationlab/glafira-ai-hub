package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class AiProviderOutcomeTest {
    @Test fun ambiguousCompletionIsNeverAutomaticallyRetried() {
        AiProviderErrorCategory.values().forEach { category ->
            assertFalse(AiProviderError(category, "test", outcome = AiRequestOutcome.UNKNOWN).canRetryAutomatically)
        }
        assertFalse(providerHttpFailure(503).error.canRetryAutomatically)
        assertFalse(providerHttpFailure(408).error.canRetryAutomatically)
    }
    @Test fun quotaRejectionHasBoundedRetryDelay() {
        val error = providerHttpFailure(429, "99999999999").error
        assertEquals(AiRequestOutcome.REJECTED, error.outcome)
        assertEquals(99_999_999_999_000L, error.retryAfterMillis)
        assertFalse(error.canRetryAutomatically)
        assertFalse(providerHttpFailure(429, "-1").error.canRetryAutomatically)
    }
    @Test fun retryAfterDateNeverRetriesBeforeServerDeadline() {
        val delay = parseProviderRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", 1445412420000L)
        assertEquals(60_000L, delay)
        assertEquals(30_000L, parseProviderRetryAfter("30"))
        assertEquals(Long.MAX_VALUE, parseProviderRetryAfter("invalid"))
    }
    @Test fun partialResultsStayVisibleOnFailedSynthesis() {
        val failure = AiProviderResult.Failure(AiProviderError(AiProviderErrorCategory.PROVIDER,
            "failure", partialResults = mapOf("peer" to "actual result")))
        assertTrue(failure.userText().contains("actual result"))
        assertTrue(failure.userText().contains("синтез не завершён"))
    }
    @Test fun credentialsAndMissingModelRequireUserAction() {
        listOf(401, 403, 404).forEach { assertFalse(providerHttpFailure(it).error.canRetryAutomatically) }
    }
    @Test fun safeNetworkFailureCanRetryButTlsDoesNot() {
        assertTrue(AiProviderError(AiProviderErrorCategory.NETWORK, "network", outcome = AiRequestOutcome.NOT_SENT).canRetryAutomatically)
        assertFalse(AiProviderError(AiProviderErrorCategory.TLS, "tls", outcome = AiRequestOutcome.NOT_SENT).canRetryAutomatically)
    }
    @Test fun compositeOperationCannotRepeatCompletedPlannerAndPeers() {
        val rejected = providerHttpFailure(429, "30")
        val result = afterPartialProviderCompletion(rejected) as AiProviderResult.Failure
        assertEquals(AiRequestOutcome.UNKNOWN, result.error.outcome)
        assertFalse(result.error.canRetryAutomatically)
        assertEquals(30_000L, result.error.retryAfterMillis)
    }
    @Test fun modelIdsRejectControlCharacters() {
        assertTrue(validProviderModel("vendor/model:free"))
        assertFalse(validProviderModel("model\nAuthorization:secret"))
        assertFalse(validProviderModel(""))
    }
}
