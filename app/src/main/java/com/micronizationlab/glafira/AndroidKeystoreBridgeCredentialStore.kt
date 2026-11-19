package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local encrypted storage for credentials belonging to remote Bridge devices.
 *
 * The AES key is non-exportable and protected by Android Keystore. The Bridge HMAC
 * secret is encrypted before it is written to app-private SharedPreferences.
 *
 * This class is storage only: pairing/authentication must happen before save(), and
 * callers must pass the authenticated device record to GlafiraBridgeCoreGate.
 */
class AndroidKeystoreBridgeCredentialStore(context: Context) : GlafiraBridgeCredentialProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    fun save(deviceId: String, hmacSecret: ByteArray, scopes: Set<String>) {
        require(isValidDeviceId(deviceId)) { "Invalid device ID." }
        require(hmacSecret.size >= MIN_SECRET_BYTES) { "Bridge secret must contain at least 256 bits." }
        require(scopes.isNotEmpty() && scopes.all(::isValidScope)) { "Invalid device scopes." }

        val alias = keyAlias(deviceId)
        val keyAlreadyExisted = keyStore.containsAlias(alias)
        val key = getOrCreateKey(alias)
        val encrypted = encrypt(key, hmacSecret)
        try {
            val encoded = listOf(
                Base64.encodeToString(encrypted.first, Base64.NO_WRAP),
                Base64.encodeToString(encrypted.second, Base64.NO_WRAP),
                scopes.sorted().joinToString(",")
            ).joinToString(":")

            val committed = preferences.edit().putString(storageKey(deviceId), encoded).commit()
            if (!committed) {
                if (!keyAlreadyExisted && keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                throw IllegalStateException("Unable to persist Bridge device credentials.")
            }
        } finally {
            encrypted.first.fill(0)
            encrypted.second.fill(0)
        }
    }

    @Synchronized
    override fun load(deviceId: String): GlafiraBridgeDeviceCredential? {
        if (!isValidDeviceId(deviceId)) return null
        val encoded = preferences.getString(storageKey(deviceId), null) ?: return null
        val parts = encoded.split(":", limit = 3)
        if (parts.size != 3) return null

        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val scopes = parts[2].split(",").filter(String::isNotBlank).toSet()
            require(iv.size == GCM_IV_BYTES)
            require(scopes.isNotEmpty() && scopes.all(::isValidScope))
            var secret: ByteArray? = null
            try {
                val decrypted = decrypt(getExistingKey(keyAlias(deviceId)), iv, ciphertext)
                secret = decrypted
                require(decrypted.size >= MIN_SECRET_BYTES)
                GlafiraBridgeDeviceCredential(deviceId, decrypted, scopes)
            } catch (failure: Exception) {
                secret?.fill(0)
                throw failure
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        }.getOrNull()
    }

    /** Revocation removes the stored record and destroys its wrapping key. */
    @Synchronized
    fun revoke(deviceId: String): Boolean {
        if (!isValidDeviceId(deviceId)) return false
        val alias = keyAlias(deviceId)
        val committed = preferences.edit().remove(storageKey(deviceId)).commit()
        if (!committed) return false
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        return true
    }

    @Synchronized
    fun revokeAll(): Boolean {
        val aliases = preferences.all.keys
            .filter { it.startsWith(DEVICE_KEY_PREFIX) }
            .map { it.removePrefix(DEVICE_KEY_PREFIX).take(32) }
            .filter { it.length == 32 }
            .map { KEY_ALIAS_PREFIX + it }
        val committed = preferences.edit().clear().commit()
        if (!committed) return false
        aliases.forEach { alias ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
        return true
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(alias: String): SecretKey =
        keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Bridge credential key is missing; re-pair the device.")

    private fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv to ciphertext
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun keyAlias(deviceId: String): String =
        KEY_ALIAS_PREFIX + sha256(deviceId).take(32)

    private fun storageKey(deviceId: String): String =
        DEVICE_KEY_PREFIX + sha256(deviceId)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun isValidDeviceId(value: String): Boolean =
        value.length in 8..128 && value.all { it.isLetterOrDigit() || it in "-_." }

    private fun isValidScope(value: String): Boolean =
        value.length in 1..96 && value.all { it.isLetterOrDigit() || it in "._-" }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val PREFS_NAME = "glafira_bridge_device_credentials"
        private const val KEY_ALIAS_PREFIX = "glafira.bridge.device."
        private const val DEVICE_KEY_PREFIX = "device."
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MIN_SECRET_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
