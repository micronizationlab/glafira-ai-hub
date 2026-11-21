package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
        require(invitationJson.toByteArray().size in 1..MAX_JSON_BYTES &&
            enrollmentResponseJson.toByteArray().size in 1..MAX_JSON_BYTES) {
            "PAIRING_TRUST_BUNDLE_SIZE_INVALID"
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
            require(secret.size == 32 && exactString(response, "agentId") == deviceId &&
                exactString(response, "enrollmentId") == ticketId &&
                validIdentifier(exactString(response, "desktopHubId")) &&
                exactString(response, "endpoint") == endpoint &&
                exactString(response, "tlsCertificateSha256") == tlsPin &&
                responseScopes.isNotEmpty() && responseScopes.all(invitationScopes::contains) &&
                enrolledAt <= nowEpochMs && credentialExpiry > nowEpochMs &&
                anchor(response) == anchor) {
                "PAIRING_RESPONSE_CORRELATION_INVALID"
            }
        } finally {
            secret.fill(0)
        }
        return GlafiraPinnedPairingTrustBinding(deviceId, responseScopes, anchor)
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

enum class GlafiraOwnershipTrustInstallResult { INSTALLED, ROTATED, UNCHANGED }

/**
 * Android client-side ownership trust store. Records are encrypted by a non-exportable
 * Android Keystore AES-GCM key and atomically persisted outside backup/restore.
 */
class AndroidGlafiraOwnershipTrustStore(
    context: Context,
    trustFile: File = File(context.noBackupFilesDir, DEFAULT_FILE_NAME),
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : GlafiraOwnershipTrustedKeyResolver {
    private val file = AtomicFile(trustFile)
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    fun install(binding: GlafiraPinnedPairingTrustBinding): GlafiraOwnershipTrustInstallResult {
        val anchor = binding.anchor.also(GlafiraOwnershipTrustValidation::validate)
        require("ownership.read" in binding.scopes) { "OWNERSHIP_TRUST_SCOPE_NOT_GRANTED" }
        val anchors = readAll().toMutableMap()
        val existing = anchors[anchor.ownerCoreId]
        if (existing != null) {
            require(anchor.signingKeyEpoch >= existing.signingKeyEpoch) {
                "OWNERSHIP_TRUST_EPOCH_ROLLBACK"
            }
            if (anchor.signingKeyEpoch == existing.signingKeyEpoch) {
                require(anchor == existing) { "OWNERSHIP_TRUST_EPOCH_CONFLICT" }
                return GlafiraOwnershipTrustInstallResult.UNCHANGED
            }
        }
        require(existing != null || anchors.size < MAX_ANCHORS) { "OWNERSHIP_TRUST_LIMIT_EXCEEDED" }
        anchors[anchor.ownerCoreId] = anchor
        writeAll(anchors)
        return if (existing == null) GlafiraOwnershipTrustInstallResult.INSTALLED
        else GlafiraOwnershipTrustInstallResult.ROTATED
    }

    @Synchronized
    override fun resolve(
        oldOwnerCoreId: String,
        signingKeyEpoch: Long,
        fingerprintSha256: String
    ): PublicKey? {
        val anchor = readAll()[oldOwnerCoreId] ?: return null
        if (anchor.signingKeyEpoch != signingKeyEpoch ||
            anchor.signingPublicKeySha256 != fingerprintSha256) return null
        return anchor.publicKey()
    }

    @Synchronized
    fun revoke(ownerCoreId: String): Boolean {
        val anchors = readAll().toMutableMap()
        if (anchors.remove(ownerCoreId) == null) return false
        writeAll(anchors)
        return true
    }

    /** Commits an empty trust set before deleting both ciphertext and wrapping key. */
    @Synchronized
    fun revokeAll() {
        try {
            if (file.baseFile.exists() && keyStore.containsAlias(keyAlias)) writeAll(emptyMap())
        } finally {
            file.delete()
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    private fun readAll(): Map<String, GlafiraOwnershipTrustAnchor> {
        val encoded = try {
            file.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return emptyMap()
        }
        require(encoded.size in 1..MAX_ENCRYPTED_BYTES) { "OWNERSHIP_TRUST_FILE_SIZE_INVALID" }
        val wrapper = runCatching { JSONObject(encoded.toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalStateException("OWNERSHIP_TRUST_FILE_CORRUPT", it) }
        require(wrapper.keys().asSequence().toSet() == setOf("version", "iv", "ciphertext") &&
            wrapper.get("version") == 1) { "OWNERSHIP_TRUST_FILE_SCHEMA_INVALID" }
        val iv = decode(wrapper, "iv")
        val ciphertext = decode(wrapper, "ciphertext")
        var plaintext: ByteArray? = null
        try {
            require(iv.size == GCM_IV_BYTES) { "OWNERSHIP_TRUST_IV_INVALID" }
            plaintext = decrypt(existingKey(), iv, ciphertext)
            val root = JSONObject(plaintext.toString(Charsets.UTF_8))
            require(root.keys().asSequence().toSet() == setOf("schemaVersion", "anchors") &&
                root.get("schemaVersion") == 1) { "OWNERSHIP_TRUST_SCHEMA_INVALID" }
            val array = root.get("anchors")
            require(array is JSONArray && array.length() <= MAX_ANCHORS) {
                "OWNERSHIP_TRUST_LIMIT_EXCEEDED"
            }
            return buildMap {
                repeat(array.length()) {
                    val value = array.getJSONObject(it)
                    require(value.keys().asSequence().toSet() == setOf(
                        "ownerCoreId", "signingKeyEpoch", "signingPublicKeySpkiBase64",
                        "signingPublicKeySha256"
                    )) { "OWNERSHIP_TRUST_RECORD_SCHEMA_INVALID" }
                    val ownerCoreId = value.get("ownerCoreId")
                    val keyEpoch = value.get("signingKeyEpoch")
                    val publicKey = value.get("signingPublicKeySpkiBase64")
                    val fingerprint = value.get("signingPublicKeySha256")
                    require(ownerCoreId is String && (keyEpoch is Int || keyEpoch is Long) &&
                        publicKey is String && fingerprint is String) {
                        "OWNERSHIP_TRUST_RECORD_TYPE_INVALID"
                    }
                    val anchor = GlafiraOwnershipTrustAnchor(
                        ownerCoreId,
                        (keyEpoch as Number).toLong(),
                        publicKey,
                        fingerprint
                    ).also(GlafiraOwnershipTrustValidation::validate)
                    require(put(anchor.ownerCoreId, anchor) == null) {
                        "OWNERSHIP_TRUST_DUPLICATE_CORE"
                    }
                }
            }
        } catch (failure: Exception) {
            throw IllegalStateException("OWNERSHIP_TRUST_FILE_INVALID", failure)
        } finally {
            encoded.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun writeAll(anchors: Map<String, GlafiraOwnershipTrustAnchor>) {
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("anchors", JSONArray().apply {
                anchors.toSortedMap().values.forEach { anchor ->
                    put(JSONObject().apply {
                        put("ownerCoreId", anchor.ownerCoreId)
                        put("signingKeyEpoch", anchor.signingKeyEpoch)
                        put("signingPublicKeySpkiBase64", anchor.signingPublicKeySpkiBase64)
                        put("signingPublicKeySha256", anchor.signingPublicKeySha256)
                    })
                }
            })
        }
        val plaintext = root.toString().toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(getOrCreateKey(), plaintext)
        val output = JSONObject().apply {
            put("version", 1)
            put("iv", Base64.getEncoder().encodeToString(encrypted.first))
            put("ciphertext", Base64.getEncoder().encodeToString(encrypted.second))
        }.toString().toByteArray(Charsets.UTF_8)
        var stream: FileOutputStream? = null
        try {
            val opened = file.startWrite()
            stream = opened
            opened.write(output)
            opened.fd.sync()
            file.finishWrite(opened)
        } catch (failure: Throwable) {
            stream?.let(file::failWrite)
            throw failure
        } finally {
            plaintext.fill(0)
            encrypted.first.fill(0)
            encrypted.second.fill(0)
            output.fill(0)
        }
    }

    private fun decode(wrapper: JSONObject, field: String): ByteArray = runCatching {
        val value = wrapper.get(field)
        require(value is String)
        Base64.getDecoder().decode(value).also {
            require(Base64.getEncoder().encodeToString(it) == value)
        }
    }.getOrElse { throw IllegalStateException("OWNERSHIP_TRUST_ENCODING_INVALID", it) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey = keyStore.getKey(keyAlias, null) as? SecretKey
        ?: throw IllegalStateException("OWNERSHIP_TRUST_KEY_MISSING")

    private fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val DEFAULT_FILE_NAME = "ownership-trust-v1.enc"
        private const val DEFAULT_KEY_ALIAS = "glafira.ownership.trust.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_ANCHORS = 32
        private const val MAX_ENCRYPTED_BYTES = 128 * 1024
        private val AAD = "GLAFIRA.Android.OwnershipTrust.v1".toByteArray(Charsets.UTF_8)
    }
}
