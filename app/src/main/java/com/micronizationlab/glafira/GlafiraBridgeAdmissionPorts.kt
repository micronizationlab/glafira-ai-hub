package com.micronizationlab.glafira

/**
 * Narrow ports keep the Bridge admission policy testable without Android services.
 * Implementations must return credentials only from a trusted enrollment store.
 */
data class GlafiraBridgeDeviceCredential(
    val deviceId: String,
    val hmacSecret: ByteArray,
    val scopes: Set<String>
)

interface GlafiraBridgeCredentialProvider {
    fun load(deviceId: String): GlafiraBridgeDeviceCredential?
}

interface GlafiraBridgeReplayStore {
    fun claim(
        authenticatedDeviceId: String,
        idempotencyKey: String,
        expiresAtEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): BridgeReplayClaim
}
