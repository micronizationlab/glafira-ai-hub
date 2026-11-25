package com.micronizationlab.glafira

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreOutboxStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sourceCoreId = "outbox-source-core-0001"

    private fun draft(
        eventId: String,
        idempotencyKey: String = "operation-$eventId",
        ciphertext: String = "already-encrypted-$eventId"
    ) = GlafiraCoreOutboxDraft(
        eventId = eventId,
        recipientCoreId = "outbox-recipient-0001",
        projectId = "project-outbox",
        taskId = "task-$eventId",
        eventType = "TASK_ASSIGNMENT",
        sourceKeyEpoch = 1L,
        protocolVersion = 1,
        ownerGeneration = 1L,
        stateVersion = 0L,
        idempotencyKey = idempotencyKey,
        payloadSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        payloadCiphertext = ciphertext,
        signatureBase64 = "already-signed-envelope",
        createdAtMillis = 1_700_000_000_000L
    )

    private fun openStore(): GlafiraTaskStore {
        context.deleteDatabase("glafira_core_tasks.db")
        return GlafiraTaskStore(context, sourceCoreId)
    }

    @Test
    fun appendAllocatesMonotonicSequenceAndIdempotentReplayDoesNotConsumeOne() {
        val taskStore = openStore()
        try {
            val outbox = GlafiraCoreOutboxStore(taskStore.writableDatabase, sourceCoreId)
            val first = outbox.append(draft("event-one"))
            val replay = outbox.append(draft("event-one"))
            val second = outbox.append(draft("event-two"))

            assertTrue(first.inserted)
            assertFalse(replay.inserted)
            assertEquals(first.eventId, replay.eventId)
            assertEquals(first.sourceSequence, replay.sourceSequence)
            assertEquals("already-signed-envelope", replay.signatureBase64)
            assertEquals(1L, first.sourceSequence)
            assertEquals(2L, second.sourceSequence)
            assertEquals(2, taskStore.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM core_outbox", null
            ).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(2L, taskStore.readableDatabase.rawQuery(
                "SELECT last_sequence FROM core_outbox_sequences WHERE source_core_id = ?",
                arrayOf(sourceCoreId)
            ).use { it.moveToFirst(); it.getLong(0) })
        } finally {
            taskStore.close()
            context.deleteDatabase("glafira_core_tasks.db")
        }
    }

    @Test
    fun idempotencyKeyCannotBeReusedForChangedCiphertext() {
        val taskStore = openStore()
        try {
            val outbox = GlafiraCoreOutboxStore(taskStore.writableDatabase, sourceCoreId)
            outbox.append(draft("event-original", idempotencyKey = "stable-operation"))
            assertThrows(IllegalArgumentException::class.java) {
                outbox.append(draft(
                    "event-original",
                    idempotencyKey = "stable-operation",
                    ciphertext = "different-ciphertext"
                ))
            }
            assertEquals(1, taskStore.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM core_outbox", null
            ).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(1L, taskStore.readableDatabase.rawQuery(
                "SELECT last_sequence FROM core_outbox_sequences WHERE source_core_id = ?",
                arrayOf(sourceCoreId)
            ).use { it.moveToFirst(); it.getLong(0) })
        } finally {
            taskStore.close()
            context.deleteDatabase("glafira_core_tasks.db")
        }
    }

    @Test
    fun failedOutboxInsertRollsBackSequenceAllocation() {
        val taskStore = openStore()
        try {
            val db = taskStore.writableDatabase
            val outbox = GlafiraCoreOutboxStore(db, sourceCoreId)
            db.execSQL("""
                CREATE TRIGGER fail_core_outbox_insert
                BEFORE INSERT ON core_outbox
                BEGIN
                    SELECT RAISE(ABORT, 'injected-outbox-insert-failure');
                END
            """.trimIndent())

            assertThrows(SQLiteException::class.java) {
                outbox.append(draft("event-fails"))
            }
            assertEquals(0, db.rawQuery(
                "SELECT COUNT(*) FROM core_outbox_sequences", null
            ).use { it.moveToFirst(); it.getInt(0) })
            db.execSQL("DROP TRIGGER fail_core_outbox_insert")

            val accepted = outbox.append(draft("event-after-failure"))
            assertEquals(1L, accepted.sourceSequence)
            assertEquals(1, db.rawQuery(
                "SELECT COUNT(*) FROM core_outbox", null
            ).use { it.moveToFirst(); it.getInt(0) })
        } finally {
            taskStore.close()
            context.deleteDatabase("glafira_core_tasks.db")
        }
    }
}
