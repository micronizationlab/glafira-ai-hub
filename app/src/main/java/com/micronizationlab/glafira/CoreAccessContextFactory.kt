package com.micronizationlab.glafira

/**
 * Supplies identity established by the app's authenticated owner/session boundary.
 * Implementations must never derive identity or role from model/agent input.
 */
interface CoreIdentityProvider {
    suspend fun currentActor(): CoreActor?
    /** Trusted role from the authenticated agent session; never supplied by task input. */
    suspend fun currentAgentRoleId(): AgentRoleIdentifier? = null
}

/**
 * Issues repository access contexts from authenticated identity and current policy.
 * Agents cannot request OWNER scope; requested scopes are intersected with the actor
 * type's fixed ceiling before a context is issued.
 */
class CoreAccessContextFactory(
    private val identityProvider: CoreIdentityProvider,
    private val policyEngine: CorePolicyEngine
) {
    suspend fun forMemory(
        projectId: ProjectIdentifier,
        requestedScopes: Set<MemoryScopeType>,
        maximumClassification: CoreDataClassification
    ): MemoryAccessContext? {
        val actor = identityProvider.currentActor() ?: return null
        if (!isSafePrincipal(actor.id) || !isSafePrincipal(projectId.value)) return null
        val trustedRoleId = if (actor.type == CoreActorType.AGENT) {
            identityProvider.currentAgentRoleId()
        } else null
        if (trustedRoleId != null && !isSafePrincipal(trustedRoleId.value)) return null
        val actorCeiling = when (actor.type) {
            CoreActorType.OWNER, CoreActorType.SYSTEM -> MemoryScopeType.values().toSet()
            CoreActorType.AGENT -> setOf(
                MemoryScopeType.PROJECT, MemoryScopeType.AGENT,
                MemoryScopeType.CONVERSATION, MemoryScopeType.WORKING
            )
            CoreActorType.REVIEWER -> setOf(
                MemoryScopeType.PROJECT, MemoryScopeType.CONVERSATION, MemoryScopeType.WORKING
            )
            CoreActorType.INTEGRATION -> setOf(
                MemoryScopeType.PROJECT, MemoryScopeType.CONVERSATION
            )
        }
        val scopes = requestedScopes intersect actorCeiling
        if (scopes.isEmpty()) return null
        if (actor.type == CoreActorType.AGENT &&
            MemoryScopeType.AGENT in scopes && trustedRoleId == null) return null

        val decision = policyEngine.evaluate(
            CorePolicyRequest(actor, projectId, CoreAction.READ_MEMORY, maximumClassification)
        )
        if (!decision.allowed || decision.requiresOwnerApproval) return null

        return MemoryAccessContext(
            actorId = actor.id,
            projectId = projectId,
            agentRoleId = trustedRoleId,
            allowedScopes = scopes,
            maximumClassification = maximumClassification,
            actorType = actor.type
        )
    }

    suspend fun forTrace(
        projectIds: Set<ProjectIdentifier>,
        maximumClassification: CoreDataClassification = CoreDataClassification.INTERNAL
    ): TraceAccessContext? {
        val actor = identityProvider.currentActor() ?: return null
        if (!isSafePrincipal(actor.id) || projectIds.size > 100 ||
            projectIds.any { !isSafePrincipal(it.value) }) return null
        val allowed = projectIds.filterTo(linkedSetOf()) { projectId ->
            val decision = policyEngine.evaluate(
                CorePolicyRequest(
                    actor = actor,
                    projectId = projectId,
                    action = CoreAction.READ_TRACE,
                    classification = maximumClassification
                )
            )
            decision.allowed && !decision.requiresOwnerApproval
        }
        if (allowed.isEmpty()) return null
        return TraceAccessContext(actor, allowed, maximumClassification)
    }

    private fun isSafePrincipal(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")) &&
            GlafiraSecretRedactor.redact(value) == value
}
