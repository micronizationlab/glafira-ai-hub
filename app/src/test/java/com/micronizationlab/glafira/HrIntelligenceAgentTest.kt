package com.micronizationlab.glafira

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HrIntelligenceAgentTest {
    private val now = Instant.parse("2026-11-19T00:00:00Z")

    private fun review(id: String, decision: HrReviewDecision) = HrAgentReview(
        agentId = id,
        decision = decision,
        rationale = "Reviewed against the supplied source.",
        sourceReferences = listOf(
            HrSourceReference(
                sourceType = "review_fixture",
                sourceId = id,
                recordId = "review-$id",
                observedAt = now
            )
        )
    )

    @Test
    fun roleHasStableIdentityAndVersionedPrompt() {
        assertEquals("hr-intelligence", HrIntelligenceAgent.ROLE_ID)
        assertEquals(1, HrIntelligenceAgent.VERSION)
        assertTrue(HrIntelligenceAgent.prompt().contains("Не выдумывай"))
        assertTrue(HrIntelligenceAgent.prompt().contains("NOT_REVIEWED"))
    }

    @Test
    fun memoryCannotPromoteWhenAnEligibleAgentWasNotReviewed() {
        val outcome = HrMemoryReviewPolicy.evaluate(
            eligibleAgentIds = setOf("analyst", "accountant"),
            reviews = listOf(review("analyst", HrReviewDecision.ACCEPT)),
            requiresOwnerApproval = false,
            ownerApproved = false
        )

        assertFalse(outcome.allEligibleAgentsReviewed)
        assertEquals(setOf("accountant"), outcome.notReviewedAgentIds)
        assertFalse(outcome.canPromote)
    }

    @Test
    fun ownerApprovalIsRequiredForHighImpactMemory() {
        val reviews = listOf(
            review("analyst", HrReviewDecision.ACCEPT),
            review("accountant", HrReviewDecision.CORRECT)
        )
        val awaitingOwner = HrMemoryReviewPolicy.evaluate(
            eligibleAgentIds = setOf("analyst", "accountant"),
            reviews = reviews,
            requiresOwnerApproval = true,
            ownerApproved = false
        )
        val approved = HrMemoryReviewPolicy.evaluate(
            eligibleAgentIds = setOf("analyst", "accountant"),
            reviews = reviews,
            requiresOwnerApproval = true,
            ownerApproved = true
        )

        assertFalse(awaitingOwner.canPromote)
        assertTrue(approved.canPromote)
    }

    @Test
    fun conflictBlocksMemoryPromotion() {
        val outcome = HrMemoryReviewPolicy.evaluate(
            eligibleAgentIds = setOf("analyst", "accountant"),
            reviews = listOf(
                review("analyst", HrReviewDecision.ACCEPT),
                review("accountant", HrReviewDecision.CONFLICT)
            ),
            requiresOwnerApproval = true,
            ownerApproved = true
        )

        assertTrue(outcome.hasConflict)
        assertFalse(outcome.canPromote)
    }


    @Test
    fun duplicateReviewerResponsesBlockPromotion() {
        val outcome = HrMemoryReviewPolicy.evaluate(
            eligibleAgentIds = setOf("analyst", "accountant"),
            reviews = listOf(
                review("analyst", HrReviewDecision.ACCEPT),
                review("analyst", HrReviewDecision.ACCEPT),
                review("accountant", HrReviewDecision.ACCEPT)
            ),
            requiresOwnerApproval = false,
            ownerApproved = false
        )

        assertFalse(outcome.allEligibleAgentsReviewed)
        assertTrue(outcome.hasConflict)
        assertFalse(outcome.canPromote)
    }


    @Test
    fun acceptedReviewWithoutEvidenceIsRejected() {
        val error = runCatching {
            HrAgentReview(
                agentId = "analyst",
                decision = HrReviewDecision.ACCEPT,
                rationale = "No source cited."
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun factsWithoutSourceReferencesAreRejected() {
        val error = runCatching {
            HrContactFact(
                category = HrFactCategory.NAME,
                value = "Имя без источника",
                confidence = HrFactConfidence.CONFIRMED,
                sensitivity = HrFactSensitivity.PERSONAL,
                sources = emptyList(),
                isDirectlyStated = true
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun verifiedContactRequiresSourceBackedConfirmedName() {
        val source = HrSourceReference(
            sourceType = "owner_note",
            sourceId = "notes",
            recordId = "note-1",
            observedAt = now
        )
        val nameFact = HrContactFact(
            category = HrFactCategory.NAME,
            value = "Иван Иванов",
            confidence = HrFactConfidence.CONFIRMED,
            sensitivity = HrFactSensitivity.PERSONAL,
            sources = listOf(source),
            isDirectlyStated = true
        )

        val card = HrContactCard(
            contactId = "contact-1",
            displayName = "Иван Иванов",
            identityStatus = HrIdentityStatus.VERIFIED,
            facts = listOf(nameFact),
            lastVerifiedAt = now
        )

        assertEquals(HrIdentityStatus.VERIFIED, card.identityStatus)
        assertEquals("Иван Иванов", card.displayName)
    }
}
