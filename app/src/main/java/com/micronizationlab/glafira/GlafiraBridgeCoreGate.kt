package com.micronizationlab.glafira

/**
 * Pure authorization boundary for Bridge requests.
 *
 * authenticatedDeviceId, deviceSecret and grantedScopes MUST come from a future trusted
 * pairing/credential store, never from request fields or an LLM. This class deliberately
 * does not execute operations, persist approvals, or implement transport.
 */
enum class GlafiraBridgeAuthorizationError {
    INVALID_REQUEST,
    DEVICE_MISMATCH,
    AUTHENTICATION_FAILED,
    UNKNOWN_TOOL,
    APPROVAL_NOT_REQUIRED,
    APPROVAL_QUEUE_UNAVAILABLE,
    SCOPE_DENIED
}

data class GlafiraBridgeAuthorizationResult(
    val response: GlafiraBridgeResponse,
    val error: GlafiraBridgeAuthorizationError? = null
)

object GlafiraBridgeCoreGate {
    fun authorize(
        request: GlafiraBridgeRequest,
        authenticatedDeviceId: String,
        deviceSecret: ByteArray,
        grantedScopes: Set<String>,
        approvalGrant: ToolApprovalGrant? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraBridgeAuthorizationResult {
        fun denied(error: GlafiraBridgeAuthorizationError) =
            GlafiraBridgeAuthorizationResult(
                GlafiraBridgeResponse(
                    protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
                    requestId = request.requestId,
                    status = GlafiraBridgeStatus.FAILED,
                    errorCode = error.name
                ),
                error
            )

        if (GlafiraBridgeProtocol.validate(request, nowEpochMs) != null)
            return denied(GlafiraBridgeAuthorizationError.INVALID_REQUEST)
        if (request.deviceId != authenticatedDeviceId)
            return denied(GlafiraBridgeAuthorizationError.DEVICE_MISMATCH)
        if (!GlafiraBridgeProtocol.verifySignature(request, deviceSecret))
            return denied(GlafiraBridgeAuthorizationError.AUTHENTICATION_FAILED)

        if (request.operation == GlafiraBridgeOperation.APPROVAL_REQUEST) {
            if ("approval.request" !in grantedScopes)
                return denied(GlafiraBridgeAuthorizationError.SCOPE_DENIED)
            val targetTool = GlafiraToolRegistry.find(request.payload.getValue("toolId"))
                ?: return denied(GlafiraBridgeAuthorizationError.UNKNOWN_TOOL)
            if (!GlafiraToolRegistry.requiresOwnerApproval(targetTool.risk))
                return denied(GlafiraBridgeAuthorizationError.APPROVAL_NOT_REQUIRED)
            // Never report a pending approval until a durable owner-visible queue exists.
            return denied(GlafiraBridgeAuthorizationError.APPROVAL_QUEUE_UNAVAILABLE)
        }

        val toolId = when (request.operation) {
            GlafiraBridgeOperation.PROJECT_READ -> "project.read"
            GlafiraBridgeOperation.CONVERSATION_READ -> "conversation.read"
            GlafiraBridgeOperation.CONVERSATION_APPEND -> "conversation.append"
            GlafiraBridgeOperation.APPROVAL_REQUEST -> error("Handled above")
        }
        val definition = GlafiraToolRegistry.find(toolId)
            ?: return denied(GlafiraBridgeAuthorizationError.UNKNOWN_TOOL)
        if (!grantedScopes.containsAll(definition.requiredScopes))
            return denied(GlafiraBridgeAuthorizationError.SCOPE_DENIED)

        val actionSummary = when (request.operation) {
            GlafiraBridgeOperation.PROJECT_READ -> "Read project ${request.projectId}"
            GlafiraBridgeOperation.CONVERSATION_READ ->
                "Read conversation ${request.payload.getValue("conversationId")}"
            GlafiraBridgeOperation.CONVERSATION_APPEND ->
                "Append message to conversation ${request.payload.getValue("conversationId")}"
            GlafiraBridgeOperation.APPROVAL_REQUEST -> error("Handled above")
        }
        val decision = GlafiraToolRegistry.authorize(
            definition = definition,
            projectId = request.projectId,
            actionSummary = actionSummary,
            grant = approvalGrant,
            now = nowEpochMs
        )
        val status = when (decision) {
            ToolAuthorizationDecision.ALLOWED -> GlafiraBridgeStatus.ACCEPTED
            ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED -> GlafiraBridgeStatus.PENDING_APPROVAL
        }
        return GlafiraBridgeAuthorizationResult(
            GlafiraBridgeResponse(
                protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
                requestId = request.requestId,
                status = status,
                result = mapOf("authorizedTool" to toolId)
            )
        )
    }
}
