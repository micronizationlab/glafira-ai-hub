package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class AiProviderTransportPolicyTest {
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
    @Test fun providerHttpStatusesKeepRejectionAndAmbiguityDistinct() {
        listOf(401, 403).forEach { status ->
            val error = providerHttpFailure(status).error
            assertEquals(AiRequestOutcome.REJECTED, error.outcome)
            assertEquals(AiProviderErrorCategory.AUTHENTICATION, error.category)
            assertFalse(error.canRetryAutomatically)
        }
        val missingModel = providerHttpFailure(404).error
        assertEquals(AiRequestOutcome.REJECTED, missingModel.outcome)
        assertEquals(AiProviderErrorCategory.MODEL_UNAVAILABLE, missingModel.category)
        val timeout = providerHttpFailure(408).error
        assertEquals(AiRequestOutcome.UNKNOWN, timeout.outcome)
        listOf(500, 502, 503, 504).forEach { status ->
            assertEquals(AiRequestOutcome.UNKNOWN, providerHttpFailure(status).error.outcome)
        }
        val quota = providerHttpFailure(429, "30", 1_000L).error
        assertEquals(AiRequestOutcome.REJECTED, quota.outcome)
        assertEquals(AiProviderErrorCategory.RATE_LIMIT, quota.category)
        assertTrue(quota.canRetryAutomatically)
    }

    @Test fun transportFailureIsRetryableOnlyWhenModelRequestWasNotDispatched() {
        val beforeDispatch = providerTransportFailure(
            AiProviderErrorCategory.NETWORK, modelPostAttempted = false, message = "connect failed"
        ).error
        assertEquals(AiRequestOutcome.NOT_SENT, beforeDispatch.outcome)
        assertTrue(beforeDispatch.canRetryAutomatically)

        val afterDispatch = providerTransportFailure(
            AiProviderErrorCategory.TIMEOUT, modelPostAttempted = true, message = "read timed out"
        ).error
        assertEquals(AiRequestOutcome.UNKNOWN, afterDispatch.outcome)
        assertFalse(afterDispatch.canRetryAutomatically)
    }

    @Test fun modelIdsRejectControlCharacters() {
        assertTrue(validProviderModel("vendor/model:free"))
        assertFalse(validProviderModel("model\nAuthorization:secret"))
        assertFalse(validProviderModel(""))
    }
}
