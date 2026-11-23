package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraProviderCapabilityPolicyTest {
    private val now = 1_000_000L
    private val provider = ProviderId.OPENAI

    private fun grant(
        operations: Set<GlafiraProviderOperation> = setOf(GlafiraProviderOperation.CODE),
        classifications: Set<GlafiraProviderDataClassification> =
            setOf(GlafiraProviderDataClassification.PUBLIC),
        sharing: Boolean = true,
        expiresAt: Long? = null,
        freeAlternativeUnavailable: Boolean = false,
        paidUseApproved: Boolean = false,
        maxCostMicrosUsd: Long? = null
    ) = GlafiraProviderCapabilityGrant(
        providerId = provider,
        operations = operations,
        allowedClassifications = classifications,
        contextSharingApproved = sharing,
        expiresAtMillis = expiresAt,
        freeAlternativeUnavailable = freeAlternativeUnavailable,
        paidUseApproved = paidUseApproved,
        maxCostMicrosUsd = maxCostMicrosUsd
    )

    private fun status(
        connection: GlafiraProviderConnectionState = GlafiraProviderConnectionState.CONNECTED,
        availability: GlafiraProviderAvailability = GlafiraProviderAvailability.AVAILABLE,
        cost: GlafiraProviderCostState = GlafiraProviderCostState.FREE_CONFIRMED,
        quota: Long? = 10L,
        checkedAt: Long = now,
        estimatedCostMicrosUsd: Long? = null
    ) = GlafiraProviderRuntimeSnapshot(provider, connection, availability, cost, quota, checkedAt, estimatedCostMicrosUsd)

    private fun evaluate(
        ownerGrant: GlafiraProviderCapabilityGrant? = grant(),
        runtime: GlafiraProviderRuntimeSnapshot? = status(),
        operation: GlafiraProviderOperation = GlafiraProviderOperation.CODE,
        classification: GlafiraProviderDataClassification = GlafiraProviderDataClassification.PUBLIC
    ) = GlafiraProviderCapabilityPolicy.evaluate(
        provider, operation, classification, ownerGrant, runtime, now
    )

    @Test fun explicitFreeGrantAllowsOnlyGrantedOperationAndClassification() {
        assertTrue(evaluate().eligible)
        assertEquals(
            GlafiraProviderEligibilityReason.OPERATION_NOT_GRANTED,
            evaluate(operation = GlafiraProviderOperation.REVIEW).reason
        )
        assertEquals(
            GlafiraProviderEligibilityReason.DATA_CLASSIFICATION_NOT_GRANTED,
            evaluate(classification = GlafiraProviderDataClassification.CONFIDENTIAL).reason
        )
    }

    @Test fun missingGrantAndMissingContextConsentFailClosed() {
        assertEquals(GlafiraProviderEligibilityReason.NO_OWNER_GRANT, evaluate(ownerGrant = null).reason)
        assertEquals(
            GlafiraProviderEligibilityReason.CONTEXT_SHARING_NOT_APPROVED,
            evaluate(ownerGrant = grant(sharing = false)).reason
        )
    }

    @Test fun expiredGrantFailsClosedAtExpiryBoundary() {
        assertEquals(
            GlafiraProviderEligibilityReason.GRANT_EXPIRED,
            evaluate(ownerGrant = grant(expiresAt = now)).reason
        )
    }

    @Test fun disconnectedRevokedOrWrongProviderStatusCannotBeUsed() {
        assertEquals(
            GlafiraProviderEligibilityReason.PROVIDER_NOT_CONNECTED,
            evaluate(runtime = status(connection = GlafiraProviderConnectionState.REVOKED)).reason
        )
        val otherProvider = status().copy(providerId = ProviderId.GEMINI)
        assertEquals(
            GlafiraProviderEligibilityReason.PROVIDER_NOT_CONNECTED,
            evaluate(runtime = otherProvider).reason
        )
    }

    @Test fun staleOrFutureStatusFailsClosed() {
        assertEquals(
            GlafiraProviderEligibilityReason.PROVIDER_STATUS_STALE,
            evaluate(runtime = status(checkedAt = now - GlafiraProviderCapabilityPolicy.MAX_STATUS_AGE_MILLIS - 1)).reason
        )
        assertEquals(
            GlafiraProviderEligibilityReason.PROVIDER_STATUS_STALE,
            evaluate(runtime = status(checkedAt = now + 1)).reason
        )
    }

    @Test fun quotaAndAvailabilityAreDistinctFromPermission() {
        assertEquals(
            GlafiraProviderEligibilityReason.QUOTA_EXHAUSTED,
            evaluate(runtime = status(quota = 0L)).reason
        )
        assertEquals(
            GlafiraProviderEligibilityReason.PROVIDER_UNAVAILABLE,
            evaluate(runtime = status(availability = GlafiraProviderAvailability.RATE_LIMITED)).reason
        )
    }

    @Test fun paidOrUnknownCostNeverGetsSilentFreeFirstDispatch() {
        assertEquals(
            GlafiraProviderEligibilityReason.PAID_USE_NOT_APPROVED,
            evaluate(runtime = status(cost = GlafiraProviderCostState.PAID)).reason
        )
        assertEquals(
            GlafiraProviderEligibilityReason.COST_UNKNOWN,
            evaluate(runtime = status(cost = GlafiraProviderCostState.UNKNOWN)).reason
        )
    }

    @Test fun paidRouteRequiresNoFreeAlternativeOwnerConsentAndBoundedCost() {
        assertEquals(
            GlafiraProviderEligibilityReason.FREE_ALTERNATIVE_NOT_EXHAUSTED,
            evaluate(runtime = status(cost = GlafiraProviderCostState.PAID, estimatedCostMicrosUsd = 50L)).reason
        )
        assertEquals(
            GlafiraProviderEligibilityReason.PAID_USE_NOT_APPROVED,
            evaluate(
                ownerGrant = grant(freeAlternativeUnavailable = true),
                runtime = status(cost = GlafiraProviderCostState.PAID, estimatedCostMicrosUsd = 50L)
            ).reason
        )
        val approved = grant(
            freeAlternativeUnavailable = true,
            paidUseApproved = true,
            maxCostMicrosUsd = 100L
        )
        assertTrue(
            evaluate(
                ownerGrant = approved,
                runtime = status(cost = GlafiraProviderCostState.PAID, estimatedCostMicrosUsd = 75L)
            ).eligible
        )
        assertEquals(
            GlafiraProviderEligibilityReason.PAID_BUDGET_EXCEEDED,
            evaluate(
                ownerGrant = approved,
                runtime = status(cost = GlafiraProviderCostState.PAID, estimatedCostMicrosUsd = 101L)
            ).reason
        )
        assertEquals(
            GlafiraProviderEligibilityReason.PAID_COST_UNKNOWN,
            evaluate(
                ownerGrant = approved,
                runtime = status(cost = GlafiraProviderCostState.PAID)
            ).reason
        )
    }

    @Test fun onlyExplicitlyConfirmedFreeRouteIsEligible() {
        val result = evaluate()
        assertTrue(result.eligible)
        assertEquals(GlafiraProviderEligibilityReason.ELIGIBLE, result.reason)
        assertFalse(evaluate(runtime = status(cost = GlafiraProviderCostState.UNKNOWN)).eligible)
    }
}
