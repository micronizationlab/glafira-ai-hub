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

    fun encrypt(source: File, destination: File) {
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
                    while (true) {
                        val count = raw.read(buffer)
                        if (count < 0) break
                        val clear = cipher.update(buffer, 0, count)
                        if (clear != null && clear.isNotEmpty()) output.write(clear)
                    }
                    // GCM authenticates the complete ciphertext here. Never accept a partial file.
                    output.write(cipher.doFinal())
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
