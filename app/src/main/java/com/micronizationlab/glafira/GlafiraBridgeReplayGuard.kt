package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

enum class BridgeReplayClaim {
    ACCEPTED,
    DUPLICATE,
    INVALID_EXPIRY,
    CAPACITY_EXCEEDED,
    STORAGE_ERROR
}

/**
 * Durable, transactional replay/idempotency claim store for Bridge requests.
 *
 * Call only after authenticating the device and verifying the request signature, but
 * before executing any operation. A successful claim is permanent until its expiry.
 * A crash after claim and before execution intentionally fails closed (at-most-once).
 */
class GlafiraBridgeReplayGuard(
    context: Context,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) { "Replay guard capacity is out of range." }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                request_hash TEXT PRIMARY KEY NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX replay_expiry_idx ON $TABLE_NAME(expires_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migration is currently required. Future schema changes must
        // preserve unexpired claims; never silently drop the replay ledger.
        if (oldVersion != newVersion) {
            throw IllegalStateException("Unsupported Bridge replay database migration.")
        }
    }

    @Synchronized
    fun claim(
        authenticatedDeviceId: String,
        idempotencyKey: String,
        expiresAtEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): BridgeReplayClaim {
        if (!isIdentifier(authenticatedDeviceId) || !isIdentifier(idempotencyKey) ||
            expiresAtEpochMs <= nowEpochMs
        ) return BridgeReplayClaim.INVALID_EXPIRY

        return runCatching {
            val db = writableDatabase
            var result = BridgeReplayClaim.STORAGE_ERROR
            db.beginTransaction()
            try {
                db.delete(TABLE_NAME, "expires_at <= ?", arrayOf(nowEpochMs.toString()))
                val hash = requestHash(authenticatedDeviceId, idempotencyKey)
                val existing = db.query(
                    TABLE_NAME,
                    arrayOf("request_hash"),
                    "request_hash = ?",
                    arrayOf(hash),
                    null,
                    null,
                    null,
                    "1"
                ).use { it.moveToFirst() }

                if (existing) {
                    result = BridgeReplayClaim.DUPLICATE
                } else {
                    val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use {
                        if (it.moveToFirst()) it.getInt(0) else maxEntries
                    }
                    if (count >= maxEntries) {
                        result = BridgeReplayClaim.CAPACITY_EXCEEDED
                    } else {
                        val values = ContentValues().apply {
                            put("request_hash", hash)
                            put("expires_at", expiresAtEpochMs)
                        }
                        val inserted = db.insertOrThrow(TABLE_NAME, null, values)
                        result = if (inserted >= 0) BridgeReplayClaim.ACCEPTED
                        else BridgeReplayClaim.STORAGE_ERROR
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            result
        }.getOrElse { BridgeReplayClaim.STORAGE_ERROR }
    }

    @Synchronized
    fun pruneExpired(nowEpochMs: Long = System.currentTimeMillis()): Int =
        runCatching {
            writableDatabase.delete(TABLE_NAME, "expires_at <= ?", arrayOf(nowEpochMs.toString()))
        }.getOrDefault(0)

    private fun requestHash(deviceId: String, idempotencyKey: String): String {
        val canonical = "$deviceId\u0000$idempotencyKey"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun isIdentifier(value: String): Boolean =
        value.length in 8..128 && value.all { it.isLetterOrDigit() || it in "-_." }

    companion object {
        private const val DATABASE_NAME = "glafira_bridge_replay.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "bridge_replay_claims"
        private const val DEFAULT_MAX_ENTRIES = 10_000
        private const val MAX_ALLOWED_ENTRIES = 100_000
    }
}
