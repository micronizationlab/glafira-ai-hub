package com.micronizationlab.glafira

/**
 * Provider-independent operation vocabulary for the Development Orchestrator.
 *
 * These declarations contain capability/consent metadata only. They must never contain
 * API keys, OAuth tokens, account passwords, or provider session material.
 */
enum class GlafiraProviderOperation {
    PLAN,
    CODE,
    REVIEW,
    EXPLAIN,
    ANALYZE_TEST_FAILURE
}

enum class GlafiraProviderDataClassification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}

enum class GlafiraProviderConnectionState {
    CONNECTED,
    DISCONNECTED,
    REVOKED
}

enum class GlafiraProviderAvailability {
    AVAILABLE,
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    OFFLINE,
    UNKNOWN
}

/** A provider's billing status for the selected model/operation, not merely its brand. */
enum class GlafiraProviderCostState {
    FREE_CONFIRMED,
    PAID,
    UNKNOWN
}

/**
 * Owner-granted provider scope. A configured API key does not create this grant.
 * Expiry is checked at dispatch time; revocation is represented by removing the grant.
 */
data class GlafiraProviderCapabilityGrant(
    val providerId: ProviderId,
    val operations: Set<GlafiraProviderOperation>,
    val allowedClassifications: Set<GlafiraProviderDataClassification>,
    val contextSharingApproved: Boolean,
    val expiresAtMillis: Long? = null
)

/** Live, non-secret provider health and quota snapshot. */
data class GlafiraProviderRuntimeSnapshot(
    val providerId: ProviderId,
    val connectionState: GlafiraProviderConnectionState,
    val availability: GlafiraProviderAvailability,
    val costState: GlafiraProviderCostState,
    val quotaRemaining: Long? = null,
    val checkedAtMillis: Long
)

enum class GlafiraProviderEligibilityReason {
    ELIGIBLE,
    NO_OWNER_GRANT,
    CONTEXT_SHARING_NOT_APPROVED,
    OPERATION_NOT_GRANTED,
    DATA_CLASSIFICATION_NOT_GRANTED,
    GRANT_EXPIRED,
    PROVIDER_NOT_CONNECTED,
    PROVIDER_UNAVAILABLE,
    QUOTA_EXHAUSTED,
    COST_UNKNOWN,
    PAID_USE_NOT_APPROVED,
    PROVIDER_STATUS_STALE
}

data class GlafiraProviderEligibility(
    val eligible: Boolean,
    val reason: GlafiraProviderEligibilityReason
)

/**
 * Deterministic, fail-closed eligibility check for a single provider/task assignment.
 *
 * This class does not call providers, read credentials, infer capabilities from provider names,
 * or silently fall back to paid services. The caller must supply an explicit owner grant and a
 * fresh runtime snapshot for the exact selected provider/model.
 */
object GlafiraProviderCapabilityPolicy {
    const val MAX_STATUS_AGE_MILLIS: Long = 5 * 60 * 1000L

    fun evaluate(
        providerId: ProviderId,
        operation: GlafiraProviderOperation,
        classification: GlafiraProviderDataClassification,
        grant: GlafiraProviderCapabilityGrant?,
        status: GlafiraProviderRuntimeSnapshot?,
        nowMillis: Long
    ): GlafiraProviderEligibility {
        if (grant == null || grant.providerId != providerId) {
            return denied(GlafiraProviderEligibilityReason.NO_OWNER_GRANT)
        }
        if (!grant.contextSharingApproved) {
            return denied(GlafiraProviderEligibilityReason.CONTEXT_SHARING_NOT_APPROVED)
        }
        if (operation !in grant.operations) {
            return denied(GlafiraProviderEligibilityReason.OPERATION_NOT_GRANTED)
        }
        if (classification !in grant.allowedClassifications) {
            return denied(GlafiraProviderEligibilityReason.DATA_CLASSIFICATION_NOT_GRANTED)
        }
        if (grant.expiresAtMillis != null && nowMillis >= grant.expiresAtMillis) {
            return denied(GlafiraProviderEligibilityReason.GRANT_EXPIRED)
        }
        if (status == null || status.providerId != providerId) {
            return denied(GlafiraProviderEligibilityReason.PROVIDER_NOT_CONNECTED)
        }
        if (status.checkedAtMillis <= 0L || nowMillis < status.checkedAtMillis ||
            nowMillis - status.checkedAtMillis > MAX_STATUS_AGE_MILLIS
        ) {
            return denied(GlafiraProviderEligibilityReason.PROVIDER_STATUS_STALE)
        }
        if (status.connectionState != GlafiraProviderConnectionState.CONNECTED) {
            return denied(GlafiraProviderEligibilityReason.PROVIDER_NOT_CONNECTED)
        }
        if (status.availability != GlafiraProviderAvailability.AVAILABLE) {
            return denied(
                if (status.availability == GlafiraProviderAvailability.QUOTA_EXHAUSTED ||
                    status.quotaRemaining == 0L
                ) GlafiraProviderEligibilityReason.QUOTA_EXHAUSTED
                else GlafiraProviderEligibilityReason.PROVIDER_UNAVAILABLE
            )
        }
        if (status.quotaRemaining == 0L) {
            return denied(GlafiraProviderEligibilityReason.QUOTA_EXHAUSTED)
        }
        when (status.costState) {
            GlafiraProviderCostState.FREE_CONFIRMED -> Unit
            GlafiraProviderCostState.PAID ->
                return denied(GlafiraProviderEligibilityReason.PAID_USE_NOT_APPROVED)
            GlafiraProviderCostState.UNKNOWN ->
                return denied(GlafiraProviderEligibilityReason.COST_UNKNOWN)
        }
        return GlafiraProviderEligibility(true, GlafiraProviderEligibilityReason.ELIGIBLE)
    }

    private fun denied(reason: GlafiraProviderEligibilityReason) =
        GlafiraProviderEligibility(false, reason)
}
