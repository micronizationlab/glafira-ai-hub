package com.micronizationlab.glafira

import org.json.JSONObject

enum class GlafiraTaskAuthorizationStatus { AUTHORIZED, WAITING_FOR_OWNER, DENIED }

data class GlafiraTaskAuthorizationResult(
    val status: GlafiraTaskAuthorizationStatus,
    val reason: String? = null
)

/** Trusted owner-controlled grants and durable approval requests. Never source grants from model output. */
interface GlafiraTaskGrantSource {
    fun grantedScopes(task: GlafiraTask): Set<String>
    fun approvalGrant(task: GlafiraTask, actionSummary: String, nowMillis: Long): ToolApprovalGrant?
    fun requestApproval(task: GlafiraTask, actionSummary: String, nowMillis: Long): Boolean
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

        val actionSummary = runCatching {
            JSONObject(task.payload).optString("actionSummary").trim()
        }.getOrDefault("")
        val requiredScopes = definition.requiredScopes + task.requiredScopes
        val ownerScopes = grants.grantedScopes(task)
        val missingScopes = !ownerScopes.containsAll(requiredScopes)
        val requiresApproval = GlafiraToolRegistry.requiresOwnerApproval(definition.risk)

        if ((missingScopes || requiresApproval) && actionSummary.isBlank()) {
            return GlafiraTaskAuthorizationResult(
                GlafiraTaskAuthorizationStatus.DENIED,
                "EXACT_ACTION_SUMMARY_REQUIRED"
            )
        }

        val grant = if (!missingScopes && (requiresApproval || actionSummary.isNotBlank())) grants.approvalGrant(task, actionSummary, nowMillis) else null
        if (missingScopes || (requiresApproval && grant == null)) {
            val queued = runCatching {
                grants.requestApproval(task, actionSummary, nowMillis)
            }.getOrDefault(false)
            return GlafiraTaskAuthorizationResult(
                GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER,
                if (queued) "OWNER_APPROVAL_REQUIRED" else "OWNER_APPROVAL_STORAGE_ERROR"
            )
        }

        return when (GlafiraToolRegistry.authorize(definition, task, actionSummary, grant, nowMillis)) {
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
