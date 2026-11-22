package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class GlafiraOwnerOperationalMemoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private var clock = Instant.parse("2026-11-22T12:00:00Z")

    @Test fun ownerFactRequiresSourceAndIsConfirmedInsideItsProject() = runBlocking {
        val service = service()
        assertEquals(
            MemoryWriteResult.Rejected("MEMORY_FACT_SOURCE_REQUIRED"),
            service.createFact("project-1", GlafiraOwnerMemoryFactDraft("fact", "document", ""))
        )

        val stored = service.createFact(
            "project-1",
            GlafiraOwnerMemoryFactDraft("signed fact", "document", "doc-7", tags = setOf(" signed "))
        )
        assertTrue(stored is MemoryWriteResult.Stored)
        val record = service.list("project-1").single()
        assertEquals(MemoryStatus.CONFIRMED, record.status)
        assertEquals(MemoryConfidenceBasis.OWNER_CONFIRMED, record.confidenceBasis)
        assertEquals("doc-7", record.provenance.sourceId)
        assertEquals(setOf("signed"), record.tags)
        assertTrue(service.list("project-2").isEmpty())
    }

    @Test fun ownerCanCorrectExportAndDeleteThroughProductionService() = runBlocking {
        val service = service()
        val stored = service.createFact(
            "project-1",
            GlafiraOwnerMemoryFactDraft("original", "owner", "owner-entry-1")
        ) as MemoryWriteResult.Stored
        clock = clock.plusSeconds(30)

        assertEquals(
            stored,
            service.correct("project-1", stored.id.value, "corrected", setOf(" verified "))
        )
        val exported = service.export("project-1")
        assertEquals("corrected", exported.records.single().content)
        assertEquals(setOf("verified"), exported.records.single().tags)

        assertEquals(MemoryDeleteResult.Deleted, service.delete("project-1", stored.id.value))
        assertTrue(service.list("project-1").isEmpty())
        assertEquals(MemoryStatus.DELETED, service.export("project-1").records.single().status)
    }

    private fun service(): GlafiraOwnerOperationalMemory {
        val protector = AesGcmMemoryPayloadProtector(
            SecretKeySpec(ByteArray(32) { (it + 9).toByte() }, "AES")
        )
        val repository = GlafiraOperationalMemoryStore(temporary.root, protector) { clock }
        var next = 0
        return GlafiraOwnerOperationalMemory(
            repository = repository,
            ownerId = "owner-1",
            now = { clock },
            newId = { MemoryIdentifier("memory-${++next}") }
        )
    }
}
