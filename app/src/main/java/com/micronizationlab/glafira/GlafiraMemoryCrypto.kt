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

    fun encrypt(
        memoryId: MemoryIdentifier,
        schemaVersion: Int,
        scopeType: MemoryScopeType,
        scopeId: String,
        kind: MemoryKind,
        classification: CoreDataClassification,
        status: MemoryStatus,
        confidence: Double,
        confidenceBasis: MemoryConfidenceBasis,
        createdAt: java.time.Instant,
        updatedAt: java.time.Instant,
        validFrom: java.time.Instant?,
        expiresAt: java.time.Instant?,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad(memoryId, schemaVersion, scopeType, scopeId, kind, classification,
            status, confidence, confidenceBasis, createdAt, updatedAt, validFrom, expiresAt))
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(
        memoryId: MemoryIdentifier,
        schemaVersion: Int,
        scopeType: MemoryScopeType,
        scopeId: String,
        kind: MemoryKind,
        classification: CoreDataClassification,
        status: MemoryStatus,
        confidence: Double,
        confidenceBasis: MemoryConfidenceBasis,
        createdAt: java.time.Instant,
        updatedAt: java.time.Instant,
        validFrom: java.time.Instant?,
        expiresAt: java.time.Instant?,
        payload: ByteArray
    ): ByteArray {
        require(payload.size > IV_LENGTH + TAG_LENGTH_BYTES) { "Encrypted memory payload is truncated" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Do not mint a replacement key during decryption: that would hide key loss.
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad(memoryId, schemaVersion, scopeType, scopeId, kind, classification,
            status, confidence, confidenceBasis, createdAt, updatedAt, validFrom, expiresAt))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
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
        generator.generateKey()
    }

    private fun getExistingKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("GLAFIRA memory encryption key is unavailable; owner recovery is required")
    }

    private fun aad(
        memoryId: MemoryIdentifier,
        schemaVersion: Int,
        scopeType: MemoryScopeType,
        scopeId: String,
        kind: MemoryKind,
        classification: CoreDataClassification,
        status: MemoryStatus,
        confidence: Double,
        confidenceBasis: MemoryConfidenceBasis,
        createdAt: java.time.Instant,
        updatedAt: java.time.Instant,
        validFrom: java.time.Instant?,
        expiresAt: java.time.Instant?
    ): ByteArray = listOf(
        "GLAFIRA-MEMORY", "v1", schemaVersion.toString(), memoryId.value, scopeType.name,
        scopeId, kind.name, classification.name, status.name, confidence.toString(),
        confidenceBasis.name, createdAt.toString(), updatedAt.toString(),
        validFrom?.toString().orEmpty(), expiresAt?.toString().orEmpty()
    ).joinToString("|").toByteArray(Charsets.UTF_8)

    companion object {
        private val KEY_LOCK = Any()
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "glafira_core_memory_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
    }
}
