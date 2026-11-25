package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderOutcomeTest {
    @Test fun missingCredentialsAndUnsupportedProviderAreDefinitelyNotSent() {
        val missing = classifyLegacyProviderResponse("Ошибка: API-ключ не задан.") as AiProviderResult.Failure
        val unsupported = classifyLegacyProviderResponse("Ошибка: подключение YandexGPT пока не реализовано.") as AiProviderResult.Failure
        assertEquals(AiRequestOutcome.NOT_SENT, missing.error.outcome)
        assertEquals(AiProviderErrorCategory.NOT_CONFIGURED, missing.error.category)
        assertEquals(AiRequestOutcome.NOT_SENT, unsupported.error.outcome)
        assertEquals(AiProviderErrorCategory.UNSUPPORTED, unsupported.error.category)
    }

    @Test fun clientErrorsAreRejectedButTimeoutAndServerErrorsRemainUnknown() {
        assertEquals(AiRequestOutcome.REJECTED, providerHttpFailure(400).error.outcome)
        assertEquals(AiRequestOutcome.REJECTED, providerHttpFailure(429).error.outcome)
        assertEquals(AiRequestOutcome.UNKNOWN, providerHttpFailure(408).error.outcome)
        assertEquals(AiRequestOutcome.UNKNOWN, providerHttpFailure(503).error.outcome)
        assertFalse(providerHttpFailure(503).error.canRetryAutomatically)
    }

    @Test fun providerHttpStatusMatrixSeparatesExplicitRejectionsFromAmbiguousOutcomes() {
        listOf(400, 401, 403, 404, 409, 422, 429).forEach { status ->
            assertEquals("HTTP $status", AiRequestOutcome.REJECTED, providerHttpFailure(status).error.outcome)
            assertFalse("HTTP $status", providerHttpFailure(status).error.outcome == AiRequestOutcome.UNKNOWN)
        }
        listOf(408, 500, 502, 503, 504, 599).forEach { status ->
            val failure = providerHttpFailure(status)
            assertEquals("HTTP $status", AiRequestOutcome.UNKNOWN, failure.error.outcome)
            assertFalse("HTTP $status", failure.error.canRetryAutomatically)
        }
        assertEquals(AiProviderErrorCategory.AUTHENTICATION, providerHttpFailure(401).error.category)
        assertEquals(AiProviderErrorCategory.AUTHENTICATION, providerHttpFailure(403).error.category)
        assertEquals(AiProviderErrorCategory.MODEL_UNAVAILABLE, providerHttpFailure(404).error.category)
    }

    @Test fun preDispatchNetworkAndTimeoutAreRetryableButTlsAndPostDispatchFailuresAreNot() {
        listOf(AiProviderErrorCategory.NETWORK, AiProviderErrorCategory.TIMEOUT).forEach { category ->
            val failure = providerTransportFailure(category, modelPostAttempted = false, message = "before dispatch").error
            assertEquals(AiRequestOutcome.NOT_SENT, failure.outcome)
            assertTrue(failure.canRetryAutomatically)
        }
        val tlsBeforeDispatch = providerTransportFailure(
            AiProviderErrorCategory.TLS, modelPostAttempted = false, message = "TLS failed"
        ).error
        assertEquals(AiRequestOutcome.NOT_SENT, tlsBeforeDispatch.outcome)
        assertFalse(tlsBeforeDispatch.canRetryAutomatically)

        listOf(AiProviderErrorCategory.NETWORK, AiProviderErrorCategory.TIMEOUT, AiProviderErrorCategory.TLS).forEach { category ->
            val failure = providerTransportFailure(category, modelPostAttempted = true, message = "after dispatch").error
            assertEquals(AiRequestOutcome.UNKNOWN, failure.outcome)
            assertFalse(failure.canRetryAutomatically)
        }
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

    @Test fun distributedTaskAdapterPreservesProviderOutcomeAndRetryAfterWithoutReplaying() {
        val unknown = AiProviderResult.Failure(AiProviderError(
            category = AiProviderErrorCategory.TIMEOUT,
            message = "provider timed out after dispatch",
            outcome = AiRequestOutcome.UNKNOWN,
            retryAfterMillis = 30_000L
        )).toDistributedTaskResult("task-unknown")

        assertFalse(unknown.succeeded)
        assertEquals("LOCAL_AI_PROVIDER_REQUEST_FAILED", unknown.errorCode)
        assertEquals(AiRequestOutcome.UNKNOWN, unknown.providerOutcome)
        assertEquals(AiProviderErrorCategory.TIMEOUT, unknown.providerErrorCategory)
        assertEquals(30_000L, unknown.providerRetryAfterMillis)
        assertNull(unknown.output)
    }

    @Test fun distributedTaskAdapterPreservesDefinitelyNotSentAndRejectedOutcomes() {
        val notSent = AiProviderResult.Failure(AiProviderError(
            category = AiProviderErrorCategory.NOT_CONFIGURED,
            message = "missing key",
            outcome = AiRequestOutcome.NOT_SENT
        )).toDistributedTaskResult("task-not-sent")
        val rejected = AiProviderResult.Failure(AiProviderError(
            category = AiProviderErrorCategory.RATE_LIMIT,
            message = "quota",
            outcome = AiRequestOutcome.REJECTED,
            httpStatus = 429,
            retryAfterMillis = 60_000L
        )).toDistributedTaskResult("task-rejected")

        assertEquals(AiRequestOutcome.NOT_SENT, notSent.providerOutcome)
        assertEquals(AiProviderErrorCategory.NOT_CONFIGURED, notSent.providerErrorCategory)
        assertEquals(AiRequestOutcome.REJECTED, rejected.providerOutcome)
        assertEquals(AiProviderErrorCategory.RATE_LIMIT, rejected.providerErrorCategory)
        assertEquals(60_000L, rejected.providerRetryAfterMillis)
    }

    @Test fun plannerAdapterPropagatesUnknownOutcomeInsteadOfFlatteningToString() {
        val error = AiProviderError(
            category = AiProviderErrorCategory.NETWORK,
            message = "connection reset after dispatch",
            outcome = AiRequestOutcome.UNKNOWN
        )
        val failure = runCatching {
            AiProviderResult.Failure(error).requirePlanText()
        }.exceptionOrNull()

        assertTrue(failure is AiProviderTaskPlanException)
        assertEquals(error, (failure as AiProviderTaskPlanException).providerError)
        assertEquals(AiRequestOutcome.UNKNOWN, failure.providerError.outcome)
    }

    @Test fun plannerAdapterAcceptsOnlyNonBlankTypedSuccess() {
        assertEquals("validated plan json", AiProviderResult.Success("validated plan json").requirePlanText())
        assertEquals(
            "TASK_PLANNER_PROVIDER_EMPTY_RESPONSE",
            runCatching { AiProviderResult.Success(" ").requirePlanText() }.exceptionOrNull()?.message
        )
    }
}
