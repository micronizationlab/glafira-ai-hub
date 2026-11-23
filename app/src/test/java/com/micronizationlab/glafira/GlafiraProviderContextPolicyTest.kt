package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraProviderContextPolicyTest {
    private val now = 1_000L
    private val allowed = GlafiraProviderContextItem(
        "memo-1", "project-a", GlafiraProviderDataClassification.INTERNAL, "approved context"
    )

    private fun grant(
        project: String = "project-a",
        provider: ProviderId = ProviderId.OPENAI,
        classifications: Set<GlafiraProviderDataClassification> =
            setOf(GlafiraProviderDataClassification.INTERNAL),
        expiry: Long = 2_000L,
        revoked: Long? = null
    ) = GlafiraProviderDataGrant(project, provider, classifications, expiry, revoked)

    @Test
    fun noGrantMeansNoContextEvenWhenProviderIsConfigured() {
        val result = GlafiraProviderContextPolicy.assemble(
            "project-a", ProviderId.OPENAI, listOf(allowed), null, now
        )
        assertTrue(result.allowedItems.isEmpty())
        assertEquals(
            GlafiraProviderContextDenial.NO_VALID_GRANT,
            result.deniedItems.single().second
        )
    }

    @Test
    fun onlyExplicitlyGrantedClassificationsForExactProjectAndProviderAreIncluded() {
        val personal = allowed.copy(
            referenceId = "personal-1",
            classification = GlafiraProviderDataClassification.PERSONAL
        )
        val result = GlafiraProviderContextPolicy.assemble(
            "project-a", ProviderId.OPENAI, listOf(allowed, personal), grant(), now
        )
        assertEquals(listOf(allowed), result.allowedItems)
        assertEquals(
            GlafiraProviderContextDenial.CLASSIFICATION_NOT_GRANTED,
            result.deniedItems.single().second
        )
    }

    @Test
    fun rejectsWrongProjectProviderExpiredAndRevokedGrants() {
        val cases = listOf(
            grant(project = "project-b"),
            grant(provider = ProviderId.DEEPSEEK),
            grant(expiry = now),
            grant(revoked = now)
        )
        cases.forEach { invalid ->
            val result = GlafiraProviderContextPolicy.assemble(
                "project-a", ProviderId.OPENAI, listOf(allowed), invalid, now
            )
            assertTrue(result.allowedItems.isEmpty())
            assertEquals(GlafiraProviderContextDenial.NO_VALID_GRANT, result.deniedItems.single().second)
        }
    }

    @Test
    fun secretIsNeverForwardedEvenIfOtherClassesAreGranted() {
        val secret = allowed.copy(
            referenceId = "secret-1",
            classification = GlafiraProviderDataClassification.SECRET,
            content = "secret-canary-value"
        )
        val result = GlafiraProviderContextPolicy.assemble(
            "project-a", ProviderId.OPENAI, listOf(secret), grant(), now
        )
        assertTrue(result.allowedItems.isEmpty())
        assertEquals(
            GlafiraProviderContextDenial.SECRET_NEVER_SHARED,
            result.deniedItems.single().second
        )
        assertTrue(result.deniedItems.none { it.first.contains("secret-canary-value") })
    }

    @Test
    fun mixedProjectItemsAreDeniedWithoutLeakingTheirContent() {
        val other = allowed.copy(
            referenceId = "other-project-item",
            projectId = "project-b",
            content = "cross-project-canary"
        )
        val result = GlafiraProviderContextPolicy.assemble(
            "project-a", ProviderId.OPENAI, listOf(allowed, other), grant(), now
        )
        assertEquals(listOf(allowed), result.allowedItems)
        assertEquals(GlafiraProviderContextDenial.PROJECT_MISMATCH, result.deniedItems.single().second)
        assertTrue(result.deniedItems.none { it.first.contains("cross-project-canary") })
    }

    @Test
    fun rejectsOversizedAndDuplicateContextBeforePartialDispatch() {
        // Each item remains within the per-item limit; together the five items exceed
        // the total dispatch budget. Constructing one oversized item would correctly
        // fail earlier in GlafiraProviderContextItem.init and would not test assembly.
        val oversizedBatch = (1..5).map { index ->
            allowed.copy(
                referenceId = "memo-$index",
                content = "x".repeat(GlafiraProviderContextPolicy.MAX_PROVIDER_CONTEXT_ITEM_CHARS)
            )
        }
        val sizeError = runCatching {
            GlafiraProviderContextPolicy.assemble(
                "project-a", ProviderId.OPENAI, oversizedBatch, grant(), now
            )
        }.exceptionOrNull()
        assertEquals("PROVIDER_CONTEXT_TOTAL_SIZE_EXCEEDED", sizeError?.message)

        val duplicate = allowed.copy(content = "second")
        val duplicateError = runCatching {
            GlafiraProviderContextPolicy.assemble("project-a", ProviderId.OPENAI, listOf(allowed, duplicate), grant(), now)
        }.exceptionOrNull()
        assertEquals("PROVIDER_CONTEXT_DUPLICATE_REFERENCE", duplicateError?.message)
    }

    @Test
    fun neverAllowsSecretClassificationInAnOwnerGrant() {
        val error = runCatching {
            grant(classifications = setOf(GlafiraProviderDataClassification.SECRET))
        }.exceptionOrNull()
        assertEquals("PROVIDER_SECRET_CLASSIFICATION_NEVER_GRANTABLE", error?.message)
    }

    @Test
    fun grantClassificationSnapshotCannotBeExpandedByMutatingInputSet() {
        val source = mutableSetOf(GlafiraProviderDataClassification.INTERNAL)
        val immutableGrant = grant(classifications = source)
        source += GlafiraProviderDataClassification.RESTRICTED

        val restricted = allowed.copy(
            referenceId = "restricted-1",
            classification = GlafiraProviderDataClassification.RESTRICTED
        )
        val result = GlafiraProviderContextPolicy.assemble(
            "project-a", ProviderId.OPENAI, listOf(restricted), immutableGrant, now
        )
        assertTrue(result.allowedItems.isEmpty())
        assertEquals(
            GlafiraProviderContextDenial.CLASSIFICATION_NOT_GRANTED,
            result.deniedItems.single().second
        )

        val exposedMutation = runCatching {
            @Suppress("UNCHECKED_CAST")
            (immutableGrant.allowedClassifications as MutableSet<GlafiraProviderDataClassification>)
                .add(GlafiraProviderDataClassification.CONFIDENTIAL)
        }.exceptionOrNull()
        assertTrue(exposedMutation is UnsupportedOperationException)
    }

    @Test
    fun capabilityEligibilityNeverAllowsSecretEvenIfGrantIncludesIt() {
        val grant = GlafiraProviderCapabilityGrant(
            providerId = ProviderId.OPENAI,
            operations = setOf(GlafiraProviderOperation.CODE),
            allowedClassifications = setOf(GlafiraProviderDataClassification.SECRET),
            contextSharingApproved = true
        )
        val status = GlafiraProviderRuntimeSnapshot(
            providerId = ProviderId.OPENAI,
            connectionState = GlafiraProviderConnectionState.CONNECTED,
            availability = GlafiraProviderAvailability.AVAILABLE,
            costState = GlafiraProviderCostState.FREE_CONFIRMED,
            quotaRemaining = 1L,
            checkedAtMillis = now
        )
        val result = GlafiraProviderCapabilityPolicy.evaluate(
            ProviderId.OPENAI,
            GlafiraProviderOperation.CODE,
            GlafiraProviderDataClassification.SECRET,
            grant,
            status,
            now
        )
        assertFalse(result.eligible)
        assertEquals(GlafiraProviderEligibilityReason.SECRET_CLASSIFICATION_NEVER_SHARED, result.reason)
    }
}
