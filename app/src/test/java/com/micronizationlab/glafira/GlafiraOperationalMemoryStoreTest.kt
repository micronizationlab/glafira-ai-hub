package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class GlafiraOperationalMemoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var clock = Instant.parse("2026-11-21T00:00:00Z")
    private val ownerAccess = MemoryAccessContext(
        actorId = "owner-1",
        projectId = ProjectIdentifier("project-1"),
        agentRoleId = null,
        allowedScopes = setOf(MemoryScopeType.OWNER, MemoryScopeType.PROJECT),
        maximumClassification = CoreDataClassification.RESTRICTED
    )

    @Test fun confidentialFactRoundTripsWithoutPlaintextOnDisk() = runBlocking {
        val repository = repository()
        val record = record(content = "client-secret-contract", classification = CoreDataClassification.CONFIDENTIAL)

        assertEquals(MemoryWriteResult.Stored(record.id), repository.put(record, ownerAccess))
        assertEquals(record, repository.get(record.id, ownerAccess))

        val raw = temporary.root.resolve("core_operational_memory_v1.json").readText()
        assertFalse(raw.contains("client-secret-contract"))
        assertTrue(raw.contains("aes-gcm-v1"))
        assertTrue(raw.contains("source-document-7"))
    }

    @Test fun anotherProjectCannotReadSearchOrExportMemory() = runBlocking {
        val repository = repository()
        val record = record()
        assertTrue(repository.put(record, ownerAccess) is MemoryWriteResult.Stored)
        val otherProject = ownerAccess.copy(projectId = ProjectIdentifier("project-2"))

        assertNull(repository.get(record.id, otherProject))
        assertTrue(repository.search(MemoryQuery("contract", statuses = setOf(MemoryStatus.PROPOSED)), otherProject).isEmpty())
        assertTrue(repository.export(MemoryQuery("", statuses = setOf(MemoryStatus.PROPOSED)), otherProject).records.isEmpty())
    }

    @Test fun ownerCanCorrectExportAndDeleteMemoryWithDurableTombstone() = runBlocking {
        val repository = repository()
        val record = record(classification = CoreDataClassification.INTERNAL)
        assertTrue(repository.put(record, ownerAccess) is MemoryWriteResult.Stored)
        clock = clock.plusSeconds(60)

        assertEquals(
            MemoryWriteResult.Stored(record.id),
            repository.update(record.id, MemoryChange(content = "corrected contract", tags = setOf("verified")), ownerAccess)
        )
        val exported = repository.export(
            MemoryQuery("corrected", statuses = setOf(MemoryStatus.PROPOSED)), ownerAccess
        )
        assertEquals(1, exported.schemaVersion)
        assertEquals("corrected contract", exported.records.single().content)
        assertEquals(setOf("verified"), exported.records.single().tags)

        clock = clock.plusSeconds(60)
        assertEquals(MemoryDeleteResult.Deleted, repository.delete(record.id, ownerAccess))
        assertTrue(repository.search(MemoryQuery(""), ownerAccess).isEmpty())
        val tombstone = repository.search(
            MemoryQuery("", statuses = setOf(MemoryStatus.DELETED)), ownerAccess
        ).single()
        assertEquals(MemoryStatus.DELETED, tombstone.status)
        assertEquals("owner-1", tombstone.lastModifiedBy)
    }

    @Test fun factWithoutSourceAndUnsupportedScopeAreRejected() = runBlocking {
        val repository = repository()
        val missingSource = record().copy(
            provenance = MemoryProvenance("document", null, "owner-1", clock)
        )
        assertEquals(
            MemoryWriteResult.Rejected("MEMORY_FACT_SOURCE_REQUIRED"),
            repository.put(missingSource, ownerAccess)
        )
        val conversation = record().copy(scopeType = MemoryScopeType.CONVERSATION, scopeId = "conversation-1")
        assertEquals(
            MemoryWriteResult.Rejected("MEMORY_SCOPE_NOT_ALLOWED"),
            repository.put(conversation, ownerAccess)
        )
    }

    @Test fun ciphertextIsBoundToMemoryIdentifier() {
        val protector = protector()
        val ciphertext = protector.protect(MemoryIdentifier("memory-1"), "sensitive".toByteArray())
        assertTrue(runCatching {
            protector.unprotect(MemoryIdentifier("memory-2"), ciphertext)
        }.isFailure)
    }

    private fun repository() = GlafiraOperationalMemoryStore(temporary.root, protector()) { clock }
    private fun protector() = AesGcmMemoryPayloadProtector(
        SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    )

    private fun record(
        content: String = "signed contract facts",
        classification: CoreDataClassification = CoreDataClassification.CONFIDENTIAL
    ) = CoreMemoryRecord(
        id = MemoryIdentifier("memory-1"),
        scopeType = MemoryScopeType.PROJECT,
        scopeId = "project-1",
        kind = MemoryKind.FACT,
        content = content,
        classification = classification,
        status = MemoryStatus.PROPOSED,
        confidence = 0.9,
        confidenceBasis = MemoryConfidenceBasis.SOURCE_DERIVED,
        provenance = MemoryProvenance("document", "source-document-7", "owner-1", clock),
        createdAt = clock,
        updatedAt = clock,
        createdBy = "owner-1",
        lastModifiedBy = "owner-1",
        tags = setOf("contract")
    )
}
