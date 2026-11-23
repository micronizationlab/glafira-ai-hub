package com.micronizationlab.glafira

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local-only password vault. The master password is never stored.
 * The file contains only a versioned AES-256-GCM envelope.
 */
data class VaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val website: String,
    val notes: String,
    val updatedAt: Long
)

class VaultSession internal constructor(
    context: Context,
    private val keyBytes: ByteArray,
    private val salt: ByteArray,
    private val deadline: VaultSessionDeadline = VaultSessionDeadline()
) {
    private val file = File(context.filesDir, FILE_NAME)
    private var active = true

    @Synchronized
    fun loadEntries(): List<VaultEntry> {
        requireActive()
        val envelope = JSONObject(file.readText())
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        val array = JSONArray(plain)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    VaultEntry(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        username = item.optString("username"),
                        password = item.optString("password"),
                        website = item.optString("website"),
                        notes = item.optString("notes"),
                        updatedAt = item.optLong("updatedAt")
                    )
                )
            }
        }
    }

    @Synchronized
    fun saveEntries(entries: List<VaultEntry>) {
        requireActive()
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("title", entry.title)
                put("username", entry.username)
                put("password", entry.password)
                put("website", entry.website)
                put("notes", entry.notes)
                put("updatedAt", entry.updatedAt)
            })
        }
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().apply {
            put("version", 1)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(envelope.toString())
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun lock() {
        deactivate()
    }

    @Synchronized
    internal fun remainingLifetimeMillis(): Long {
        if (!active) return 0L
        val remaining = deadline.remainingMillis()
        if (remaining == 0L) deactivate()
        return remaining
    }

    private fun requireActive() {
        if (!active || deadline.isExpired()) {
            deactivate()
            error("Хранилище заблокировано")
        }
    }

    private fun deactivate() {
        if (!active) return
        keyBytes.fill(0)
        active = false
    }

    companion object {
        private const val FILE_NAME = "password_vault.enc"
        private const val IV_LENGTH = 12
        private const val PBKDF2_ITERATIONS = 310_000
        private const val AAD = "GLAFIRA_PASSWORD_VAULT_V1"
        private val random = SecureRandom()

        fun exists(context: Context): Boolean =
            File(context.filesDir, FILE_NAME).exists()

        fun create(context: Context, masterPassword: CharArray): VaultSession {
            try {
                require(masterPassword.size >= 12) { "Пароль должен содержать не менее 12 символов" }
                require(!exists(context)) { "Хранилище уже создано" }
                val salt = ByteArray(16).also(random::nextBytes)
                val key = deriveKey(masterPassword, salt)
                val session = VaultSession(context, key, salt)
                try {
                    session.saveEntries(emptyList())
                    return session
                } catch (error: Exception) {
                    session.lock()
                    File(context.filesDir, FILE_NAME).delete()
                    throw error
                }
            } finally {
                masterPassword.fill('\u0000')
            }
        }

        fun unlock(context: Context, masterPassword: CharArray): VaultSession {
            try {
                val file = File(context.filesDir, FILE_NAME)
                require(file.exists()) { "Хранилище ещё не создано" }
                val envelope = JSONObject(file.readText())
                require(envelope.optInt("version") == 1) { "Неизвестная версия хранилища" }
                val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
                val key = deriveKey(masterPassword, salt)
                val session = VaultSession(context, key, salt)
                try {
                    session.loadEntries() // Authenticate password before exposing session.
                    return session
                } catch (error: Exception) {
                    session.lock()
                    throw IllegalArgumentException("Неверный мастер-пароль или повреждённое хранилище")
                }
            } finally {
                masterPassword.fill('\u0000')
            }
        }

        private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}

internal class VaultSessionDeadline(
    private val lifetimeNanos: Long = DEFAULT_LIFETIME_NANOS,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val openedAtNanos = nanoTime()

    init {
        require(lifetimeNanos > 0L) { "Session lifetime must be positive" }
    }

    fun isExpired(): Boolean = elapsedNanos() >= lifetimeNanos

    fun remainingMillis(): Long {
        val remainingNanos = (lifetimeNanos - elapsedNanos()).coerceAtLeast(0L)
        if (remainingNanos == 0L) return 0L
        return ((remainingNanos - 1L) / NANOS_PER_MILLISECOND) + 1L
    }

    private fun elapsedNanos(): Long = (nanoTime() - openedAtNanos).coerceAtLeast(0L)

    companion object {
        internal const val DEFAULT_LIFETIME_MILLIS = 5 * 60_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val DEFAULT_LIFETIME_NANOS = DEFAULT_LIFETIME_MILLIS * NANOS_PER_MILLISECOND
    }
}
