package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class GlafiraProjectKnowledgeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val projectOne = KnowledgeAccessContext(
        actorId = "owner-1",
        projectId = ProjectIdentifier("project-1"),
        maximumClassification = CoreDataClassification.RESTRICTED
    )

    @Test fun encryptedDocumentRetrievesVersionedOpenableCitation() {
        val store = repository()
        val source = KnowledgeSourceRef(KnowledgeSourceIdentifier("contract-7"), 3)
        val input = document(
            source = source,
            title = "Signed contract",
            sourceUri = "content://documents/contract-7-v3",
            text = "The delivery deadline is 18 December and acceptance requires a signed protocol."
        )

        assertEquals(KnowledgeImportResult.Stored(source), store.importDocument(input, projectOne))
        val citation = store.retrieve(KnowledgeQuery("delivery deadline"), projectOne).citations.single()
        assertEquals(source, citation.source)
        assertEquals("Signed contract", citation.title)
        assertEquals("content://documents/contract-7-v3", citation.sourceUri)
        assertEquals(KnowledgeEvidenceKind.SOURCE_EXCERPT, citation.evidenceKind)
        assertTrue(citation.excerpt.contains("18 December"))

        val durable = temporary.root.resolve("core_project_knowledge_v1.json").readText()
        assertFalse(durable.contains("Signed contract"))
        assertFalse(durable.contains("delivery deadline"))
        assertFalse(durable.contains("content://documents"))
        assertTrue(durable.contains("aes-gcm-v1"))
    }

    @Test fun retrievalNeverUsesAnotherProjectsDocument() {
        val store = repository()
        assertTrue(store.importDocument(document(text = "foreign project launch code"), projectOne) is KnowledgeImportResult.Stored)
        val projectTwo = projectOne.copy(projectId = ProjectIdentifier("project-2"))

        assertTrue(store.retrieve(KnowledgeQuery("launch code"), projectTwo).citations.isEmpty())
        assertEquals(
            KnowledgeImportResult.Rejected("KNOWLEDGE_PROJECT_MISMATCH"),
            store.importDocument(document(projectId = ProjectIdentifier("project-1")), projectTwo)
        )
    }

    @Test fun exactStoredVersionOpensOnlyInsideAuthorizedProjectAndClassification() {
        val store = repository()
        val source = KnowledgeSourceRef(KnowledgeSourceIdentifier("versioned-policy"), 7)
        val body = "Approved source fact from immutable imported version seven."
        assertEquals(
            KnowledgeImportResult.Stored(source),
            store.importDocument(
                document(
                    source = source,
                    title = "Versioned policy",
                    sourceUri = "content://mutable/provider/path",
                    text = body,
                    classification = CoreDataClassification.CONFIDENTIAL
                ),
                projectOne
            )
        )

        val snapshot = store.readDocument(source, projectOne)!!
        assertEquals(source, snapshot.source)
        assertEquals("Versioned policy", snapshot.title)
        assertEquals(body, snapshot.text)
        assertEquals(CoreDataClassification.CONFIDENTIAL, snapshot.classification)
        assertEquals(
            null,
            store.readDocument(source, projectOne.copy(projectId = ProjectIdentifier("project-2")))
        )
        assertEquals(
            null,
            store.readDocument(
                source,
                projectOne.copy(maximumClassification = CoreDataClassification.INTERNAL)
            )
        )
    }

    @Test fun ownerCanExcludeAContextSourceAndClassificationIsFailClosed() {
        val store = repository()
        val publicSource = KnowledgeSourceRef(KnowledgeSourceIdentifier("public"), 1)
        val restrictedSource = KnowledgeSourceRef(KnowledgeSourceIdentifier("restricted"), 1)
        assertTrue(store.importDocument(document(source = publicSource, text = "roadmap quartz public"), projectOne) is KnowledgeImportResult.Stored)
        assertTrue(store.importDocument(document(source = restrictedSource, text = "roadmap quartz restricted", classification = CoreDataClassification.RESTRICTED), projectOne) is KnowledgeImportResult.Stored)

        val internalOnly = projectOne.copy(maximumClassification = CoreDataClassification.INTERNAL)
        val result = store.retrieve(
            KnowledgeQuery("roadmap quartz", excludedSources = setOf(publicSource)),
            internalOnly
        )
        assertTrue(result.citations.isEmpty())
    }

    @Test fun citationExcerptIsRedactedAndBoundedByContextBudget() {
        val store = repository()
        val secret = "sk-abcdefghijklmnopqrstuvwxyz"
        val text = "deployment token $secret " + "bounded context ".repeat(80)
        assertTrue(store.importDocument(document(text = text), projectOne) is KnowledgeImportResult.Stored)

        val result = store.retrieve(
            KnowledgeQuery("deployment token", contextBudgetChars = 256),
            projectOne
        )
        assertEquals(result.citations.sumOf { it.excerpt.length }, result.usedContextChars)
        assertTrue(result.usedContextChars <= 256)
        assertFalse(result.citations.single().excerpt.contains(secret))
        assertTrue(result.citations.single().excerpt.contains("[СКРЫТО]"))
    }

    @Test fun importRejectsUnsupportedOversizedAndDuplicateVersions() {
        val store = repository()
        val valid = document()
        assertTrue(store.importDocument(valid, projectOne) is KnowledgeImportResult.Stored)
        assertEquals(
            KnowledgeImportResult.Rejected("KNOWLEDGE_VERSION_ALREADY_EXISTS"),
            store.importDocument(valid, projectOne)
        )
        assertEquals(
            KnowledgeImportResult.Rejected("KNOWLEDGE_MEDIA_TYPE_NOT_SUPPORTED"),
            store.importDocument(document(source = source("binary"), mediaType = "application/pdf"), projectOne)
        )
        assertEquals(
            KnowledgeImportResult.Rejected("KNOWLEDGE_IMPORT_TOO_LARGE"),
            store.importDocument(
                document(source = source("large"), bytes = ByteArray(512 * 1024 + 1) { 'x'.code.toByte() }),
                projectOne
            )
        )
        assertEquals(
            KnowledgeImportResult.Rejected("KNOWLEDGE_TEXT_INVALID_UTF8"),
            store.importDocument(
                document(source = source("invalid"), bytes = byteArrayOf(0xC3.toByte(), 0x28)),
                projectOne
            )
        )
    }

    @Test fun providerContextContainsOnlyOwnerApprovedSourcesAndNoLocalUri() {
        val store = repository()
        val approved = source("approved")
        val excluded = source("excluded")
        assertTrue(
            store.importDocument(
                document(
                    source = approved,
                    title = "Approved policy",
                    sourceUri = "content://private/approved",
                    text = "quartz approval threshold is seven"
                ),
                projectOne
            ) is KnowledgeImportResult.Stored
        )
        assertTrue(
            store.importDocument(
                document(
                    source = excluded,
                    title = "Excluded notes",
                    sourceUri = "content://private/excluded",
                    text = "quartz excluded draft is nine"
                ),
                projectOne
            ) is KnowledgeImportResult.Stored
        )

        val preview = KnowledgeContextPreviewService(store).preview(
            query = "quartz",
            recipient = "Primary provider",
            access = projectOne,
            includedSources = setOf(approved)
        )
        val prompt = preview.renderForProvider()
        assertEquals(listOf(approved), preview.citations.map { it.source })
        assertTrue(prompt.contains("Approved policy"))
        assertTrue(prompt.contains("threshold is seven"))
        assertFalse(prompt.contains("Excluded notes"))
        assertFalse(prompt.contains("content://private"))
    }

    @Test fun queryRejectsContradictoryOwnerSelection() {
        val source = source("same")
        assertTrue(runCatching {
            KnowledgeQuery(
                text = "quartz",
                includedSources = setOf(source),
                excludedSources = setOf(source)
            )
        }.isFailure)
    }

    private fun repository() = GlafiraProjectKnowledgeStore(
        temporary.root,
        AesGcmMemoryPayloadProtector(
            SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")
        )
    )

    private fun source(id: String) = KnowledgeSourceRef(KnowledgeSourceIdentifier(id), 1)

    private fun document(
        projectId: ProjectIdentifier = ProjectIdentifier("project-1"),
        source: KnowledgeSourceRef = source("source-1"),
        title: String = "Project source",
        sourceUri: String = "content://documents/source-1",
        mediaType: String = "text/plain",
        text: String = "project facts and verified source",
        bytes: ByteArray = text.toByteArray(),
        classification: CoreDataClassification = CoreDataClassification.INTERNAL
    ) = KnowledgeDocumentInput(
        projectId = projectId,
        source = source,
        title = title,
        sourceUri = sourceUri,
        mediaType = mediaType,
        content = bytes,
        classification = classification,
        importedAt = Instant.parse("2026-11-21T02:00:00Z")
    )
}
