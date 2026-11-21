package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class OwnerApprovedContextDispatchInstrumentedTest {
    @Test fun importPreviewExcludeDispatchAndAnswerCitationsStayOwnerScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "context-dispatch-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try {
            val access = KnowledgeAccessContext(
                actorId = "owner-1",
                projectId = ProjectIdentifier("project-1"),
                maximumClassification = CoreDataClassification.RESTRICTED
            )
            val protector = AesGcmMemoryPayloadProtector(
                SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")
            )
            val knowledge = GlafiraProjectKnowledgeStore(File(root, "knowledge"), protector)
            val allowedDocument = KnowledgeSourceRef(KnowledgeSourceIdentifier("allowed-doc"), 1)
            val forbiddenDocument = KnowledgeSourceRef(KnowledgeSourceIdentifier("forbidden-doc"), 1)
            assertTrue(knowledge.importDocument(
                document(access.projectId, allowedDocument, "Allowed title", "ALLOWED_DOCUMENT_EVIDENCE"), access
            ) is KnowledgeImportResult.Stored)
            assertTrue(knowledge.importDocument(
                document(access.projectId, forbiddenDocument, "Forbidden title", "FORBIDDEN_DOCUMENT_EVIDENCE"), access
            ) is KnowledgeImportResult.Stored)

            val chatDirectory = File(root, "chats").apply { assertTrue(mkdirs()) }
            val histories = mutableMapOf<String, List<Msg>>()
            chatFile(chatDirectory, "project-1", "Legal", "allowed-generation")
            chatFile(chatDirectory, "project-1", "Finance", "forbidden-generation")
            histories["project-1_Legal"] = listOf(Msg("ALLOWED_CHAT_EVIDENCE launch", true, "10:00"))
            histories["project-1_Finance"] = listOf(Msg("FORBIDDEN_CHAT_EVIDENCE launch", true, "10:01"))
            val assembler = ProjectChatContextAssembler(chatDirectory) { histories[it].orEmpty() }
            val allDocuments = KnowledgeContextPreviewService(knowledge).preview(
                query = "launch evidence",
                recipient = "Primary",
                access = access
            )
            val allChats = assembler.preview(access.projectId, "ГЛАФИРА", "Primary")
            assertEquals(setOf(allowedDocument, forbiddenDocument), allDocuments.citations.map { it.source }.toSet())
            val allowedChat = allChats.citations.single { it.agentName == "Legal" }.source
            assertNotNull(allChats.citations.singleOrNull { it.agentName == "Finance" })

            val dispatch = OwnerApprovedContextDispatchPlanner(
                KnowledgeContextPreviewService(knowledge), assembler
            ).prepare(
                selection = OwnerApprovedContextSelection(
                    query = "launch evidence",
                    documentSources = setOf(allowedDocument),
                    chatSources = setOf(allowedChat)
                ),
                recipient = "Primary",
                currentAgentName = "ГЛАФИРА",
                access = access
            )
            val primaryPayload = dispatch.primarySystemPrompt("BASE_PROMPT")
            val peerPayload = dispatch.peerConsultationPrompt()
            assertTrue(primaryPayload.contains("ALLOWED_DOCUMENT_EVIDENCE"))
            assertTrue(primaryPayload.contains("ALLOWED_CHAT_EVIDENCE"))
            assertFalse(primaryPayload.contains("FORBIDDEN_DOCUMENT_EVIDENCE"))
            assertFalse(primaryPayload.contains("FORBIDDEN_CHAT_EVIDENCE"))
            assertFalse(peerPayload.contains("ALLOWED_DOCUMENT_EVIDENCE"))
            assertFalse(peerPayload.contains("ALLOWED_CHAT_EVIDENCE"))
            assertFalse(peerPayload.contains("FORBIDDEN_DOCUMENT_EVIDENCE"))
            assertFalse(peerPayload.contains("FORBIDDEN_CHAT_EVIDENCE"))

            val manifest = dispatch.manifest("operation-1", Instant.parse("2026-11-21T03:00:00Z"))
            val manifestStore = GlafiraContextCitationManifestStore(File(root, "manifests"), protector)
            val manifestAccess = ContextCitationAccess(access.projectId, access.actorId)
            assertEquals(CitationManifestWriteResult.STORED, manifestStore.put(manifest, manifestAccess))
            val saved = manifestStore.get("operation-1", manifestAccess)
            assertEquals(listOf("allowed-doc"), saved?.documents?.map { it.sourceId })
            assertEquals(listOf(allowedChat.sourceId), saved?.chats?.map { it.sourceId })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun document(
        projectId: ProjectIdentifier,
        source: KnowledgeSourceRef,
        title: String,
        evidence: String
    ) = KnowledgeDocumentInput(
        projectId = projectId,
        source = source,
        title = title,
        sourceUri = "content://test/${source.sourceId.value}",
        mediaType = "text/plain",
        content = "$evidence launch evidence".toByteArray(),
        classification = CoreDataClassification.CONFIDENTIAL,
        importedAt = Instant.parse("2026-11-21T03:00:00Z")
    )

    private fun chatFile(directory: File, project: String, agent: String, content: String) {
        File(directory, "chat_${project}_${agent}.json").writeText(content)
    }
}
