package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts every durable memory payload with a non-exportable device-bound key. */
class GlafiraMemoryCrypto(context: Context) {
    @Suppress("unused")
    private val appContext = context.applicationContext

    fun encrypt(memoryId: MemoryIdentifier, schemaVersion: Int, classification: CoreDataClassification, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad(memoryId, schemaVersion, classification))
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(memoryId: MemoryIdentifier, schemaVersion: Int, classification: CoreDataClassification, payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH + TAG_LENGTH_BYTES) { "Encrypted memory payload is truncated" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad(memoryId, schemaVersion, classification))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
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

    private fun aad(memoryId: MemoryIdentifier, schemaVersion: Int, classification: CoreDataClassification): ByteArray =
        "GLAFIRA-MEMORY|v1|$schemaVersion|$classification|${memoryId.value}".toByteArray(Charsets.UTF_8)

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "glafira_core_memory_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
    }
}
