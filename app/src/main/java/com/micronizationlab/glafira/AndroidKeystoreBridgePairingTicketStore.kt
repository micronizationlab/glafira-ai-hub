package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable one-time pairing-ticket store. Ticket verifier keys are encrypted with a
 * non-exportable Android Keystore AES-GCM key; raw pairing capabilities are never stored.
 *
 * consume() is a SQLite transaction so concurrent valid proofs can consume a ticket once.
 * This store does not approve a device or issue its long-term credential.
 */
class AndroidKeystoreBridgePairingTicketStore(
    context: Context
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), GlafiraBridgePairingTicketStore {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                ticket_id TEXT PRIMARY KEY NOT NULL,
                iv BLOB NOT NULL,
                verifier_ciphertext BLOB NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX pairing_ticket_expiry_idx ON $TABLE_NAME(expires_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            throw IllegalStateException("Unsupported Bridge pairing ticket database migration.")
        }
    }

    @Synchronized
    override fun create(ticket: GlafiraBridgePairingTicket): Boolean {
        if (!isValidTicketId(ticket.ticketId) ||
            ticket.verifierKey.size != VERIFIER_KEY_BYTES ||
            ticket.expiresAtEpochMs <= 0L
        ) return false

        return runCatching {
            val encrypted = encrypt(getOrCreateKey(), ticket.verifierKey)
            val values = ContentValues().apply {
                put("ticket_id", ticket.ticketId)
                put("iv", encrypted.first)
                put("verifier_ciphertext", encrypted.second)
                put("expires_at", ticket.expiresAtEpochMs)
            }
            writableDatabase.insertOrThrow(TABLE_NAME, null, values) >= 0L
        }.getOrDefault(false)
    }

    @Synchronized
    override fun find(ticketId: String): GlafiraBridgePairingTicket? {
        if (!isValidTicketId(ticketId)) return null
        return runCatching {
            readableDatabase.query(
                TABLE_NAME,
                arrayOf("iv", "verifier_ciphertext", "expires_at"),
                "ticket_id = ?",
                arrayOf(ticketId),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val iv = cursor.getBlob(0)
                val ciphertext = cursor.getBlob(1)
                val expiresAt = cursor.getLong(2)
                val verifier = decrypt(getExistingKey(), iv, ciphertext)
                if (verifier.size != VERIFIER_KEY_BYTES) null
                else GlafiraBridgePairingTicket(ticketId, verifier, expiresAt)
            }
        }.getOrNull()
    }

    @Synchronized
    override fun consume(
        ticketId: String,
        expectedVerifierKey: ByteArray,
        nowEpochMs: Long
    ): Boolean {
        if (!isValidTicketId(ticketId) ||
            expectedVerifierKey.size != VERIFIER_KEY_BYTES ||
            nowEpochMs <= 0L
        ) return false

        return runCatching {
            val db = writableDatabase
            var consumed = false
            db.beginTransaction()
            try {
                val stored = db.query(
                    TABLE_NAME,
                    arrayOf("iv", "verifier_ciphertext", "expires_at"),
                    "ticket_id = ?",
                    arrayOf(ticketId),
                    null,
                    null,
                    null,
                    "1"
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else Triple(cursor.getBlob(0), cursor.getBlob(1), cursor.getLong(2))
                }

                if (stored != null && stored.third > nowEpochMs) {
                    val verifier = decrypt(getExistingKey(), stored.first, stored.second)
                    if (MessageDigest.isEqual(verifier, expectedVerifierKey)) {
                        consumed = db.delete(
                            TABLE_NAME,
                            "ticket_id = ?",
                            arrayOf(ticketId)
                        ) == 1
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            consumed
        }.getOrDefault(false)
    }

    @Synchronized
    fun pruneExpired(nowEpochMs: Long = System.currentTimeMillis()): Int =
        runCatching {
            writableDatabase.delete(
                TABLE_NAME,
                "expires_at <= ?",
                arrayOf(nowEpochMs.toString())
            )
        }.getOrDefault(0)

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey =
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Pairing ticket encryption key is missing.")

    private fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun isValidTicketId(value: String): Boolean =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it in "-_" }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val DATABASE_NAME = "glafira_bridge_pairing.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "bridge_pairing_tickets"
        private const val KEY_ALIAS = "glafira_bridge_pairing_ticket_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERIFIER_KEY_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
