package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GlafiraCorePersistenceTest {

    @Test
    fun traceDropsUnknownFreeTextAndSecretLookingAttributeValues() {
        val sanitized = GlafiraTraceSanitizer.sanitize(
            event(
                attributes = mapOf(
                    "scopeType" to "PROJECT",
                    "providerId" to "sk-12345678901234567890",
                    "privateNote" to "customer email is user@example.com"
                )
            )
        )

        assertEquals(mapOf("scopeType" to "PROJECT"), sanitized.safeAttributes)
        assertTrue(sanitized.redactionApplied)
    }

    @Test
    fun traceRetainsOnlyAllowListedSafeMetadata() {
        val sanitized = GlafiraTraceSanitizer.sanitize(
            event(attributes = mapOf("providerId" to "openai", "attempt" to "2"))
        )

        assertEquals("openai", sanitized.safeAttributes["providerId"])
        assertEquals("2", sanitized.safeAttributes["attempt"])
        assertFalse(sanitized.safeAttributes.containsKey("prompt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun traceRejectsUnsafeActorIdentifiers() {
        GlafiraTraceSanitizer.sanitize(event(actorId = "owner@example.com"))
    }

    @Test
    fun agentCannotObtainOwnerMemoryScope() = runBlocking {
        val factory = CoreAccessContextFactory(
            identityProvider = object : CoreIdentityProvider {
                override suspend fun currentActor() = CoreActor("agent-1", CoreActorType.AGENT)
                override suspend fun currentAgentRoleId() = AgentRoleIdentifier("researcher")
            },
            policyEngine = allowPolicy()
        )

        val access = factory.forMemory(
            projectId = ProjectIdentifier("project-1"),
            requestedScopes = setOf(MemoryScopeType.OWNER, MemoryScopeType.PROJECT, MemoryScopeType.AGENT),
            maximumClassification = CoreDataClassification.INTERNAL
        )

        assertTrue(access != null)
        assertFalse(MemoryScopeType.OWNER in requireNotNull(access).allowedScopes)
        assertTrue(MemoryScopeType.PROJECT in requireNotNull(access).allowedScopes)
    }

    @Test
    fun missingAuthenticatedActorCannotObtainMemoryContext() = runBlocking {
        val factory = CoreAccessContextFactory(
            identityProvider = object : CoreIdentityProvider {
                override suspend fun currentActor(): CoreActor? = null
            },
            policyEngine = allowPolicy()
        )

        val access = factory.forMemory(
            projectId = ProjectIdentifier("project-1"),
            requestedScopes = setOf(MemoryScopeType.PROJECT),
            maximumClassification = CoreDataClassification.INTERNAL
        )

        assertNull(access)
    }


    @Test
    fun unsafeProjectIdentifierCannotObtainMemoryContext() = runBlocking {
        val factory = CoreAccessContextFactory(
            identityProvider = object : CoreIdentityProvider {
                override suspend fun currentActor() = CoreActor("owner-1", CoreActorType.OWNER)
            },
            policyEngine = allowPolicy()
        )

        val access = factory.forMemory(
            projectId = ProjectIdentifier("owner@example.com"),
            requestedScopes = setOf(MemoryScopeType.PROJECT),
            maximumClassification = CoreDataClassification.INTERNAL
        )

        assertNull(access)
    }

    @Test
    fun agentWithoutTrustedRoleCannotRequestAgentMemory() = runBlocking {
        val factory = CoreAccessContextFactory(
            identityProvider = object : CoreIdentityProvider {
                override suspend fun currentActor() = CoreActor("agent-2", CoreActorType.AGENT)
            },
            policyEngine = allowPolicy()
        )

        val access = factory.forMemory(
            projectId = ProjectIdentifier("project-1"),
            requestedScopes = setOf(MemoryScopeType.PROJECT, MemoryScopeType.AGENT),
            maximumClassification = CoreDataClassification.INTERNAL
        )

        assertNull(access)
    }

    @Test
    fun policyRequiringOwnerApprovalDoesNotIssueMemoryContext() = runBlocking {
        val factory = CoreAccessContextFactory(
            identityProvider = object : CoreIdentityProvider {
                override suspend fun currentActor() = CoreActor("owner-1", CoreActorType.OWNER)
            },
            policyEngine = object : CorePolicyEngine {
                override suspend fun evaluate(request: CorePolicyRequest) =
                    CorePolicyDecision(allowed = true, reasonCode = "APPROVAL_REQUIRED", requiresOwnerApproval = true)
            }
        )

        val access = factory.forMemory(
            projectId = ProjectIdentifier("project-1"),
            requestedScopes = setOf(MemoryScopeType.PROJECT),
            maximumClassification = CoreDataClassification.CONFIDENTIAL
        )

        assertNull(access)
    }

    private fun allowPolicy() = object : CorePolicyEngine {
        override suspend fun evaluate(request: CorePolicyRequest) =
            CorePolicyDecision(allowed = true, reasonCode = "TEST_ALLOWED")
    }

    private fun event(
        actorId: String = "owner-1",
        attributes: Map<String, String> = emptyMap()
    ) = CoreTraceEvent(
        eventId = TraceEventIdentifier("event-1"),
        occurredAt = Instant.parse("2026-11-18T12:00:00Z"),
        recordedAt = Instant.parse("2026-11-18T12:00:00Z"),
        eventType = CoreTraceEventType.MEMORY_PROPOSED,
        severity = CoreTraceSeverity.INFO,
        actor = CoreActor(actorId, CoreActorType.OWNER),
        projectId = ProjectIdentifier("project-1"),
        taskId = null,
        parentTaskId = null,
        correlationId = CoreIdentifier("correlation-1"),
        causationId = null,
        component = "MemoryRepository",
        action = "memory.put",
        targetType = "MEMORY",
        targetId = "memory-1",
        resultCode = "OK",
        approvalRef = null,
        redactionApplied = false,
        safeAttributes = attributes
    )
}
