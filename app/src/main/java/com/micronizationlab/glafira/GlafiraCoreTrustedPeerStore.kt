package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.io.File
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Local trust registry for remote Core installation keys.
 *
 * The database lives under noBackupFilesDir: a portable backup must never transfer peer
 * trust or authorize a replacement installation to impersonate an old installation.
 * A candidate key is data, not trust; enrollment requires a fresh one-use BiometricPrompt
 * proof and the exact SHA-256 fingerprint the owner independently confirmed out of band.
 */
class GlafiraCoreTrustedPeerStore(
    context: Context,
    databaseFile: File = File(context.noBackupFilesDir, DATABASE_NAME)
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseFile.absolutePath,
    null,
    DATABASE_VERSION
) {
    private val localCoreId = AndroidCoreInstallationIdentityStore(context).loadOrCreate().coreId

    init {
        val noBackupRoot = context.noBackupFilesDir.canonicalFile
        require(databaseFile.canonicalFile.parentFile == noBackupRoot) {
            "TRUSTED_PEER_STORE_MUST_BE_NO_BACKUP"
        }
        require(isIdentifier(localCoreId)) { "LOCAL_CORE_ID_INVALID" }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                core_id TEXT NOT NULL,
                key_epoch INTEGER NOT NULL CHECK(key_epoch > 0),
                public_key_spki_base64 TEXT NOT NULL,
                fingerprint_sha256 TEXT NOT NULL,
                approved_at INTEGER NOT NULL,
                revoked_at INTEGER,
                PRIMARY KEY(core_id, key_epoch)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX trusted_peer_one_active_key " +
                "ON $TABLE_NAME(core_id) WHERE revoked_at IS NULL"
        )
        db.execSQL("CREATE INDEX trusted_peer_revocation ON $TABLE_NAME(core_id, revoked_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            throw IllegalStateException("A reviewed trusted-peer registry migration is required.")
        }
    }

    @Synchronized
    fun enroll(
        candidate: GlafiraPeerEnrollmentCandidate,
        ownerConfirmedFingerprintSha256: String,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraPeerTrustMutation {
        if (nowMillis <= 0L) return GlafiraPeerTrustMutation.INVALID_PEER_IDENTITY
        val publicKey = parseCandidate(candidate) ?: return GlafiraPeerTrustMutation.INVALID_PEER_IDENTITY
        val computedFingerprint = GlafiraCoreOwnershipTransferCryptography.fingerprint(publicKey)
        if (candidate.claimedFingerprintSha256 != computedFingerprint ||
            !isFingerprint(ownerConfirmedFingerprintSha256) ||
            ownerConfirmedFingerprintSha256 != computedFingerprint
        ) return GlafiraPeerTrustMutation.FINGERPRINT_MISMATCH

        val db = writableDatabase
        return try {
            db.beginTransaction()
            val active = queryActive(db, candidate.coreId)
            if (active != null) {
                val result = if (active.keyEpoch == candidate.keyEpoch &&
                    active.fingerprintSha256 == computedFingerprint
                ) GlafiraPeerTrustMutation.ALREADY_TRUSTED else GlafiraPeerTrustMutation.ACTIVE_KEY_EXISTS
                db.setTransactionSuccessful()
                return result
            }
            val highestEpoch = db.rawQuery(
                "SELECT MAX(key_epoch) FROM $TABLE_NAME WHERE core_id = ?",
                arrayOf(candidate.coreId)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            }
            if (candidate.keyEpoch <= highestEpoch) {
                db.setTransactionSuccessful()
                return GlafiraPeerTrustMutation.KEY_EPOCH_NOT_MONOTONIC
            }
            if (!ownerSession.consume()) {
                db.setTransactionSuccessful()
                return GlafiraPeerTrustMutation.OWNER_AUTHENTICATION_REQUIRED
            }
            val values = ContentValues().apply {
                put("core_id", candidate.coreId)
                put("key_epoch", candidate.keyEpoch)
                put("public_key_spki_base64", candidate.publicKeySpkiBase64)
                put("fingerprint_sha256", computedFingerprint)
                put("approved_at", nowMillis)
                putNull("revoked_at")
            }
            db.insertOrThrow(TABLE_NAME, null, values)
            db.setTransactionSuccessful()
            GlafiraPeerTrustMutation.ENROLLED
        } catch (_: Exception) {
            GlafiraPeerTrustMutation.STORAGE_ERROR
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    }

    @Synchronized
    fun revoke(
        coreId: String,
        keyEpoch: Long,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraPeerTrustMutation {
        if (!isIdentifier(coreId) || coreId == localCoreId || keyEpoch <= 0L || nowMillis <= 0L) {
            return GlafiraPeerTrustMutation.INVALID_PEER_IDENTITY
        }
        if (!ownerSession.consume()) return GlafiraPeerTrustMutation.OWNER_AUTHENTICATION_REQUIRED
        val db = writableDatabase
        return try {
            db.beginTransaction()
            val existing = query(db, coreId, keyEpoch)
            val result = when {
                existing == null -> GlafiraPeerTrustMutation.NOT_FOUND
                existing.revokedAtMillis != null -> GlafiraPeerTrustMutation.ALREADY_REVOKED
                else -> {
                    val changed = db.update(
                        TABLE_NAME,
                        ContentValues().apply { put("revoked_at", nowMillis) },
                        "core_id = ? AND key_epoch = ? AND revoked_at IS NULL",
                        arrayOf(coreId, keyEpoch.toString())
                    )
                    if (changed == 1) GlafiraPeerTrustMutation.REVOKED
                    else GlafiraPeerTrustMutation.STORAGE_ERROR
                }
            }
            db.setTransactionSuccessful()
            result
        } catch (_: Exception) {
            GlafiraPeerTrustMutation.STORAGE_ERROR
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    }

    /** Exact active key lookup for a later transfer verifier; revoked or stale epochs never resolve. */
    @Synchronized
    fun resolveActive(coreId: String, keyEpoch: Long): GlafiraTrustedPeer? {
        if (!isIdentifier(coreId) || coreId == localCoreId || keyEpoch <= 0L) return null
        return runCatching { query(readableDatabase, coreId, keyEpoch)?.takeIf { it.revokedAtMillis == null } }
            .getOrNull()
    }

    @Synchronized
    fun listActive(): List<GlafiraTrustedPeer> = runCatching {
        val rows = mutableListOf<GlafiraTrustedPeer>()
        readableDatabase.query(
            TABLE_NAME,
            arrayOf("core_id", "key_epoch", "public_key_spki_base64", "fingerprint_sha256", "approved_at", "revoked_at"),
            "revoked_at IS NULL",
            null,
            null,
            null,
            "core_id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toPeer()
        }
        rows
    }.getOrDefault(emptyList())

    private fun parseCandidate(candidate: GlafiraPeerEnrollmentCandidate): ECPublicKey? {
        if (!isIdentifier(candidate.coreId) || candidate.coreId == localCoreId ||
            candidate.keyEpoch <= 0L || candidate.publicKeySpkiBase64.length !in 1..1024 ||
            !isFingerprint(candidate.claimedFingerprintSha256)
        ) return null
        return runCatching {
            val encoded = Base64.decode(candidate.publicKeySpkiBase64, Base64.NO_WRAP)
            require(encoded.size in 1..512)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as ECPublicKey
            val parameters = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            require(key.params.curve == parameters.curve &&
                key.params.generator == parameters.generator &&
                key.params.order == parameters.order &&
                key.params.cofactor == parameters.cofactor)
            require(key.encoded.contentEquals(encoded))
            key
        }.getOrNull()
    }

    private fun queryActive(db: SQLiteDatabase, coreId: String): GlafiraTrustedPeer? =
        db.query(
            TABLE_NAME,
            arrayOf("core_id", "key_epoch", "public_key_spki_base64", "fingerprint_sha256", "approved_at", "revoked_at"),
            "core_id = ? AND revoked_at IS NULL",
            arrayOf(coreId),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toPeer() else null }

    private fun query(db: SQLiteDatabase, coreId: String, keyEpoch: Long): GlafiraTrustedPeer? =
        db.query(
            TABLE_NAME,
            arrayOf("core_id", "key_epoch", "public_key_spki_base64", "fingerprint_sha256", "approved_at", "revoked_at"),
            "core_id = ? AND key_epoch = ?",
            arrayOf(coreId, keyEpoch.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toPeer() else null }

    private fun android.database.Cursor.toPeer() = GlafiraTrustedPeer(
        coreId = getString(0),
        keyEpoch = getLong(1),
        publicKeySpkiBase64 = getString(2),
        fingerprintSha256 = getString(3),
        approvedAtMillis = getLong(4),
        revokedAtMillis = if (isNull(5)) null else getLong(5)
    )

    private fun isIdentifier(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9._-]{8,128}$"))

    private fun isFingerprint(value: String): Boolean =
        value.matches(Regex("^[a-f0-9]{64}$"))

    companion object {
        private const val DATABASE_NAME = "glafira_trusted_peers.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "trusted_core_peers"
    }
}
