package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class GlafiraCoreSecurityInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: GlafiraCoreDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
        File(context.filesDir, GlafiraCoreDatabase.DATABASE_NAME).delete()
        File(context.filesDir, "${GlafiraCoreDatabase.DATABASE_NAME}-wal").delete()
        File(context.filesDir, "${GlafiraCoreDatabase.DATABASE_NAME}-shm").delete()
    }

    @Test
    fun keystoreEncryptsPayloadAndRoundTrips() {
        val crypto = GlafiraMemoryCrypto(context)
        val plaintext = "owner-private-memory-value".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(
            memoryId = MemoryIdentifier("memory-instrumented-1"),
            schemaVersion = 1,
            scopeType = MemoryScopeType.PROJECT,
            scopeId = "project-instrumented",
            kind = MemoryKind.FACT,
            classification = CoreDataClassification.CONFIDENTIAL,
            status = MemoryStatus.PROPOSED,
            confidence = 0.9,
            confidenceBasis = MemoryConfidenceBasis.SOURCE_DERIVED,
            createdAt = Instant.parse("2026-11-18T10:00:00Z"),
            updatedAt = Instant.parse("2026-11-18T10:00:00Z"),
            validFrom = null,
            expiresAt = null,
            plaintext = plaintext
        )

        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("owner-private-memory-value"))
        val decrypted = crypto.decrypt(
            memoryId = MemoryIdentifier("memory-instrumented-1"),
            schemaVersion = 1,
            scopeType = MemoryScopeType.PROJECT,
            scopeId = "project-instrumented",
            kind = MemoryKind.FACT,
            classification = CoreDataClassification.CONFIDENTIAL,
            status = MemoryStatus.PROPOSED,
            confidence = 0.9,
            confidenceBasis = MemoryConfidenceBasis.SOURCE_DERIVED,
            createdAt = Instant.parse("2026-11-18T10:00:00Z"),
            updatedAt = Instant.parse("2026-11-18T10:00:00Z"),
            validFrom = null,
            expiresAt = null,
            payload = encrypted
        )
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun authenticatedMetadataTamperingFailsDecryption() {
        val crypto = GlafiraMemoryCrypto(context)
        val created = Instant.parse("2026-11-18T10:00:00Z")
        val encrypted = crypto.encrypt(
            MemoryIdentifier("memory-tamper-1"), 1, MemoryScopeType.PROJECT,
            "project-instrumented", MemoryKind.FACT, CoreDataClassification.INTERNAL,
            MemoryStatus.PROPOSED, 0.8, MemoryConfidenceBasis.SOURCE_DERIVED,
            created, created, null, null, "tamper-check".toByteArray()
        )

        assertThrows(Exception::class.java) {
            crypto.decrypt(
                MemoryIdentifier("memory-tamper-1"), 1, MemoryScopeType.PROJECT,
                "project-instrumented", MemoryKind.FACT, CoreDataClassification.CONFIDENTIAL,
                MemoryStatus.PROPOSED, 0.8, MemoryConfidenceBasis.SOURCE_DERIVED,
                created, created, null, null, encrypted
            )
        }
    }

    @Test
    fun traceRowsRejectUpdateAndDelete() = runBlocking {
        val db = GlafiraCoreDatabase(context)
        database = db
        val repository = GlafiraSqliteTraceRepository(db)
        repository.append(
            CoreTraceEvent(
                eventId = TraceEventIdentifier("trace-instrumented-1"),
                occurredAt = Instant.parse("2026-11-18T10:00:00Z"),
                recordedAt = Instant.parse("2026-11-18T10:00:01Z"),
                eventType = CoreTraceEventType.MEMORY_PROPOSED,
                severity = CoreTraceSeverity.INFO,
                actor = CoreActor("owner-instrumented", CoreActorType.OWNER),
                projectId = ProjectIdentifier("project-instrumented"),
                taskId = null,
                parentTaskId = null,
                correlationId = CoreIdentifier("correlation-instrumented"),
                causationId = null,
                component = "CoreSecurityTest",
                action = "trace.append",
                targetType = "MEMORY",
                targetId = "memory-instrumented-1",
                resultCode = "OK",
                approvalRef = null,
                redactionApplied = false
            )
        )

        val values = ContentValues().apply { put("action", "tampered") }
        assertThrows(SQLException::class.java) {
            db.writableDatabase.update(
                "trace_events", values, "event_id = ?", arrayOf("trace-instrumented-1")
            )
        }
        assertThrows(SQLException::class.java) {
            db.writableDatabase.delete(
                "trace_events", "event_id = ?", arrayOf("trace-instrumented-1")
            )
        }
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM trace_events WHERE event_id = ?",
            arrayOf("trace-instrumented-1")
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}
