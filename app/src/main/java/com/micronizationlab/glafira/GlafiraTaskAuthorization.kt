package com.micronizationlab.glafira

import org.json.JSONObject

enum class GlafiraTaskAuthorizationStatus { AUTHORIZED, WAITING_FOR_OWNER, DENIED }

data class GlafiraTaskAuthorizationResult(
    val status: GlafiraTaskAuthorizationStatus,
    val reason: String? = null
)

/** Trusted owner-controlled grant lookup. Never source scopes or grants from model output. */
interface GlafiraTaskGrantSource {
    fun grantedScopes(projectId: String, origin: String): Set<String>
    fun approvalGrant(task: GlafiraTask, actionSummary: String): ToolApprovalGrant?
}

fun interface GlafiraTaskAuthorizer {
    fun authorize(task: GlafiraTask, nowMillis: Long): GlafiraTaskAuthorizationResult
}

/** Unknown tools and missing scopes are denied; risky actions wait for exact owner approval. */
class RegistryBackedGlafiraTaskAuthorizer(
    private val grants: GlafiraTaskGrantSource
) : GlafiraTaskAuthorizer {
    override fun authorize(task: GlafiraTask, nowMillis: Long): GlafiraTaskAuthorizationResult {
        val definition = GlafiraToolRegistry.find(task.taskType)
            ?: return GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.DENIED, "UNKNOWN_TOOL")

        val ownerScopes = grants.grantedScopes(task.projectId, task.origin)
        if (!ownerScopes.containsAll(definition.requiredScopes + task.requiredScopes)) {
            return GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.DENIED, "SCOPE_DENIED")
        }

        val actionSummary = runCatching {
            JSONObject(task.payload).optString("actionSummary").trim()
        }.getOrDefault("")
        if (GlafiraToolRegistry.requiresOwnerApproval(definition.risk) && actionSummary.isBlank()) {
            return GlafiraTaskAuthorizationResult(
                GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER,
                "EXACT_ACTION_SUMMARY_REQUIRED"
            )
        }

        val grant = if (actionSummary.isBlank()) null else grants.approvalGrant(task, actionSummary)
        return when (GlafiraToolRegistry.authorize(definition, task.projectId, actionSummary, grant, nowMillis)) {
            ToolAuthorizationDecision.ALLOWED ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED ->
                GlafiraTaskAuthorizationResult(
                    GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER,
                    "OWNER_APPROVAL_REQUIRED"
                )
        }
    }
}
