package com.micronizationlab.glafira

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated, installation-local encryption for the short-lived Core rollback snapshot. */
internal object GlafiraRollbackSnapshotCipher {
    private const val KEY_ALIAS = "glafira_core_rollback_v1"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private val MAGIC = "GLRBK001".toByteArray(Charsets.US_ASCII)
    private const val IV_BYTES = 12
    private const val MAX_SNAPSHOT_BYTES = 512L * 1024L * 1024L
    private const val GCM_TAG_BYTES = 16
    private const val MAX_ENCRYPTED_BYTES = MAX_SNAPSHOT_BYTES + MAGIC.size + IV_BYTES + GCM_TAG_BYTES

    fun encrypt(source: File, destination: File) {
        require(source.isFile && source.length() in 1..MAX_SNAPSHOT_BYTES) {
            "Core rollback snapshot is missing or exceeds the size limit."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        FileOutputStream(destination).use { raw ->
            raw.write(MAGIC)
            raw.write(cipher.iv)
            CipherOutputStream(raw, cipher).use { encrypted ->
                FileInputStream(source).use { it.copyTo(encrypted) }
            }
        }
    }

    fun decrypt(source: File, destination: File) {
        try {
            require(source.isFile && source.length() in
                (MAGIC.size + IV_BYTES + GCM_TAG_BYTES + 1L)..MAX_ENCRYPTED_BYTES) {
                "Core rollback snapshot is missing or exceeds the size limit."
            }
            DataInputStream(FileInputStream(source)).use { raw ->
                val magic = ByteArray(MAGIC.size)
                raw.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Core rollback snapshot format is invalid." }
                val iv = ByteArray(IV_BYTES)
                raw.readFully(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, existingKeyOrThrow(), GCMParameterSpec(128, iv))
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var totalClearBytes = 0L
                    while (true) {
                        val count = raw.read(buffer)
                        if (count < 0) break
                        val clear = cipher.update(buffer, 0, count)
                        if (clear != null && clear.isNotEmpty()) {
                            totalClearBytes += clear.size
                            require(totalClearBytes <= MAX_SNAPSHOT_BYTES) {
                                "Core rollback snapshot exceeds the size limit."
                            }
                            output.write(clear)
                        }
                    }
                    // GCM authenticates the complete ciphertext here. Never accept a partial file.
                    val finalClear = cipher.doFinal()
                    totalClearBytes += finalClear.size
                    require(totalClearBytes in 1..MAX_SNAPSHOT_BYTES) {
                        "Core rollback snapshot is empty or exceeds the size limit."
                    }
                    output.write(finalClear)
                    output.fd.sync()
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun deleteKey() {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private fun existingKeyOrThrow(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("ROLLBACK_KEY_MISSING")
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
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
