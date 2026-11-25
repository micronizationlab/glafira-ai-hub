package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                "event_id", "source_core_id", "recipient_core_id", "project_id", "source_sequence",
                "correlation_id", "causation_id", "source_key_epoch", "protocol_version",
                "event_type", "task_id", "owner_generation", "state_version",
                "idempotency_key", "payload_sha256", "payload_ciphertext", "signature_base64",
                "delivery_state", "attempt_count", "next_attempt_at", "acknowledged_at",
                "ack_sender_core_id", "ack_recipient_core_id", "ack_owner_generation",
                "ack_state_version", "ack_payload_sha256", "ack_signature_base64"
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
    @Test fun outboxRejectsDuplicateSequenceAndMismatchedAcknowledgement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("glafira_core_tasks.db")
        val store = GlafiraTaskStore(context, "glafira-outbox-test-core-0001")
        try {
            val base = ContentValues().apply {
                put("event_id", "event-1")
                put("source_core_id", "source-core-0001")
                put("recipient_core_id", "peer-core-0001")
                put("project_id", "project-1")
                put("source_sequence", 1L)
                put("source_key_epoch", 1L)
                put("protocol_version", 1)
                put("event_type", "TASK_RESULT")
                put("task_id", "task-1")
                put("owner_generation", 1L)
                put("state_version", 1L)
                put("idempotency_key", "task-1-result")
                put("payload_sha256", "a".repeat(64))
                put("payload_ciphertext", "ciphertext")
                put("signature_base64", "signature")
                put("created_at", 1_000L)
                put("next_attempt_at", 1_000L)
            }
            store.writableDatabase.insertOrThrow(GlafiraCoreOutboxSchema.TABLE, null, base)

            val duplicateSequence = ContentValues(base).apply {
                put("event_id", "event-2")
                put("idempotency_key", "task-2-result")
                put("task_id", "task-2")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                store.writableDatabase.insertOrThrow(GlafiraCoreOutboxSchema.TABLE, null, duplicateSequence)
            }

            val mismatchedAck = ContentValues(base).apply {
                put("event_id", "event-3")
                put("source_sequence", 2L)
                put("idempotency_key", "task-1-result-ack")
                put("delivery_state", "ACKNOWLEDGED")
                put("acknowledged_at", 2_000L)
                put("ack_sender_core_id", "peer-core-0001")
                put("ack_recipient_core_id", "source-core-0001")
                put("ack_owner_generation", 1L)
                put("ack_state_version", 1L)
                put("ack_payload_sha256", "b".repeat(64))
                put("ack_signature_base64", "ack-signature")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                store.writableDatabase.insertOrThrow(GlafiraCoreOutboxSchema.TABLE, null, mismatchedAck)
            }
        } finally {
            store.close()
            context.deleteDatabase("glafira_core_tasks.db")
        }
    }
}
