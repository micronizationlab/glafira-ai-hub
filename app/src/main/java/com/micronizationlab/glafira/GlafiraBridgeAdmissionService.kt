package com.micronizationlab.glafira

/**
 * Authenticated Bridge admission pipeline shared by every transport:
 * credential lookup -> request/HMAC/scope validation -> durable replay claim -> operation dispatch.
 * Network listeners must call this service rather than dispatching requests directly.
 */
class GlafiraBridgeAdmissionService(
    private val credentialProvider: GlafiraBridgeCredentialProvider,
    private val replayStore: GlafiraBridgeReplayStore,
    private val dispatch: (GlafiraBridgeRequest) -> GlafiraBridgeResponse
) {
    fun handle(
        request: GlafiraBridgeRequest,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraBridgeResponse {
        val credential = credentialProvider.load(request.deviceId)
            ?: return failed(request, "AUTHENTICATION_FAILED")
        try {
            val authorization = GlafiraBridgeCoreGate.authorize(
                request = request,
                authenticatedDeviceId = credential.deviceId,
                deviceSecret = credential.hmacSecret,
                grantedScopes = credential.scopes,
                nowEpochMs = nowEpochMs
            )
            if (authorization.error != null || authorization.response.status != GlafiraBridgeStatus.ACCEPTED) {
                return authorization.response
            }

            return when (replayStore.claim(
                authenticatedDeviceId = credential.deviceId,
                idempotencyKey = request.idempotencyKey,
                expiresAtEpochMs = request.expiresAtEpochMs,
                nowEpochMs = nowEpochMs
            )) {
                BridgeReplayClaim.ACCEPTED -> runCatching { dispatch(request) }
                    .getOrElse { failed(request, "OPERATION_FAILED") }
                BridgeReplayClaim.DUPLICATE -> failed(request, "DUPLICATE_REQUEST")
                BridgeReplayClaim.INVALID_EXPIRY -> failed(request, "INVALID_REQUEST_EXPIRY")
                BridgeReplayClaim.CAPACITY_EXCEEDED -> failed(request, "REPLAY_CAPACITY_EXCEEDED")
                BridgeReplayClaim.STORAGE_ERROR -> failed(request, "REPLAY_STORAGE_ERROR")
            }
        } finally {
            credential.hmacSecret.fill(0)
        }
    }

    private fun failed(request: GlafiraBridgeRequest, code: String) = GlafiraBridgeResponse(
        protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
        requestId = request.requestId,
        status = GlafiraBridgeStatus.FAILED,
        errorCode = code
    )
}
