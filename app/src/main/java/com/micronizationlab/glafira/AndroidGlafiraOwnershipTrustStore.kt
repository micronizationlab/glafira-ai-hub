package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class GlafiraOwnershipTrustAnchor(
    val ownerCoreId: String,
    val signingKeyEpoch: Long,
    val signingPublicKeySpkiBase64: String,
    val signingPublicKeySha256: String
) {
    fun publicKey(): PublicKey = GlafiraOwnershipTrustValidation.publicKey(this)
}

/**
 * Can be created only by exact correlation of an owner-created Windows Hub invitation
 * with its TLS-pinned enrollment response. The ownership transfer response itself is
 * never a source of signing-key trust.
 */
class GlafiraPinnedPairingTrustBinding internal constructor(
    val deviceId: String,
    val scopes: Set<String>,
    val anchor: GlafiraOwnershipTrustAnchor
)

internal class GlafiraWindowsHubPairingInvitation(
    val deviceId: String,
    val endpoint: String,
    val tlsCertificateSha256: String,
    val ticketId: String,
    val pairingCode: String,
    val scopes: Set<String>,
    val expiresAtEpochMs: Long,
    val anchor: GlafiraOwnershipTrustAnchor
)

internal class GlafiraPinnedPairingEnrollment(
    val deviceId: String,
    val desktopHubId: String,
    val endpoint: String,
    val tlsCertificateSha256: String,
    private val hmacSecret: ByteArray,
    val scopes: Set<String>,
    val enrolledAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val anchor: GlafiraOwnershipTrustAnchor
) {
    fun secretCopy(): ByteArray = hmacSecret.copyOf()
    fun clearSecret() = hmacSecret.fill(0)
}

/** Strict parser for the Windows Hub pairing trust material consumed by Android clients. */
object GlafiraWindowsHubPairingTrustBinding {
    private val invitationFields = setOf(
        "version", "agentId", "endpoint", "tlsCertificateSha256", "ownerCoreId",
        "ownershipSigningKeyEpoch", "ownershipSigningPublicKeySpkiBase64",
        "ownershipSigningPublicKeySha256", "ticketId", "pairingCode", "scopes",
        "expiresAtEpochMs"
    )
    private val responseFields = setOf(
        "version", "status", "errorCode", "agentId", "enrollmentId", "desktopHubId",
        "endpoint", "tlsCertificateSha256", "ownerCoreId", "ownershipSigningKeyEpoch",
        "ownershipSigningPublicKeySpkiBase64", "ownershipSigningPublicKeySha256",
        "sharedSecretBase64", "scopes", "enrolledAtEpochMs", "expiresAtEpochMs"
    )

    fun parse(
        invitationJson: String,
        enrollmentResponseJson: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraPinnedPairingTrustBinding {
        val invitation = parseInvitation(invitationJson, nowEpochMs)
        val enrollment = correlate(invitation, enrollmentResponseJson, nowEpochMs)
        return try {
            GlafiraPinnedPairingTrustBinding(
                enrollment.deviceId,
                enrollment.scopes,
                enrollment.anchor
            )
        } finally {
            enrollment.clearSecret()
        }
    }

    internal fun parseInvitation(
        invitationJson: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraWindowsHubPairingInvitation {
        require(invitationJson.toByteArray().size in 1..MAX_JSON_BYTES) {
            "PAIRING_INVITATION_SIZE_INVALID"
        }
        val invitation = parseObject(invitationJson, "PAIRING_INVITATION_JSON_INVALID")
        require(invitation.keys().asSequence().toSet() == invitationFields) {
            "PAIRING_INVITATION_FIELDS_INVALID"
        }
        require(exactLong(invitation, "version") == 1L) { "PAIRING_VERSION_UNSUPPORTED" }
        val deviceId = exactString(invitation, "agentId")
        val endpoint = exactString(invitation, "endpoint")
        val tlsPin = exactString(invitation, "tlsCertificateSha256")
        val ticketId = exactString(invitation, "ticketId")
        val pairingCode = exactString(invitation, "pairingCode")
        val invitationScopes = exactScopes(invitation, "scopes")
        val invitationExpiry = exactLong(invitation, "expiresAtEpochMs")
        val anchor = anchor(invitation)
        require(validIdentifier(deviceId) && validWindowsHubEndpoint(endpoint) &&
            tlsPin.matches(LOWER_SHA256) && ticketId.matches(LOWER_TICKET) &&
            validPairingCode(pairingCode) && invitationExpiry > nowEpochMs &&
            invitationExpiry - nowEpochMs <= MAX_INVITATION_LIFETIME_MS) {
            "PAIRING_INVITATION_IDENTITY_INVALID"
        }

        return GlafiraWindowsHubPairingInvitation(
            deviceId,
            endpoint,
            tlsPin,
            ticketId,
            pairingCode,
            invitationScopes,
            invitationExpiry,
            anchor
        )
    }

    internal fun correlate(
        invitation: GlafiraWindowsHubPairingInvitation,
        enrollmentResponseJson: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraPinnedPairingEnrollment {
        require(enrollmentResponseJson.toByteArray().size in 1..MAX_JSON_BYTES) {
            "PAIRING_RESPONSE_SIZE_INVALID"
        }
        val response = parseObject(enrollmentResponseJson, "PAIRING_RESPONSE_JSON_INVALID")
        require(response.keys().asSequence().toSet() == responseFields) {
            "PAIRING_RESPONSE_FIELDS_INVALID"
        }
        require(exactLong(response, "version") == 1L &&
            exactString(response, "status") == "ENROLLED" && response.isNull("errorCode")) {
            "PAIRING_RESPONSE_STATUS_INVALID"
        }
        val responseScopes = exactScopes(response, "scopes")
        val enrolledAt = exactLong(response, "enrolledAtEpochMs")
        val credentialExpiry = exactLong(response, "expiresAtEpochMs")
        val secretText = exactString(response, "sharedSecretBase64")
        val secret = decodeCanonicalBase64(secretText, "PAIRING_SECRET_INVALID")
        try {
            require(secret.size == 32 && exactString(response, "agentId") == invitation.deviceId &&
                exactString(response, "enrollmentId") == invitation.ticketId &&
                validIdentifier(exactString(response, "desktopHubId")) &&
                exactString(response, "endpoint") == invitation.endpoint &&
                exactString(response, "tlsCertificateSha256") ==
                    invitation.tlsCertificateSha256 &&
                responseScopes.isNotEmpty() && responseScopes.all(invitation.scopes::contains) &&
                enrolledAt <= nowEpochMs && credentialExpiry > nowEpochMs &&
                anchor(response) == invitation.anchor) {
                "PAIRING_RESPONSE_CORRELATION_INVALID"
            }
            return GlafiraPinnedPairingEnrollment(
                invitation.deviceId,
                exactString(response, "desktopHubId"),
                invitation.endpoint,
                invitation.tlsCertificateSha256,
                secret,
                responseScopes,
                enrolledAt,
                credentialExpiry,
                invitation.anchor
            )
        } catch (failure: Exception) {
            secret.fill(0)
            throw failure
        }
    }

    private fun anchor(value: JSONObject) = GlafiraOwnershipTrustAnchor(
        ownerCoreId = exactString(value, "ownerCoreId"),
        signingKeyEpoch = exactLong(value, "ownershipSigningKeyEpoch"),
        signingPublicKeySpkiBase64 = exactString(value, "ownershipSigningPublicKeySpkiBase64"),
        signingPublicKeySha256 = exactString(value, "ownershipSigningPublicKeySha256")
    ).also(GlafiraOwnershipTrustValidation::validate)

    private fun parseObject(text: String, error: String): JSONObject =
        runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException(error, it) }

    private fun exactString(value: JSONObject, name: String): String = value.get(name).let {
        require(it is String) { "PAIRING_FIELD_TYPE_INVALID:$name" }
        it
    }

    private fun exactLong(value: JSONObject, name: String): Long = value.get(name).let {
        require(it is Int || it is Long) { "PAIRING_FIELD_TYPE_INVALID:$name" }
        (it as Number).toLong()
    }

    private fun exactScopes(value: JSONObject, name: String): Set<String> {
        val array = value.get(name)
        require(array is JSONArray && array.length() in 1..32) { "PAIRING_SCOPES_INVALID" }
        val ordered = buildList {
            repeat(array.length()) { add(array.get(it).let { item ->
                require(item is String) { "PAIRING_SCOPE_TYPE_INVALID" }
                item
            }) }
        }
        require(ordered.distinct().size == ordered.size && ordered.all(::validScope)) {
            "PAIRING_SCOPES_INVALID"
        }
        return ordered.toSet()
    }

    private fun validPairingCode(value: String): Boolean = runCatching {
        value.length == 43 && value.all { it.isAsciiAlphaNumeric() || it == '-' || it == '_' } &&
            Base64.getUrlDecoder().decode(value).size == 32 &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                Base64.getUrlDecoder().decode(value)
            ) == value
    }.getOrDefault(false)

    private fun validWindowsHubEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path == "/bridge/v1" && (uri.port == -1 || uri.port in 1..65535) && uri.host != null &&
            isPrivateLanLiteral(uri.host)
    }.getOrDefault(false)

    private fun isPrivateLanLiteral(host: String): Boolean {
        val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }
        if (ipv4.size == 4 && ipv4.all { it in 0..255 }) {
            return ipv4[0] == 10 ||
                (ipv4[0] == 172 && ipv4[1] in 16..31) ||
                (ipv4[0] == 192 && ipv4[1] == 168) ||
                (ipv4[0] == 169 && ipv4[1] == 254)
        }
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]").substringBefore('%')
        return normalized.startsWith("fc") || normalized.startsWith("fd") ||
            normalized.startsWith("fe8") || normalized.startsWith("fe9") ||
            normalized.startsWith("fea") || normalized.startsWith("feb")
    }

    private fun validIdentifier(value: String): Boolean = value.length in 8..128 &&
        value.all { it.code <= 0x7f && (it.isAsciiAlphaNumeric() || it in "-_.") }

    private fun validScope(value: String): Boolean = value.length in 1..96 &&
        value.all { it.code <= 0x7f && (it.isAsciiAlphaNumeric() || it in "-_.") }

    private fun Char.isAsciiAlphaNumeric(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun decodeCanonicalBase64(value: String, error: String): ByteArray = runCatching {
        Base64.getDecoder().decode(value).also {
            require(Base64.getEncoder().encodeToString(it) == value) { error }
        }
    }.getOrElse { throw IllegalArgumentException(error, it) }

    private val LOWER_SHA256 = Regex("^[a-f0-9]{64}$")
    private val LOWER_TICKET = Regex("^[a-f0-9]{32}$")
    private const val MAX_JSON_BYTES = 8_192
    private const val MAX_INVITATION_LIFETIME_MS = 10 * 60 * 1_000L
}

internal object GlafiraOwnershipTrustValidation {
    fun validate(anchor: GlafiraOwnershipTrustAnchor) {
        require(anchor.ownerCoreId.length in 8..128 && anchor.ownerCoreId.all {
            it.code <= 0x7f && (it.isLetterOrDigit() || it in "-_.")
        }) { "OWNERSHIP_TRUST_CORE_ID_INVALID" }
        require(anchor.signingKeyEpoch > 0) { "OWNERSHIP_TRUST_EPOCH_INVALID" }
        require(anchor.signingPublicKeySha256.matches(Regex("^[a-f0-9]{64}$"))) {
            "OWNERSHIP_TRUST_FINGERPRINT_INVALID"
        }
        publicKey(anchor)
    }

    fun publicKey(anchor: GlafiraOwnershipTrustAnchor): PublicKey {
        val encoded = runCatching { Base64.getDecoder().decode(anchor.signingPublicKeySpkiBase64) }
            .getOrElse { throw IllegalArgumentException("OWNERSHIP_TRUST_KEY_ENCODING_INVALID", it) }
        try {
            require(Base64.getEncoder().encodeToString(encoded) == anchor.signingPublicKeySpkiBase64) {
                "OWNERSHIP_TRUST_KEY_ENCODING_INVALID"
            }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(encoded)
                .joinToString("") { "%02x".format(it) }
            require(fingerprint == anchor.signingPublicKeySha256) {
                "OWNERSHIP_TRUST_KEY_FINGERPRINT_MISMATCH"
            }
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            require(key is ECPublicKey && key.params.curve.field.fieldSize == 256) {
                "OWNERSHIP_TRUST_KEY_MUST_BE_P256"
            }
            return key
        } finally {
            encoded.fill(0)
        }
    }
}
