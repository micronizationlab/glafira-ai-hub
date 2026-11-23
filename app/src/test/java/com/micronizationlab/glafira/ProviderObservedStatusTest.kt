package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderObservedStatusTest {
    private val checkedAt = 1_730_000_000_000L

    @Test fun successfulResponseConfirmsOnlyTheExactProviderModelPair() {
        val status = classifyProviderObservation(
            ProviderId.OPENAI, "gpt-test", AiProviderResult.Success("answer"), checkedAt
        )
        assertEquals(ProviderId.OPENAI, status.providerId)
        assertEquals("gpt-test", status.modelId)
        assertEquals(ProviderObservedAvailability.AVAILABLE, status.availability)
        assertEquals(ProviderObservedRequestOutcome.SUCCEEDED, status.requestOutcome)
        assertTrue(providerObservedStatusLabel(status, checkedAt).contains("успешно"))
    }

    @Test fun missingModelAndAuthenticationHaveOwnerActionableStates() {
        val missing = classifyProviderObservation(
            ProviderId.OPENAI, "missing-model", providerHttpFailure(404), checkedAt
        )
        val authentication = classifyProviderObservation(
            ProviderId.OPENAI, "gpt-test", providerHttpFailure(401), checkedAt
        )
        assertEquals(ProviderObservedAvailability.MODEL_UNAVAILABLE, missing.availability)
        assertEquals(404, missing.httpStatus)
        assertEquals(ProviderObservedAvailability.AUTHENTICATION_FAILED, authentication.availability)
        assertTrue(providerObservedStatusLabel(missing, checkedAt).contains("ID модели"))
    }

    @Test fun rateLimitIsNotReportedAsModelAvailability() {
        val status = classifyProviderObservation(
            ProviderId.GEMINI, "gemini-test", providerHttpFailure(429, "30"), checkedAt
        )
        assertEquals(ProviderObservedAvailability.RATE_LIMITED, status.availability)
        assertEquals(ProviderObservedRequestOutcome.REJECTED, status.requestOutcome)
    }

    @Test fun ambiguousTransportFailureRemainsExplicitlyUnknown() {
        val failure = providerTransportFailure(
            AiProviderErrorCategory.TIMEOUT,
            modelPostAttempted = true,
            message = "timeout"
        )
        val status = classifyProviderObservation(
            ProviderId.DEEPSEEK, "deepseek-chat", failure, checkedAt
        )
        assertEquals(ProviderObservedAvailability.NETWORK_UNAVAILABLE, status.availability)
        assertEquals(ProviderObservedRequestOutcome.UNKNOWN, status.requestOutcome)
    }

    @Test fun notConfiguredAndUnsupportedNeverPretendToBeLiveChecks() {
        val notConfigured = AiProviderResult.Failure(AiProviderError(
            AiProviderErrorCategory.NOT_CONFIGURED, "missing", outcome = AiRequestOutcome.NOT_SENT
        ))
        val unsupported = AiProviderResult.Failure(AiProviderError(
            AiProviderErrorCategory.UNSUPPORTED, "unsupported", outcome = AiRequestOutcome.NOT_SENT
        ))
        assertEquals(
            ProviderObservedAvailability.NOT_CONFIGURED,
            classifyProviderObservation(ProviderId.OPENAI, "gpt-test", notConfigured, checkedAt).availability
        )
        assertEquals(
            ProviderObservedAvailability.UNSUPPORTED,
            classifyProviderObservation(ProviderId.YANDEX, "", unsupported, checkedAt).availability
        )
    }

    @Test fun oldOrFutureObservationIsExplicitlyStale() {
        val status = classifyProviderObservation(
            ProviderId.OPENAI, "gpt-test", AiProviderResult.Success("answer"), checkedAt
        )
        assertTrue(providerObservedStatusLabel(
            status, checkedAt + MAX_PROVIDER_OBSERVATION_AGE_MILLIS + 1
        ).contains("устарел"))
        assertTrue(providerObservedStatusLabel(status, checkedAt - 1).contains("устарел"))
    }
}
