package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class GlafiraProviderContextPolicyTest {
    private val now = 1_000L
    private val item = GlafiraProviderContextItem(
        "memo-1", "project-a", GlafiraProviderDataClassification.INTERNAL, "approved context"
    )
    private fun grant(
        project: String = "project-a",
        provider: ProviderId = ProviderId.OPENAI,
        classes: Set<GlafiraProviderDataClassification> = setOf(GlafiraProviderDataClassification.INTERNAL),
        expiry: Long = 2_000L,
        revoked: Long? = null
    ) = GlafiraProviderDataGrant(project, provider, classes, expiry, revoked)

    @Test fun missingWrongExpiredOrRevokedGrantDeniesAllContext() {
        val invalid = listOf<GlafiraProviderDataGrant?>(
            null, grant(project = "other"), grant(provider = ProviderId.DEEPSEEK),
            grant(expiry = now), grant(revoked = now)
        )
        invalid.forEach { grant ->
            val result = GlafiraProviderContextPolicy.assemble("project-a", ProviderId.OPENAI, listOf(item), grant, now)
            assertTrue(result.allowedItems.isEmpty())
            assertEquals(GlafiraProviderContextDenial.NO_VALID_GRANT, result.deniedItems.single().second)
        }
    }

    @Test fun onlyGrantedClassificationForExactProjectIsIncluded() {
        val personal = item.copy(referenceId = "personal", classification = GlafiraProviderDataClassification.CONFIDENTIAL)
        val other = item.copy(referenceId = "other", projectId = "project-b")
        val result = GlafiraProviderContextPolicy.assemble(
            "project-a", ProviderId.OPENAI, listOf(item, personal, other), grant(), now
        )
        assertEquals(listOf(item), result.allowedItems)
        assertTrue(runCatching {
            (result.allowedItems as MutableList<GlafiraProviderContextItem>).clear()
        }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(
            listOf(GlafiraProviderContextDenial.CLASSIFICATION_NOT_GRANTED, GlafiraProviderContextDenial.PROJECT_MISMATCH),
            result.deniedItems.map { it.second }
        )
    }

    @Test fun secretIsNeverForwardedAndDeniedContentIsNotReturned() {
        val secret = item.copy(referenceId = "restricted-ref", classification = GlafiraProviderDataClassification.RESTRICTED,
            content = "restricted-canary")
        val result = GlafiraProviderContextPolicy.assemble("project-a", ProviderId.OPENAI, listOf(secret), grant(), now)
        assertTrue(result.allowedItems.isEmpty())
        assertEquals("restricted-ref", result.deniedItems.single().first)
        assertEquals(GlafiraProviderContextDenial.RESTRICTED_NEVER_SHARED, result.deniedItems.single().second)
        assertFalse(result.deniedItems.toString().contains("restricted-canary"))
        assertEquals("PROVIDER_RESTRICTED_CLASSIFICATION_NEVER_GRANTABLE", runCatching {
            grant(classes = setOf(GlafiraProviderDataClassification.RESTRICTED))
        }.exceptionOrNull()?.message)
    }

    @Test fun grantDefensivelyCopiesAndFreezesClassificationSet() {
        val source = mutableSetOf(GlafiraProviderDataClassification.INTERNAL)
        val fixed = grant(classes = source)
        source += GlafiraProviderDataClassification.RESTRICTED
        val restricted = item.copy(classification = GlafiraProviderDataClassification.RESTRICTED)
        val result = GlafiraProviderContextPolicy.assemble("project-a", ProviderId.OPENAI, listOf(restricted), fixed, now)
        assertTrue(result.allowedItems.isEmpty())
        assertEquals(GlafiraProviderContextDenial.RESTRICTED_NEVER_SHARED, result.deniedItems.single().second)
        val error = runCatching {
            @Suppress("UNCHECKED_CAST")
            (fixed.allowedClassifications as MutableSet<GlafiraProviderDataClassification>)
                .add(GlafiraProviderDataClassification.CONFIDENTIAL)
        }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
    }

    @Test fun rejectsOversizedDuplicateAndInvalidIdentifiersBeforeDispatch() {
        val large = (1..5).map { item.copy(referenceId = "memo-$it",
            content = "x".repeat(GlafiraProviderContextPolicy.MAX_PROVIDER_CONTEXT_ITEM_CHARS)) }
        assertEquals("PROVIDER_CONTEXT_TOTAL_SIZE_EXCEEDED", runCatching {
            GlafiraProviderContextPolicy.assemble("project-a", ProviderId.OPENAI, large, grant(), now)
        }.exceptionOrNull()?.message)
        assertEquals("PROVIDER_CONTEXT_DUPLICATE_REFERENCE", runCatching {
            GlafiraProviderContextPolicy.assemble("project-a", ProviderId.OPENAI, listOf(item, item.copy(content = "second")), grant(), now)
        }.exceptionOrNull()?.message)
        assertEquals("PROVIDER_CONTEXT_PROJECT_INVALID", runCatching {
            GlafiraProviderContextPolicy.assemble("../escape", ProviderId.OPENAI, emptyList(), grant(), now)
        }.exceptionOrNull()?.message)
    }
}
