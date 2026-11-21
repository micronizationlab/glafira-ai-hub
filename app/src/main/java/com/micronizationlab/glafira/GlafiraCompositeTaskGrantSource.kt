package com.micronizationlab.glafira

/**
 * Combines owner-approved Bridge device scopes with exact, one-use Core owner action grants.
 * Device scopes are loaded only from the encrypted Android Keystore credential store; task
 * payload fields are never treated as authority.
 */
internal class GlafiraCompositeTaskGrantSource(
    private val ownerApprovals: GlafiraCoreOwnerApprovalStore,
    private val bridgeCredentials: GlafiraBridgeCredentialProvider
) : GlafiraTaskGrantSource {
    override fun grantedScopes(task: GlafiraTask): Set<String> {
        val deviceId = task.origin
            .takeIf { it.startsWith(BRIDGE_ORIGIN_PREFIX) }
            ?.removePrefix(BRIDGE_ORIGIN_PREFIX)
            ?: return ownerApprovals.grantedScopes(task)
        val credential = bridgeCredentials.load(deviceId) ?: return ownerApprovals.grantedScopes(task)
        return try {
            ownerApprovals.grantedScopes(task) + credential.scopes
        } finally {
            credential.hmacSecret.fill(0)
        }
    }

    override fun approvalGrant(
        task: GlafiraTask,
        actionSummary: String,
        nowMillis: Long
    ): ToolApprovalGrant? = ownerApprovals.approvalGrant(task, actionSummary, nowMillis)

    override fun peekApprovalGrant(
        task: GlafiraTask,
        actionSummary: String,
        nowMillis: Long
    ): ToolApprovalGrant? = ownerApprovals.peekApprovalGrant(task, actionSummary, nowMillis)

    override fun requestApproval(
        task: GlafiraTask,
        actionSummary: String,
        nowMillis: Long
    ): Boolean = ownerApprovals.requestApproval(task, actionSummary, nowMillis)

    companion object {
        private const val BRIDGE_ORIGIN_PREFIX = "android-desktop-bridge:"
    }
}
