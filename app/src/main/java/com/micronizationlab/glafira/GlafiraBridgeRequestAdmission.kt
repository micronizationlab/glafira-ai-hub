package com.micronizationlab.glafira

/**
 * Trusted in-process admission pipeline for Bridge requests.
 *
 * This is the only intended entry point for a future Bridge host: it resolves credentials
 * from local encrypted storage and applies Core authorization. Control operations use
 * the replay ledger; durable Core submissions use the task DB's atomic enqueue receipt.
 *
 * It does not execute operations or create owner-approval grants.
 */
class GlafiraBridgeRequestAdmission(
    private val credentials: GlafiraBridgeCredentialProvider,
    private val replayGuard: GlafiraBridgeReplayStore
) {
    fun admit(
        request: GlafiraBridgeRequest,
        nowEpochMs: Long = System.currentTimeMillis(),
        authenticatedDeviceRateLimit: ((String) -> Boolean)? = null
    ): GlafiraBridgeResponse {
        val response = authorize(request, nowEpochMs, authenticatedDeviceRateLimit)
        if (response.status != GlafiraBridgeStatus.ACCEPTED &&
            response.status != GlafiraBridgeStatus.PENDING_APPROVAL
        ) return response
        return when (
            replayGuard.claim(
                authenticatedDeviceId = request.deviceId,
                idempotencyKey = request.idempotencyKey,
                expiresAtEpochMs = request.expiresAtEpochMs,
                nowEpochMs = nowEpochMs
            )
        ) {
            BridgeReplayClaim.ACCEPTED -> response
            BridgeReplayClaim.DUPLICATE -> failed(request, "DUPLICATE_REQUEST")
            BridgeReplayClaim.INVALID_EXPIRY -> failed(request, "INVALID_REPLAY_CLAIM")
            BridgeReplayClaim.CAPACITY_EXCEEDED -> failed(request, "REPLAY_GUARD_CAPACITY_EXCEEDED")
            BridgeReplayClaim.STORAGE_ERROR -> failed(request, "REPLAY_GUARD_STORAGE_ERROR")
        }
    }

    /**
     * Only the three enqueue-only Core operations may use this path. Admission itself
     * creates no replay claim: until the task row commits, a disconnected caller can
     * safely resubmit. The ingress must return a matching durable task receipt before
     * HTTPS reports acceptance; it must never dispatch this response directly.
     */
    fun admitDurableTask(
        request: GlafiraBridgeRequest,
        nowEpochMs: Long = System.currentTimeMillis(),
        authenticatedDeviceRateLimit: ((String) -> Boolean)? = null
    ): GlafiraBridgeResponse {
        if (!GlafiraBridgeDurableTaskIdentity.supports(request.operation)) {
            return failed(request, "DURABLE_CORE_OPERATION_REQUIRED")
        }
        return authorize(request, nowEpochMs, authenticatedDeviceRateLimit)
    }

    private fun authorize(
        request: GlafiraBridgeRequest,
        nowEpochMs: Long,
        authenticatedDeviceRateLimit: ((String) -> Boolean)?
    ): GlafiraBridgeResponse {
        val credential = credentials.load(request.deviceId)
            ?: return failed(request, "DEVICE_NOT_PAIRED")

        val authorization = try {
            GlafiraBridgeCoreGate.authorize(
                request = request,
                authenticatedDeviceId = credential.deviceId,
                deviceSecret = credential.hmacSecret,
                grantedScopes = credential.scopes,
                nowEpochMs = nowEpochMs
            )
        } finally {
            credential.hmacSecret.fill(0)
        }
        val response = authorization.response
        if (response.status != GlafiraBridgeStatus.ACCEPTED &&
            response.status != GlafiraBridgeStatus.PENDING_APPROVAL
        ) return response

        if (authenticatedDeviceRateLimit != null &&
            !authenticatedDeviceRateLimit(credential.deviceId)
        ) return failed(request, "DEVICE_RATE_LIMITED")

        return response
    }

    private fun failed(request: GlafiraBridgeRequest, code: String) = GlafiraBridgeResponse(
        protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
        requestId = request.requestId,
        status = GlafiraBridgeStatus.FAILED,
        errorCode = code
    )
}
