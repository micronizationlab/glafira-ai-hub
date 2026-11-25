package com.micronizationlab.glafira

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreOutboxSchemaInstrumentedTest {
    @Test fun freshCoreDatabaseCreatesVersionedOutboxAndExactAckColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("glafira_core_tasks.db")
        val store = GlafiraTaskStore(context, "glafira-outbox-test-core-0001")
        try {
            val columns = store.readableDatabase.rawQuery(
                "PRAGMA table_info(${GlafiraCoreOutboxSchema.TABLE})", null
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(columns.containsAll(setOf(
                "event_id", "source_core_id", "recipient_core_id", "source_key_epoch",
                "protocol_version", "event_type", "task_id", "owner_generation",
                "state_version", "idempotency_key", "payload_sha256", "payload_ciphertext",
                "signature_base64", "delivery_state", "attempt_count", "next_attempt_at",
                "acknowledged_at", "ack_sender_core_id", "ack_recipient_core_id",
                "ack_owner_generation", "ack_state_version", "ack_payload_sha256",
                "ack_signature_base64"
            )))
            val version = store.readableDatabase.rawQuery("PRAGMA user_version", null).use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
            assertEquals(12, version)
            val indexes = store.readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
                arrayOf(GlafiraCoreOutboxSchema.TABLE)
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue(indexes.contains("index_core_outbox_pending_delivery"))
            assertTrue(indexes.contains("index_core_outbox_task_generation"))
        } finally {
            store.close()
            context.deleteDatabase("glafira_core_tasks.db")
        }
    }
}
