package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class GlafiraContextCitationManifestStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val projectOne = ContextCitationAccess(ProjectIdentifier("project-1"), "owner-1")

    @Test fun exactSourcesRoundTripWithoutPromptOrExcerptsAtRest() {
        val store = repository()
        val manifest = manifest()

        assertEquals(CitationManifestWriteResult.STORED, store.put(manifest, projectOne))
        assertEquals(manifest, store.get("operation-1", projectOne))

        val durable = temporary.root.resolve("context_citation_manifests_v1.json").readText()
        assertFalse(durable.contains("Contract title"))
        assertFalse(durable.contains("content://contract"))
        assertFalse(durable.contains("chat:project-1:Legal"))
        assertFalse(durable.contains("user question"))
        assertFalse(durable.contains("source excerpt"))
    }

    @Test fun anotherProjectCannotReadOrReplaceManifest() {
        val store = repository()
        store.put(manifest(), projectOne)
        val projectTwo = ContextCitationAccess(ProjectIdentifier("project-2"), "owner-1")

        assertNull(store.get("operation-1", projectTwo))
        val failure = runCatching { store.put(manifest().copy(projectId = projectTwo.projectId), projectTwo) }
        assertTrue(failure.isFailure)
        assertEquals(manifest(), store.get("operation-1", projectOne))
    }

    @Test fun exactReplayIsIdempotentButDifferentSourcesAreRejected() {
        val store = repository()
        val manifest = manifest()

        assertEquals(CitationManifestWriteResult.STORED, store.put(manifest, projectOne))
        assertEquals(CitationManifestWriteResult.ALREADY_STORED, store.put(manifest, projectOne))
        assertTrue(runCatching {
            store.put(manifest.copy(recipient = "Different provider"), projectOne)
        }.isFailure)
    }

    @Test fun operationAndProjectDeletionAreScoped() {
        val store = repository()
        store.put(manifest(), projectOne)
        store.put(manifest().copy(operationId = "operation-2"), projectOne)

        store.delete("operation-1", projectOne)
        assertNull(store.get("operation-1", projectOne))
        assertEquals("operation-2", store.get("operation-2", projectOne)?.operationId)
        store.deleteProject(projectOne)
        assertNull(store.get("operation-2", projectOne))
    }

    @Test fun sentenceAttributionFinalizesOnceAndRejectsUnknownAliasesFailClosed() {
        val store = repository()
        store.put(manifest(), projectOne)

        assertEquals(
            SentenceAttributionWriteResult.VALIDATED,
            store.finalizeAnswerAttributions(
                "operation-1",
                "Договор подписан [D1]. Это вывод модели. Юрист подтвердил риск [C1].",
                projectOne
            )
        )
        val saved = store.get("operation-1", projectOne)!!
        assertEquals(SentenceAttributionStatus.VALIDATED, saved.attributionStatus)
        assertEquals(listOf(1, 3), saved.sentenceAttributions.map { it.sentenceOrdinal })
        assertEquals(listOf("D1"), saved.sentenceAttributions[0].sourceAliases)
        assertEquals(listOf("C1"), saved.sentenceAttributions[1].sourceAliases)
        assertEquals(
            SentenceAttributionWriteResult.ALREADY_FINALIZED,
            store.finalizeAnswerAttributions(
                "operation-1",
                "Договор подписан [D1]. Это вывод модели. Юрист подтвердил риск [C1].",
                projectOne
            )
        )
        assertEquals(
            SentenceAttributionWriteResult.CONFLICT,
            store.finalizeAnswerAttributions("operation-1", "Иной ответ [D1].", projectOne)
        )

        store.put(manifest().copy(operationId = "operation-2"), projectOne)
        assertEquals(
            SentenceAttributionWriteResult.REJECTED,
            store.finalizeAnswerAttributions("operation-2", "Неизвестный источник [D2].", projectOne)
        )
        assertEquals(SentenceAttributionStatus.REJECTED, store.get("operation-2", projectOne)?.attributionStatus)
        val durable = temporary.root.resolve("context_citation_manifests_v1.json").readText()
        assertFalse(durable.contains("Договор подписан"))
        assertFalse(durable.contains("Юрист подтвердил риск"))
    }

    private fun repository() = GlafiraContextCitationManifestStore(
        temporary.root,
        AesGcmMemoryPayloadProtector(SecretKeySpec(ByteArray(32) { (it + 9).toByte() }, "AES"))
    )

    private fun manifest() = ContextCitationManifest(
        operationId = "operation-1",
        projectId = projectOne.projectId,
        recipient = "Primary provider",
        createdAt = Instant.parse("2026-11-21T02:30:00Z"),
        documents = listOf(DocumentCitationReference(
            sourceId = "contract-7", version = 3, title = "Contract title",
            sourceUri = "content://contract/7", classification = CoreDataClassification.CONFIDENTIAL
        )),
        chats = listOf(ChatCitationReference(
            sourceId = "chat:project-1:Legal", contentVersion = "a".repeat(64),
            agentName = "Legal", classification = CoreDataClassification.RESTRICTED
        ))
    )
}
