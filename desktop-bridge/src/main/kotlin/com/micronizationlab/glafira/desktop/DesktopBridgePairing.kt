package com.micronizationlab.glafira.desktop

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** One-time proof payload for POST /bridge/v1/pairing. The capability is never serialized. */
data class DesktopBridgeEnrollmentRequest(
    val ticketId: String,
    val clientNonce: String,
    val proofHex: String,
    val deviceLabel: String,
    val platform: String = "DESKTOP",
    val requestedScopes: Set<String>
) {
    fun toJsonBytes(): ByteArray = JSONObject().apply {
        put("ticketId", ticketId)
        put("clientNonce", clientNonce)
        put("proofHex", proofHex)
        put("deviceLabel", deviceLabel)
        put("platform", platform)
        put("requestedScopes", org.json.JSONArray(requestedScopes.sorted()))
    }.toString().toByteArray(Charsets.UTF_8)
}

/**
 * Desktop-side pairing proof helper. The invitation capability is used only in memory to
 * derive the proof; the Android owner must still approve the enrollment and select scopes.
 */
object DesktopBridgePairing {
    private const val CAPABILITY_BYTES = 32
    private const val NONCE_BYTES = 32
    private const val MAX_LABEL_LENGTH = 64
    private val random = SecureRandom()

    fun createEnrollmentRequest(
        ticketId: String,
        pairingCapability: String,
        deviceLabel: String,
        requestedScopes: Set<String>
    ): DesktopBridgeEnrollmentRequest {
        require(ticketId.length in 16..128 && ticketId.all { it.isLetterOrDigit() || it in "-_" })
        require(deviceLabel.trim().length in 1..MAX_LABEL_LENGTH && deviceLabel.none(Char::isISOControl))
        require(requestedScopes.size <= 32 && requestedScopes.all(DesktopBridgeScopePolicy::isValidScope))
        val capability = Base64.getUrlDecoder().decode(pairingCapability)
        require(capability.size == CAPABILITY_BYTES)
        val nonceBytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val verifier = MessageDigest.getInstance("SHA-256").digest(capability)
        capability.fill(0)
        val message = "GLAFIRA-BRIDGE-PAIRING-V1\u0000$ticketId\u0000$nonce".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(verifier, "HmacSHA256"))
        val proof = mac.doFinal(message).joinToString("") { "%02x".format(it) }
        verifier.fill(0)
        nonceBytes.fill(0)
        return DesktopBridgeEnrollmentRequest(
            ticketId = ticketId,
            clientNonce = nonce,
            proofHex = proof,
            deviceLabel = deviceLabel.trim(),
            requestedScopes = requestedScopes.toSet()
        )
    }
}


data class DesktopBridgePairingSession(
    val origin: String,
    val serverCertificateSha256: String,
    val enrollment: DesktopBridgeEnrollmentRequest
)

fun DesktopBridgePairing.fromInvitationBundle(
    bundleJson: String,
    deviceLabel: String,
    requestedScopes: Set<String>,
    nowEpochMs: Long = System.currentTimeMillis()
): DesktopBridgePairingSession {
    val json = DesktopBridgeJson.parseExactObject(
        bundleJson,
        maxBytes = 8 * 1024,
        expectedKeys = setOf(
            "format", "origin", "ticketId", "pairingCapability",
            "serverCertificateSha256", "expiresAtEpochMs"
        )
    )
    require(json.getString("format") == "GLAFIRA_BRIDGE_INVITATION_V1")
    val expiry = json.getLong("expiresAtEpochMs")
    require(expiry > nowEpochMs && expiry - nowEpochMs <= GlafiraPairingMaximumLifetimeMs)
    val origin = json.getString("origin")
    val uri = java.net.URI(origin)
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    val pin = json.getString("serverCertificateSha256")
    require(pin.matches(Regex("[0-9a-fA-F]{64}")))
    val ticketId = json.getString("ticketId")
    val capability = json.getString("pairingCapability")
    return DesktopBridgePairingSession(
        origin,
        pin.lowercase(),
        createEnrollmentRequest(ticketId, capability, deviceLabel, requestedScopes)
    )
}

private const val GlafiraPairingMaximumLifetimeMs = 120_000L
