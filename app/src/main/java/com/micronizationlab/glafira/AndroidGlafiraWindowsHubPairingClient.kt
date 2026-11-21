package com.micronizationlab.glafira

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal fun interface GlafiraWindowsHubPairingExchange {
    fun redeem(invitation: GlafiraWindowsHubPairingInvitation): String
}

/**
 * Redeems a Windows Hub invitation only after a TLS handshake anchored to the
 * owner-provided leaf pin and the platform HTTPS hostname verifier both succeed.
 */
internal class AndroidGlafiraPinnedHttpsPairingExchange(
    private val connectionFactory: (URL) -> HttpsURLConnection = { url ->
        url.openConnection() as HttpsURLConnection
    }
) : GlafiraWindowsHubPairingExchange {
    override fun redeem(invitation: GlafiraWindowsHubPairingInvitation): String {
        val endpoint = URI(invitation.endpoint)
        val pairUrl = URL(
            endpoint.scheme,
            endpoint.host,
            endpoint.port,
            endpoint.path + "/pair"
        )
        val request = JSONObject().apply {
            put("version", 1)
            put("ticketId", invitation.ticketId)
            put("pairingCode", invitation.pairingCode)
            put("agentId", invitation.deviceId)
        }.toString().toByteArray(Charsets.UTF_8)
        require(request.size <= MAX_REQUEST_BYTES) { "PAIRING_REQUEST_SIZE_INVALID" }

        val connection = connectionFactory(pairUrl)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.sslSocketFactory = AndroidGlafiraPinnedHttpsSupport.socketFactory(
                invitation.tlsCertificateSha256
            )
            connection.setFixedLengthStreamingMode(request.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            // connect() performs the pinned trust and platform hostname checks. The
            // peer is checked again before getOutputStream(), so the pairing code is
            // never disclosed to an unpinned HTTPS endpoint.
            connection.connect()
            AndroidGlafiraPinnedHttpsSupport.verifyConnectedLeaf(
                connection,
                invitation.tlsCertificateSha256
            )

            connection.outputStream.use { it.write(request) }
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "PAIRING_HTTP_STATUS_INVALID"
            }
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            require(contentType == "application/json") { "PAIRING_CONTENT_TYPE_INVALID" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength == -1L || declaredLength in 1..MAX_RESPONSE_BYTES.toLong()) {
                "PAIRING_RESPONSE_SIZE_INVALID"
            }
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_RESPONSE_BYTES) {
                        "PAIRING_RESPONSE_SIZE_INVALID"
                    }
                    output.write(buffer, 0, read)
                }
                buffer.fill(0)
                output.toByteArray()
            }
            return try {
                require(response.isNotEmpty()) { "PAIRING_RESPONSE_EMPTY" }
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response))
                    .toString()
            } finally {
                response.fill(0)
            }
        } finally {
            request.fill(0)
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REQUEST_BYTES = 2_048
        private const val MAX_RESPONSE_BYTES = 16 * 1_024
    }
}

/** Shared pin enforcement for pairing and subsequent signed Bridge requests. */
internal object AndroidGlafiraPinnedHttpsSupport {
    @SuppressLint("CustomX509TrustManager")
    fun socketFactory(expectedPin: String) = SSLContext.getInstance("TLS").run {
        require(expectedPin.matches(Regex("^[a-f0-9]{64}$"))) {
            "BRIDGE_TLS_PIN_INVALID"
        }
        val expected = expectedPin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("PAIRING_CLIENT_CERTIFICATE_NOT_SUPPORTED")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || authType.isBlank()) {
                    throw CertificateException("PAIRING_TLS_CHAIN_INVALID")
                }
                val leaf = chain[0]
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                try {
                    if (!MessageDigest.isEqual(actual, expected)) {
                        throw CertificateException("PAIRING_TLS_PIN_MISMATCH")
                    }
                } finally {
                    actual.fill(0)
                }
            }
        }
        init(null, arrayOf(trustManager), null)
        socketFactory
    }

    fun verifyConnectedLeaf(connection: HttpsURLConnection, expectedPin: String) {
        val leaf = connection.serverCertificates.firstOrNull()
            ?: throw IllegalStateException("BRIDGE_TLS_PEER_CERTIFICATE_MISSING")
        val expected = expectedPin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        try {
            require(MessageDigest.isEqual(actual, expected)) { "BRIDGE_TLS_PIN_MISMATCH" }
        } finally {
            actual.fill(0)
            expected.fill(0)
        }
    }
}

class GlafiraWindowsHubClientCredential internal constructor(
    val deviceId: String,
    val desktopHubId: String,
    val endpoint: String,
    val tlsCertificateSha256: String,
    private val hmacSecret: ByteArray,
    val scopes: Set<String>,
    val enrolledAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val ownershipAnchor: GlafiraOwnershipTrustAnchor
) {
    fun secretCopy(): ByteArray = hmacSecret.copyOf()
    fun clearSecret() = hmacSecret.fill(0)
}

fun interface GlafiraWindowsHubClientCredentialProvider {
    fun load(nowEpochMs: Long): GlafiraWindowsHubClientCredential?
}

internal interface GlafiraPinnedPairingEnrollmentSink {
    fun install(enrollment: GlafiraPinnedPairingEnrollment): GlafiraWindowsHubCredentialInstallResult
}

enum class GlafiraWindowsHubCredentialInstallResult {
    INSTALLED,
    REFRESHED,
    ROTATED,
    UNCHANGED
}

/** Coordinates strict parsing, a pinned exchange and one atomic composite commit. */
internal class AndroidGlafiraWindowsHubPairingClient(
    private val exchange: GlafiraWindowsHubPairingExchange,
    private val store: GlafiraPinnedPairingEnrollmentSink,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun enroll(invitationJson: String): GlafiraWindowsHubCredentialInstallResult {
        val invitation = GlafiraWindowsHubPairingTrustBinding.parseInvitation(
            invitationJson,
            clock()
        )
        val responseJson = exchange.redeem(invitation)
        val enrollment = GlafiraWindowsHubPairingTrustBinding.correlate(
            invitation,
            responseJson,
            clock()
        )
        return try {
            store.install(enrollment)
        } finally {
            enrollment.clearSecret()
        }
    }
}

/**
 * One encrypted, atomic client-side pairing state. The HMAC credential and the
 * ownership trust anchor are never committed independently.
 */
internal class AndroidGlafiraWindowsHubCredentialStore(
    context: Context,
    stateFile: File = File(context.noBackupFilesDir, DEFAULT_FILE_NAME),
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : GlafiraPinnedPairingEnrollmentSink,
    GlafiraWindowsHubClientCredentialProvider,
    GlafiraOwnershipTrustedKeyResolver {
    private val file = AtomicFile(stateFile)
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    override fun install(
        enrollment: GlafiraPinnedPairingEnrollment
    ): GlafiraWindowsHubCredentialInstallResult {
        require("ownership.read" in enrollment.scopes) { "PAIRING_OWNERSHIP_SCOPE_NOT_GRANTED" }
        validateEnrollment(enrollment)
        val state = readState()
        val existingAnchor = state.anchors[enrollment.anchor.ownerCoreId]
        if (existingAnchor != null) {
            require(enrollment.anchor.signingKeyEpoch >= existingAnchor.signingKeyEpoch) {
                "PAIRING_OWNERSHIP_KEY_EPOCH_ROLLBACK"
            }
            if (enrollment.anchor.signingKeyEpoch == existingAnchor.signingKeyEpoch) {
                require(enrollment.anchor == existingAnchor) {
                    "PAIRING_OWNERSHIP_KEY_EPOCH_CONFLICT"
                }
            }
        }
        require(existingAnchor != null || state.anchors.size < MAX_ANCHORS) {
            "PAIRING_OWNERSHIP_TRUST_LIMIT_EXCEEDED"
        }

        val secret = enrollment.secretCopy()
        val replacement = StoredCredential(
            enrollment.deviceId,
            enrollment.desktopHubId,
            enrollment.endpoint,
            enrollment.tlsCertificateSha256,
            secret,
            enrollment.scopes,
            enrollment.enrolledAtEpochMs,
            enrollment.expiresAtEpochMs,
            enrollment.anchor.ownerCoreId
        )
        val anchors = state.anchors.toMutableMap().apply {
            put(enrollment.anchor.ownerCoreId, enrollment.anchor)
        }
        val sameCredential = state.credential?.equivalentTo(replacement) == true
        val keyAlreadyExisted = keyStore.containsAlias(keyAlias)
        try {
            if (!sameCredential || existingAnchor != enrollment.anchor) {
                writeState(StoredState(replacement, anchors))
            }
        } catch (failure: Throwable) {
            if (!keyAlreadyExisted && !file.baseFile.exists() && keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
            throw failure
        } finally {
            state.clearSecret()
            secret.fill(0)
        }
        return when {
            state.credential == null -> GlafiraWindowsHubCredentialInstallResult.INSTALLED
            existingAnchor != null &&
                enrollment.anchor.signingKeyEpoch > existingAnchor.signingKeyEpoch ->
                GlafiraWindowsHubCredentialInstallResult.ROTATED
            sameCredential -> GlafiraWindowsHubCredentialInstallResult.UNCHANGED
            else -> GlafiraWindowsHubCredentialInstallResult.REFRESHED
        }
    }

    @Synchronized
    override fun load(nowEpochMs: Long): GlafiraWindowsHubClientCredential? {
        val state = readState()
        try {
            val credential = state.credential ?: return null
            if (credential.expiresAtEpochMs <= nowEpochMs) return null
            val anchor = state.anchors[credential.ownerCoreId] ?: return null
            return GlafiraWindowsHubClientCredential(
                credential.deviceId,
                credential.desktopHubId,
                credential.endpoint,
                credential.tlsCertificateSha256,
                credential.secret.copyOf(),
                credential.scopes,
                credential.enrolledAtEpochMs,
                credential.expiresAtEpochMs,
                anchor
            )
        } finally {
            state.clearSecret()
        }
    }

    @Synchronized
    override fun resolve(
        oldOwnerCoreId: String,
        signingKeyEpoch: Long,
        fingerprintSha256: String
    ): PublicKey? {
        val state = readState()
        try {
            val anchor = state.anchors[oldOwnerCoreId] ?: return null
            if (anchor.signingKeyEpoch != signingKeyEpoch ||
                anchor.signingPublicKeySha256 != fingerprintSha256) return null
            return anchor.publicKey()
        } finally {
            state.clearSecret()
        }
    }

    @Synchronized
    fun revoke(ownerCoreId: String): Boolean {
        val state = readState()
        try {
            if (ownerCoreId !in state.anchors) return false
            val anchors = state.anchors.toMutableMap().apply { remove(ownerCoreId) }
            val credential = if (state.credential?.ownerCoreId == ownerCoreId) null
                else state.credential
            if (credential == null && anchors.isEmpty()) {
                destroyState()
            } else {
                writeState(StoredState(credential, anchors))
            }
            return true
        } finally {
            state.clearSecret()
        }
    }

    @Synchronized
    fun revokeAll() = destroyState()

    private fun readState(): StoredState {
        val encoded = try {
            file.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return StoredState(null, emptyMap())
        }
        require(encoded.size in 1..MAX_ENCRYPTED_BYTES) { "PAIRING_STATE_SIZE_INVALID" }
        var iv = ByteArray(0)
        var ciphertext = ByteArray(0)
        var plaintext: ByteArray? = null
        try {
            val wrapper = JSONObject(encoded.toString(Charsets.UTF_8))
            require(wrapper.keys().asSequence().toSet() == setOf("version", "iv", "ciphertext") &&
                wrapper.get("version") == 1) { "PAIRING_STATE_WRAPPER_INVALID" }
            iv = canonicalBase64(wrapper, "iv")
            ciphertext = canonicalBase64(wrapper, "ciphertext")
            require(iv.size == GCM_IV_BYTES) { "PAIRING_STATE_IV_INVALID" }
            plaintext = decrypt(existingKey(), iv, ciphertext)
            return decodeState(JSONObject(plaintext.toString(Charsets.UTF_8)))
        } catch (failure: Exception) {
            throw IllegalStateException("PAIRING_STATE_INVALID", failure)
        } finally {
            encoded.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun decodeState(root: JSONObject): StoredState {
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "credential", "anchors") &&
            root.get("schemaVersion") == 1) { "PAIRING_STATE_SCHEMA_INVALID" }
        val anchorsArray = root.get("anchors")
        require(anchorsArray is JSONArray && anchorsArray.length() <= MAX_ANCHORS) {
            "PAIRING_OWNERSHIP_TRUST_LIMIT_EXCEEDED"
        }
        val anchors = buildMap {
            repeat(anchorsArray.length()) {
                val value = anchorsArray.getJSONObject(it)
                require(value.keys().asSequence().toSet() == ANCHOR_FIELDS) {
                    "PAIRING_ANCHOR_SCHEMA_INVALID"
                }
                val anchor = GlafiraOwnershipTrustAnchor(
                    exactString(value, "ownerCoreId"),
                    exactLong(value, "signingKeyEpoch"),
                    exactString(value, "signingPublicKeySpkiBase64"),
                    exactString(value, "signingPublicKeySha256")
                ).also(GlafiraOwnershipTrustValidation::validate)
                require(put(anchor.ownerCoreId, anchor) == null) { "PAIRING_ANCHOR_DUPLICATE" }
            }
        }
        val credential = if (root.isNull("credential")) null else {
            val value = root.getJSONObject("credential")
            require(value.keys().asSequence().toSet() == CREDENTIAL_FIELDS) {
                "PAIRING_CREDENTIAL_SCHEMA_INVALID"
            }
            val secret = canonicalBase64(value, "sharedSecretBase64")
            try {
                val scopes = exactStringArray(value, "scopes")
                val stored = StoredCredential(
                    exactString(value, "deviceId"),
                    exactString(value, "desktopHubId"),
                    exactString(value, "endpoint"),
                    exactString(value, "tlsCertificateSha256"),
                    secret,
                    scopes,
                    exactLong(value, "enrolledAtEpochMs"),
                    exactLong(value, "expiresAtEpochMs"),
                    exactString(value, "ownerCoreId")
                )
                validateStoredCredential(stored)
                require(stored.ownerCoreId in anchors) { "PAIRING_CREDENTIAL_ANCHOR_MISSING" }
                stored
            } catch (failure: Exception) {
                secret.fill(0)
                throw failure
            }
        }
        return StoredState(credential, anchors)
    }

    private fun writeState(state: StoredState) {
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("credential", state.credential?.let { credential ->
                JSONObject().apply {
                    put("deviceId", credential.deviceId)
                    put("desktopHubId", credential.desktopHubId)
                    put("endpoint", credential.endpoint)
                    put("tlsCertificateSha256", credential.tlsCertificateSha256)
                    put("sharedSecretBase64", Base64.getEncoder().encodeToString(credential.secret))
                    put("scopes", JSONArray(credential.scopes.sorted()))
                    put("enrolledAtEpochMs", credential.enrolledAtEpochMs)
                    put("expiresAtEpochMs", credential.expiresAtEpochMs)
                    put("ownerCoreId", credential.ownerCoreId)
                }
            } ?: JSONObject.NULL)
            put("anchors", JSONArray().apply {
                state.anchors.toSortedMap().values.forEach { anchor ->
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

    private fun validateEnrollment(enrollment: GlafiraPinnedPairingEnrollment) {
        GlafiraOwnershipTrustValidation.validate(enrollment.anchor)
        val secret = enrollment.secretCopy()
        try {
            validateStoredCredential(
                StoredCredential(
                    enrollment.deviceId,
                    enrollment.desktopHubId,
                    enrollment.endpoint,
                    enrollment.tlsCertificateSha256,
                    secret,
                    enrollment.scopes,
                    enrollment.enrolledAtEpochMs,
                    enrollment.expiresAtEpochMs,
                    enrollment.anchor.ownerCoreId
                )
            )
        } finally {
            secret.fill(0)
        }
    }

    private fun validateStoredCredential(value: StoredCredential) {
        require(validIdentifier(value.deviceId) && validIdentifier(value.desktopHubId)) {
            "PAIRING_CREDENTIAL_IDENTITY_INVALID"
        }
        require(validEndpoint(value.endpoint) && value.tlsCertificateSha256.matches(LOWER_SHA256)) {
            "PAIRING_CREDENTIAL_ENDPOINT_INVALID"
        }
        require(value.secret.size == 32 && value.scopes.isNotEmpty() &&
            value.scopes.size <= 32 && value.scopes.all(::validScope) &&
            value.enrolledAtEpochMs > 0 && value.expiresAtEpochMs > value.enrolledAtEpochMs) {
            "PAIRING_CREDENTIAL_VALUE_INVALID"
        }
    }

    private fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path == "/bridge/v1" &&
            (uri.port == -1 || uri.port in 1..65_535) && isPrivateLanLiteral(uri.host)
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
        value.all { it.code <= 0x7f && (it.isLetterOrDigit() || it in "-_.") }

    private fun validScope(value: String): Boolean = value.length in 1..96 &&
        value.all { it.code <= 0x7f && (it.isLetterOrDigit() || it in "-_.") }

    private fun exactString(value: JSONObject, name: String): String = value.get(name).let {
        require(it is String) { "PAIRING_STATE_TYPE_INVALID:$name" }
        it
    }

    private fun exactLong(value: JSONObject, name: String): Long = value.get(name).let {
        require(it is Int || it is Long) { "PAIRING_STATE_TYPE_INVALID:$name" }
        (it as Number).toLong()
    }

    private fun exactStringArray(value: JSONObject, name: String): Set<String> {
        val array = value.get(name)
        require(array is JSONArray && array.length() in 1..32) {
            "PAIRING_STATE_TYPE_INVALID:$name"
        }
        val ordered = buildList {
            repeat(array.length()) {
                add(array.get(it).let { item ->
                    require(item is String) { "PAIRING_STATE_TYPE_INVALID:$name" }
                    item
                })
            }
        }
        require(ordered == ordered.sorted() && ordered.distinct().size == ordered.size) {
            "PAIRING_STATE_SCOPES_NON_CANONICAL"
        }
        return ordered.toSet()
    }

    private fun canonicalBase64(value: JSONObject, name: String): ByteArray = runCatching {
        val text = exactString(value, name)
        Base64.getDecoder().decode(text).also {
            require(Base64.getEncoder().encodeToString(it) == text)
        }
    }.getOrElse { throw IllegalStateException("PAIRING_STATE_ENCODING_INVALID:$name", it) }

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
        ?: throw IllegalStateException("PAIRING_STATE_KEY_MISSING")

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

    private fun destroyState() {
        try {
            if (file.baseFile.exists() && keyStore.containsAlias(keyAlias)) {
                writeState(StoredState(null, emptyMap()))
            }
        } finally {
            file.delete()
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    private data class StoredState(
        val credential: StoredCredential?,
        val anchors: Map<String, GlafiraOwnershipTrustAnchor>
    ) {
        fun clearSecret() = credential?.secret?.fill(0)
    }

    private data class StoredCredential(
        val deviceId: String,
        val desktopHubId: String,
        val endpoint: String,
        val tlsCertificateSha256: String,
        val secret: ByteArray,
        val scopes: Set<String>,
        val enrolledAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val ownerCoreId: String
    ) {
        fun equivalentTo(other: StoredCredential): Boolean =
            deviceId == other.deviceId && desktopHubId == other.desktopHubId &&
                endpoint == other.endpoint && tlsCertificateSha256 == other.tlsCertificateSha256 &&
                MessageDigest.isEqual(secret, other.secret) && scopes == other.scopes &&
                enrolledAtEpochMs == other.enrolledAtEpochMs &&
                expiresAtEpochMs == other.expiresAtEpochMs && ownerCoreId == other.ownerCoreId
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val DEFAULT_FILE_NAME = "windows-hub-pairing-v1.enc"
        private const val DEFAULT_KEY_ALIAS = "glafira.windows.hub.pairing.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_ANCHORS = 32
        private const val MAX_ENCRYPTED_BYTES = 256 * 1_024
        private val LOWER_SHA256 = Regex("^[a-f0-9]{64}$")
        private val AAD = "GLAFIRA.Android.WindowsHubPairing.v1".toByteArray(Charsets.UTF_8)
        private val ANCHOR_FIELDS = setOf(
            "ownerCoreId", "signingKeyEpoch", "signingPublicKeySpkiBase64",
            "signingPublicKeySha256"
        )
        private val CREDENTIAL_FIELDS = setOf(
            "deviceId", "desktopHubId", "endpoint", "tlsCertificateSha256",
            "sharedSecretBase64", "scopes", "enrolledAtEpochMs", "expiresAtEpochMs",
            "ownerCoreId"
        )
    }
}
