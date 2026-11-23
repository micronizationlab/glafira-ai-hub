package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores provider credentials encrypted with a non-exportable Android Keystore key.
 * Existing plaintext SharedPreferences values are migrated on first read.
 */
object EncryptedApiKeyStore {
    private const val PREFS_NAME = "glafira_prefs"
    private const val KEY_ALIAS = "glafira_api_key_aes"
    private const val ENCRYPTED_PREFIX = "enc:v1:"
    private const val IV_LENGTH = 12

    fun save(context: Context, preferenceKey: String, value: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (value.isBlank()) {
                prefs.edit().remove(preferenceKey).commit()
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                val encoded = ENCRYPTED_PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
                prefs.edit().putString(preferenceKey, encoded).commit()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun load(context: Context, preferenceKey: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(preferenceKey, null) ?: return ""
        if (stored.isBlank()) return ""

        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            // One-time migration from the previous plaintext storage format.
            return if (save(context, preferenceKey, stored)) stored else ""
        }

        return try {
            val payload = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) return ""
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
