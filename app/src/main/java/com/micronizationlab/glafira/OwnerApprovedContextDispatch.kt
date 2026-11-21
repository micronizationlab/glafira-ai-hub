package com.micronizationlab.glafira

import java.time.Instant

/** Exact owner selection after preview; an empty set means that source class is not exported. */
data class OwnerApprovedContextSelection(
    val query: String,
    val documentSources: Set<KnowledgeSourceRef>,
    val chatSources: Set<ChatContextSourceRef>
) {
    init {
        require(query.isNotBlank())
        require(documentSources.isNotEmpty() || chatSources.isNotEmpty())
    }
}

data class OwnerApprovedContextDispatch(
    val query: String,
    val recipient: String,
    val projectId: ProjectIdentifier,
    val documentPreview: KnowledgeContextPreview?,
    val chatPreview: ProjectChatContextPreview?
) {
    init {
        require(query.isNotBlank() && recipient.isNotBlank())
        require(documentPreview != null || chatPreview != null)
        require(documentPreview == null || documentPreview.projectId == projectId)
        require(chatPreview == null || chatPreview.projectId == projectId)
    }

    fun primarySystemPrompt(basePrompt: String): String = listOf(
        basePrompt,
        chatPreview?.renderForProvider().orEmpty(),
        documentPreview?.renderForProvider().orEmpty()
    ).filter { it.isNotBlank() }.joinToString("\n\n")

    /** Peer providers receive no document/chat excerpts; only this redacted question. */
    fun peerConsultationPrompt(): String = GlafiraPeerConsultationPolicy.buildPrompt(query)

    fun manifest(operationId: String, createdAt: Instant): ContextCitationManifest =
        ContextCitationManifest(
            operationId = operationId,
            projectId = projectId,
            recipient = recipient,
            createdAt = createdAt,
            documents = documentPreview?.citations.orEmpty().map { citation ->
                DocumentCitationReference(
                    citation.source.sourceId.value,
                    citation.source.version,
                    citation.title,
                    citation.sourceUri,
                    citation.classification
                )
            },
            chats = chatPreview?.citations.orEmpty().map { citation ->
                ChatCitationReference(
                    citation.source.sourceId,
                    citation.source.contentVersion,
                    citation.agentName,
                    citation.classification
                )
            }
        )
}

class OwnerApprovedContextDispatchPlanner(
    private val knowledgePreviewService: KnowledgeContextPreviewService,
    private val chatContextAssembler: ProjectChatContextAssembler
) {
    fun prepare(
        selection: OwnerApprovedContextSelection,
        recipient: String,
        currentAgentName: String,
        access: KnowledgeAccessContext
    ): OwnerApprovedContextDispatch {
        require(recipient.isNotBlank() && currentAgentName.isNotBlank())
        val documentPreview = selection.documentSources.takeIf { it.isNotEmpty() }?.let { sources ->
            knowledgePreviewService.preview(
                query = selection.query,
                recipient = recipient,
                access = access,
                includedSources = sources
            ).also { preview ->
                check(preview.citations.map { it.source }.toSet() == sources) {
                    "OWNER_APPROVED_DOCUMENT_VERSION_CHANGED"
                }
            }
        }
        val chatPreview = selection.chatSources.takeIf { it.isNotEmpty() }?.let { sources ->
            chatContextAssembler.preview(
                projectId = access.projectId,
                currentAgentName = currentAgentName,
                recipient = recipient,
                includedSources = sources
            ).also { preview ->
                check(preview.citations.map { it.source }.toSet() == sources) {
                    "OWNER_APPROVED_CHAT_VERSION_CHANGED"
                }
            }
        }
        return OwnerApprovedContextDispatch(
            query = selection.query,
            recipient = recipient,
            projectId = access.projectId,
            documentPreview = documentPreview,
            chatPreview = chatPreview
        )
    }
}

object GlafiraPeerConsultationPolicy {
    private const val MAX_QUESTION_CHARS = 2_000

    fun buildPrompt(question: String): String {
        val safeQuestion = GlafiraSecretRedactor.redact(question)
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(MAX_QUESTION_CHARS)
        return """
            Ты — независимый эксперт, которого пригласила Глафира.
            Дай самостоятельное мнение по задаче. Проверь предположения, риски и предложи
            конкретные действия. Не пытайся управлять Глафирой и не выдавай догадки за факты.
            Не запрашивай и не предполагай доступ к документам или другим чатам проекта:
            их содержимое не разрешено для peer consultation.
            Вопрос пользователя:
            $safeQuestion
        """.trimIndent()
    }
}
