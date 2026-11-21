package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class GlafiraWindowsHubPairingTrustBindingTest {
    private val keyPair = keyPair()
    private val now = 1_800_000_000_000L

    @Test
    fun exactInvitationAndPinnedResponseProduceOwnershipTrustBinding() {
        val invitation = invitation()
        val response = response()

        val binding = GlafiraWindowsHubPairingTrustBinding.parse(
            invitation.toString(), response.toString(), now
        )

        assertEquals("android-device-01", binding.deviceId)
        assertEquals(setOf("ownership.read", "project.read"), binding.scopes)
        assertEquals("desktop-core-01", binding.anchor.ownerCoreId)
        assertEquals(7L, binding.anchor.signingKeyEpoch)
        assertArrayEquals(keyPair.public.encoded, binding.anchor.publicKey().encoded)
    }

    @Test
    fun substitutedKeyEpochFingerprintOrResponseIdentityFailsClosed() {
        val cases = listOf(
            response().put("ownershipSigningKeyEpoch", 8),
            response().put("ownershipSigningPublicKeySha256", "0".repeat(64)),
            response().put("agentId", "android-device-02"),
            response().put("scopes", JSONArray(listOf("admin.root")))
        )

        cases.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                GlafiraWindowsHubPairingTrustBinding.parse(
                    invitation().toString(), candidate.toString(), now
                )
            }
        }
    }

    @Test
    fun unknownFractionalExpiredAndPublicEndpointInvitationFailsBeforeTrust() {
        val cases = listOf(
            invitation().put("unexpected", true),
            invitation().put("ownershipSigningKeyEpoch", 7.5),
            invitation().put("expiresAtEpochMs", now),
            invitation().put("endpoint", "https://8.8.8.8:8443/bridge/v1")
        )

        cases.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                GlafiraWindowsHubPairingTrustBinding.parse(
                    candidate.toString(), response().toString(), now
                )
            }
        }
    }

    private fun invitation(pair: KeyPair = keyPair, epoch: Long = 7) = JSONObject().apply {
        put("version", 1)
        put("agentId", "android-device-01")
        put("endpoint", "https://192.168.1.20:8443/bridge/v1")
        put("tlsCertificateSha256", "a".repeat(64))
        put("ownerCoreId", "desktop-core-01")
        put("ownershipSigningKeyEpoch", epoch)
        put("ownershipSigningPublicKeySpkiBase64", Base64.getEncoder().encodeToString(pair.public.encoded))
        put("ownershipSigningPublicKeySha256", fingerprint(pair.public.encoded))
        put("ticketId", "0123456789abcdef0123456789abcdef")
        put("pairingCode", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x2a }))
        put("scopes", JSONArray(listOf("ownership.read", "project.read")))
        put("expiresAtEpochMs", now + 120_000)
    }

    private fun response(pair: KeyPair = keyPair, epoch: Long = 7) = JSONObject().apply {
        put("version", 1)
        put("status", "ENROLLED")
        put("errorCode", JSONObject.NULL)
        put("agentId", "android-device-01")
        put("enrollmentId", "0123456789abcdef0123456789abcdef")
        put("desktopHubId", "desktop-hub-01")
        put("endpoint", "https://192.168.1.20:8443/bridge/v1")
        put("tlsCertificateSha256", "a".repeat(64))
        put("ownerCoreId", "desktop-core-01")
        put("ownershipSigningKeyEpoch", epoch)
        put("ownershipSigningPublicKeySpkiBase64", Base64.getEncoder().encodeToString(pair.public.encoded))
        put("ownershipSigningPublicKeySha256", fingerprint(pair.public.encoded))
        put("sharedSecretBase64", Base64.getEncoder().encodeToString(ByteArray(32) { 0x5c }))
        put("scopes", JSONArray(listOf("ownership.read", "project.read")))
        put("enrolledAtEpochMs", now - 1_000)
        put("expiresAtEpochMs", now + 30 * 24 * 60 * 60 * 1_000L)
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun fingerprint(value: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }
}
