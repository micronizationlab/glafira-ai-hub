package com.micronizationlab.glafira

/**
 * Trusted in-process admission pipeline for Bridge requests.
 *
 * This is the only intended entry point for a future Bridge host: it resolves credentials
 * from local encrypted storage, applies Core authorization, then atomically claims the
 * idempotency key before returning an admissible result to an operation dispatcher.
 *
 * It does not execute operations or create owner-approval grants.
 */
class GlafiraBridgeRequestAdmission(
    private val credentials: AndroidKeystoreBridgeCredentialStore,
    private val replayGuard: GlafiraBridgeReplayGuard
) {
    fun admit(
        request: GlafiraBridgeRequest,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraBridgeResponse {
        fun failed(code: String) = GlafiraBridgeResponse(
            protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
            requestId = request.requestId,
            status = GlafiraBridgeStatus.FAILED,
            errorCode = code
        )

        val credential = credentials.load(request.deviceId)
            ?: return failed("DEVICE_NOT_PAIRED")

        val authorization = GlafiraBridgeCoreGate.authorize(
            request = request,
            authenticatedDeviceId = credential.deviceId,
            deviceSecret = credential.hmacSecret,
            grantedScopes = credential.scopes,
            nowEpochMs = nowEpochMs
        )
        val response = authorization.response
        if (response.status != GlafiraBridgeStatus.ACCEPTED &&
            response.status != GlafiraBridgeStatus.PENDING_APPROVAL
        ) return response

        return when (
            replayGuard.claim(
                authenticatedDeviceId = credential.deviceId,
                idempotencyKey = request.idempotencyKey,
                expiresAtEpochMs = request.expiresAtEpochMs,
                nowEpochMs = nowEpochMs
            )
        ) {
            BridgeReplayClaim.ACCEPTED -> response
            BridgeReplayClaim.DUPLICATE -> failed("DUPLICATE_REQUEST")
            BridgeReplayClaim.INVALID_EXPIRY -> failed("INVALID_REPLAY_CLAIM")
            BridgeReplayClaim.CAPACITY_EXCEEDED -> failed("REPLAY_GUARD_CAPACITY_EXCEEDED")
            BridgeReplayClaim.STORAGE_ERROR -> failed("REPLAY_GUARD_STORAGE_ERROR")
        }
    }
}
