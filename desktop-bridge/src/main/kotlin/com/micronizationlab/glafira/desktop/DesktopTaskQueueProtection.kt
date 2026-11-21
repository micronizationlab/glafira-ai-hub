package com.micronizationlab.glafira.desktop

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal interface DesktopTaskQueueProtector : AutoCloseable {
    fun protect(plaintext: ByteArray): ByteArray
    fun unprotect(ciphertext: ByteArray): ByteArray
}

/**
 * Pairing-bound AES-256-GCM protection for the Desktop task queue. The queue key is
 * derived from the already enrolled 256-bit HMAC secret with an HKDF-style extract
 * and expand step; the transport key itself is never persisted beside the queue.
 */
internal class PairingDesktopTaskQueueProtector private constructor(
    private val key: ByteArray,
    private val aad: ByteArray
) : DesktopTaskQueueProtector {
    private var closed = false

    override fun protect(plaintext: ByteArray): ByteArray {
        check(!closed) { "Desktop task queue protector is closed." }
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        var ciphertext: ByteArray? = null
        return try {
            ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(plaintext)
            }
            nonce + requireNotNull(ciphertext)
        } finally {
            nonce.fill(0)
            ciphertext?.fill(0)
        }
    }

    override fun unprotect(ciphertext: ByteArray): ByteArray {
        check(!closed) { "Desktop task queue protector is closed." }
        require(ciphertext.size >= NONCE_BYTES + TAG_BITS / 8) {
            "Desktop task queue ciphertext is truncated."
        }
        val nonce = ciphertext.copyOfRange(0, NONCE_BYTES)
        val payload = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size)
        return try {
            try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                    updateAAD(aad)
                    doFinal(payload)
                }
            } catch (_: AEADBadTagException) {
                throw SecurityException("Desktop task queue key is incorrect or the file was modified.")
            }
        } finally {
            nonce.fill(0)
            payload.fill(0)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
        aad.fill(0)
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        fun fromPairing(binding: String, hmacSecret: ByteArray): PairingDesktopTaskQueueProtector {
            require(binding.matches(Regex("[0-9a-f]{64}")))
            require(hmacSecret.size == 32) { "Pairing HMAC secret must be exactly 256 bits." }
            val salt = MessageDigest.getInstance("SHA-256").digest(
                "GLAFIRA-DESKTOP-QUEUE-HKDF-SALT-V1\n$binding".toByteArray(Charsets.UTF_8)
            )
            val extract = hmac(salt, hmacSecret)
            val info = "GLAFIRA-DESKTOP-QUEUE-AES256-GCM-V1\n$binding".toByteArray(Charsets.UTF_8) + byteArrayOf(1)
            return try {
                val key = hmac(extract, info)
                val aad = "GLAFIRA-DESKTOP-QUEUE-V1\n$binding".toByteArray(Charsets.UTF_8)
                PairingDesktopTaskQueueProtector(key, aad)
            } finally {
                salt.fill(0)
                extract.fill(0)
                info.fill(0)
            }
        }

        private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(input)
            }
    }
}
