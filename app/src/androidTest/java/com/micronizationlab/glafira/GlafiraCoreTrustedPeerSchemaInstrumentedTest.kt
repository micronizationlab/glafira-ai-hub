package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreTrustedPeerSchemaInstrumentedTest {
    @Test fun registryIsNoBackupAndAllowsOnlyOneActiveKeyPerPeer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "trusted-peer-schema-test.db")
        val store = GlafiraCoreTrustedPeerStore(context, file)
        try {
            val db = store.writableDatabase
            assertEquals(context.noBackupFilesDir.canonicalFile, file.canonicalFile.parentFile)
            val columns = db.rawQuery("PRAGMA table_info(trusted_core_peers)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(columns.containsAll(setOf("core_id", "key_epoch", "public_key_spki_base64", "fingerprint_sha256", "approved_at", "revoked_at")))
            val first = ContentValues().apply {
                put("core_id", "peer-core-0001")
                put("key_epoch", 1L)
                put("public_key_spki_base64", "cHVibGljLWtleQ==")
                put("fingerprint_sha256", "a".repeat(64))
                put("approved_at", 1_000L)
                putNull("revoked_at")
            }
            db.insertOrThrow("trusted_core_peers", null, first)
            val second = ContentValues(first).apply { put("key_epoch", 2L) }
            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow("trusted_core_peers", null, second)
            }
            assertEquals(1, db.update("trusted_core_peers", ContentValues().apply { put("revoked_at", 2_000L) },
                "core_id = ? AND key_epoch = 1", arrayOf("peer-core-0001")))
            db.insertOrThrow("trusted_core_peers", null, second)
        } finally {
            store.close()
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
        }
    }
}
