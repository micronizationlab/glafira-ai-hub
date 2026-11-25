package com.micronizationlab.glafira

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreOutboxStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = "outbox-source-core-0001"
    private fun draft(id: String, key: String = "operation-$id", cipher: String = "cipher-$id") =
        GlafiraCoreOutboxDraft(id, "outbox-recipient-0001", "project-outbox", "task-$id",
            "TASK_ASSIGNMENT", 1L, 1, 1L, 0L, key,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            cipher, "signed-envelope", 1_700_000_000_000L)
    private fun openStore(): GlafiraTaskStore {
        context.deleteDatabase("glafira_core_tasks.db")
        return GlafiraTaskStore(context, source)
    }

    @Test fun sequenceIsMonotonicReplayIsIdempotentAndChangedPayloadConflicts() {
        val store = openStore()
        try {
            val outbox = GlafiraCoreOutboxStore(store.writableDatabase, source)
            val first = outbox.append(draft("event-one"))
            val replay = outbox.append(draft("event-one"))
            assertTrue(first.inserted); assertFalse(replay.inserted)
            assertEquals(first.sourceSequence, replay.sourceSequence)
            assertEquals(first.signatureBase64, replay.signatureBase64)
            assertEquals(1L, first.sourceSequence)
            assertEquals(2L, outbox.append(draft("event-two")).sourceSequence)
            assertThrows(IllegalArgumentException::class.java) {
                outbox.append(draft("event-one", cipher = "changed-cipher"))
            }
            assertEquals(2, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM core_outbox", null)
                .use { it.moveToFirst(); it.getInt(0) })
            assertEquals(2L, store.readableDatabase.rawQuery(
                "SELECT last_sequence FROM core_outbox_sequences WHERE source_core_id=?", arrayOf(source)
            ).use { it.moveToFirst(); it.getLong(0) })
        } finally { store.close(); context.deleteDatabase("glafira_core_tasks.db") }
    }

    @Test fun failedInsertRollsBackSequenceAllocation() {
        val store = openStore()
        try {
            val db = store.writableDatabase
            val outbox = GlafiraCoreOutboxStore(db, source)
            db.execSQL("CREATE TRIGGER fail_outbox BEFORE INSERT ON core_outbox BEGIN SELECT RAISE(ABORT,'injected'); END")
            assertThrows(SQLiteException::class.java) { outbox.append(draft("event-fails")) }
            assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM core_outbox_sequences", null)
                .use { it.moveToFirst(); it.getInt(0) })
            db.execSQL("DROP TRIGGER fail_outbox")
            assertEquals(1L, outbox.append(draft("event-after-failure")).sourceSequence)
        } finally { store.close(); context.deleteDatabase("glafira_core_tasks.db") }
    }
}
