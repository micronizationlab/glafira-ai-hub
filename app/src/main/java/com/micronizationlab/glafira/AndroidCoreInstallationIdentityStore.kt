package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Properties
import java.util.UUID

/**
 * Stable identity for this Android Core installation.
 *
 * Metadata lives in noBackupFilesDir and the signing key is non-exportable in Android
 * Keystore. A portable backup/restore therefore cannot clone this installation identity.
 * This identity is not an owner account identity and does not itself grant authorization.
 */
class AndroidCoreInstallationIdentityStore internal constructor(
    context: Context,
    private val identityFile: File = File(context.applicationContext.noBackupFilesDir, IDENTITY_FILE),
    private val keyAliasPrefix: String = KEY_ALIAS_PREFIX
) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun loadOrCreate(): CoreInstallationIdentity = synchronized(PROCESS_LOCK) {
        if (!identityFile.exists()) {
            // If metadata was intentionally removed or lost, never silently reuse a leftover key.
            keyStore.aliases().toList().filter { it.startsWith(keyAliasPrefix) }
                .forEach(keyStore::deleteEntry)
            val keyPair = createKeyPair(1)
            val identity = CoreInstallationIdentity(
                coreId = UUID.randomUUID().toString(),
                keyEpoch = 1,
                publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
            )
            persist(identity)
            return identity
        }

        val properties = Properties().apply { identityFile.inputStream().use(::load) }
        val identity = CoreInstallationIdentity(
            coreId = properties.getProperty(KEY_CORE_ID).orEmpty(),
            keyEpoch = properties.getProperty(KEY_EPOCH)?.toLongOrNull() ?: 0L,
            publicKeyBase64 = properties.getProperty(KEY_PUBLIC_KEY).orEmpty()
        )
        require(identity.coreId.matches(Regex("[0-9a-fA-F-]{36}"))) { "CORE_IDENTITY_CORRUPT" }
        require(identity.keyEpoch > 0) { "CORE_KEY_EPOCH_CORRUPT" }
        val expectedPublicKey = Base64.decode(identity.publicKeyBase64, Base64.NO_WRAP)
        val certificate = keyStore.getCertificate(keyAlias(identity.keyEpoch))
            ?: throw IllegalStateException("CORE_IDENTITY_KEY_MISSING")
        val actualPublicKey = certificate.publicKey.encoded
        require(MessageDigest.isEqual(expectedPublicKey, actualPublicKey)) { "CORE_IDENTITY_KEY_MISMATCH" }
        return identity
    }

    /** Rotate the local signing key and increment its monotonic key epoch. */
    fun rotateKey(): CoreInstallationIdentity = synchronized(PROCESS_LOCK) {
        val current = loadOrCreate()
        val nextEpoch = Math.addExact(current.keyEpoch, 1L)
        // A crash before metadata commit may leave this unreferenced alias behind.
        // The current metadata still points to keyEpoch, so the next epoch is safe to replace.
        keyStore.deleteEntry(keyAlias(nextEpoch))
        val pair = createKeyPair(nextEpoch)
        val next = CoreInstallationIdentity(
            coreId = current.coreId,
            keyEpoch = nextEpoch,
            publicKeyBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        )
        try {
            persist(next)
        } catch (failure: Exception) {
            keyStore.deleteEntry(keyAlias(nextEpoch))
            throw failure
        }
        keyStore.deleteEntry(keyAlias(current.keyEpoch))
        return next
    }

    fun sign(payload: ByteArray): ByteArray = synchronized(PROCESS_LOCK) {
        require(payload.isNotEmpty()) { "CORE_SIGNATURE_PAYLOAD_EMPTY" }
        val identity = loadOrCreate()
        val privateKey = keyStore.getKey(keyAlias(identity.keyEpoch), null) as? PrivateKey
            ?: throw IllegalStateException("CORE_IDENTITY_PRIVATE_KEY_MISSING")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    private fun createKeyPair(epoch: Long) =
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    keyAlias(epoch),
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKeyPair()
        }

    private fun persist(identity: CoreInstallationIdentity) {
        identityFile.parentFile?.let { check(it.exists() || it.mkdirs()) { "CORE_IDENTITY_DIRECTORY_UNAVAILABLE" } }
        val properties = Properties().apply {
            setProperty(KEY_CORE_ID, identity.coreId)
            setProperty(KEY_EPOCH, identity.keyEpoch.toString())
            setProperty(KEY_PUBLIC_KEY, identity.publicKeyBase64)
        }
        val temporary = File(identityFile.parentFile, "${identityFile.name}.tmp")
        try {
            temporary.outputStream().use { stream ->
                properties.store(stream, "GLAFIRA local Core identity; do not copy into portable backups")
                stream.flush()
                (stream as? java.io.FileOutputStream)?.fd?.sync()
            }
            Files.move(
                temporary.toPath(),
                identityFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
        }
    }

    private fun keyAlias(epoch: Long) = "$keyAliasPrefix$epoch"

    companion object {
        // Synchronize across store instances: runtime initialization and tests may construct
        // more than one adapter for the same app-private identity file.
        private val PROCESS_LOCK = Any()
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val IDENTITY_FILE = "glafira_core_installation_identity.properties"
        private const val KEY_ALIAS_PREFIX = "glafira.core.installation.signing."
        private const val KEY_CORE_ID = "core_id"
        private const val KEY_EPOCH = "key_epoch"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val EC_CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

data class CoreInstallationIdentity(
    val coreId: String,
    val keyEpoch: Long,
    val publicKeyBase64: String
)
