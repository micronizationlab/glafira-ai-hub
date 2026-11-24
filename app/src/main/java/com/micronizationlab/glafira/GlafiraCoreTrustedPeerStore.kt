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
    init {
        val noBackupRoot = context.noBackupFilesDir.canonicalFile
        require(databaseFile.canonicalFile.parentFile == noBackupRoot) {
            "TRUSTED_PEER_STORE_MUST_BE_NO_BACKUP"
        }
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

    companion object {
        private const val DATABASE_NAME = "glafira_trusted_peers.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "trusted_core_peers"
    }
}
