package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderOutcomeTest {
    @Test fun missingCredentialsAndUnsupportedProviderAreDefinitelyNotSent() {
        val missing = classifyLegacyProviderResponse("Ошибка: API-ключ не задан.")
        val unsupported = classifyLegacyProviderResponse("Ошибка: подключение YandexGPT пока не реализовано.")
        assertEquals(AiRequestOutcome.NOT_SENT, (missing as AiProviderResult.Failure).error.outcome)
        assertEquals(AiProviderErrorCategory.NOT_CONFIGURED, missing.error.category)
        assertEquals(AiRequestOutcome.NOT_SENT, (unsupported as AiProviderResult.Failure).error.outcome)
        assertEquals(AiProviderErrorCategory.UNSUPPORTED, unsupported.error.category)
    }

    @Test fun clientErrorsAreRejectedButTimeoutAndServerErrorsRemainUnknown() {
        assertEquals(AiRequestOutcome.REJECTED, providerHttpFailure(400).error.outcome)
        assertEquals(AiRequestOutcome.REJECTED, providerHttpFailure(429).error.outcome)
        assertEquals(AiRequestOutcome.UNKNOWN, providerHttpFailure(408).error.outcome)
        assertEquals(AiRequestOutcome.UNKNOWN, providerHttpFailure(503).error.outcome)
        assertFalse(providerHttpFailure(503).error.canRetryAutomatically)
    }

    @Test fun legacyNetworkFailureIsUnknownBecauseRequestMayHaveReachedProvider() {
        val result = classifyLegacyProviderResponse("Ошибка сети (OpenAI): connection reset")
        assertEquals(AiRequestOutcome.UNKNOWN, (result as AiProviderResult.Failure).error.outcome)
        assertFalse(result.error.canRetryAutomatically)
    }

    @Test fun retryAfterSupportsSecondsAndHttpDatesAndFailsClosedOnInvalidValues() {
        assertEquals(30_000L, parseProviderRetryAfter("30", 1_000L))
        assertEquals(60_000L, parseProviderRetryAfter(
            "Wed, 21 Oct 2015 07:28:00 GMT", 1_445_412_420_000L
        ))
        assertEquals(Long.MAX_VALUE, parseProviderRetryAfter("-1", 1_000L))
        assertEquals(Long.MAX_VALUE, parseProviderRetryAfter("invalid", 1_000L))
    }

    @Test fun excessiveRetryAfterDisablesAutomaticRetry() {
        val result = providerHttpFailure(429, "3600", 1_000L)
        assertEquals(AiRequestOutcome.REJECTED, result.error.outcome)
        assertEquals(3_600_000L, result.error.retryAfterMillis)
        assertFalse(result.error.canRetryAutomatically)
    }

    @Test fun partialPeerResultsSurviveFailedSynthesisAndMarkCompositeOutcomeUnknown() {
        val failedSynthesis = AiProviderResult.Failure(AiProviderError(
            category = AiProviderErrorCategory.PROVIDER,
            message = "primary synthesis failed",
            outcome = AiRequestOutcome.REJECTED,
            partialResults = mapOf("DeepSeek" to "independent analysis")
        ))
        val result = afterPartialProviderCompletion(failedSynthesis) as AiProviderResult.Failure
        assertEquals(AiRequestOutcome.UNKNOWN, result.error.outcome)
        assertTrue(result.userText().contains("independent analysis"))
        assertTrue(result.userText().contains("Автоматический повтор отключён"))
    }

    @Test fun successfulResponsesRemainSuccessful() {
        assertEquals("answer", (classifyLegacyProviderResponse("answer") as AiProviderResult.Success).text)
    }
}
