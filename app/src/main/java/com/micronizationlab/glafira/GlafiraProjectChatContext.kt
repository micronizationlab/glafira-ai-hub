package com.micronizationlab.glafira

import java.io.File
import java.security.MessageDigest

data class ChatContextSourceRef(
    val sourceId: String,
    val contentVersion: String
) {
    init {
        require(sourceId.isNotBlank())
        require(contentVersion.matches(Regex("[0-9a-f]{64}")))
    }
}

data class ChatContextCitation(
    val source: ChatContextSourceRef,
    val agentName: String,
    val excerpt: String,
    val classification: CoreDataClassification = CoreDataClassification.RESTRICTED
) {
    init { require(agentName.isNotBlank() && excerpt.isNotBlank()) }
}

data class ProjectChatContextPreview(
    val projectId: ProjectIdentifier,
    val recipient: String,
    val citations: List<ChatContextCitation>,
    val usedContextChars: Int
) {
    init {
        require(recipient.isNotBlank())
        require(usedContextChars == citations.sumOf { it.excerpt.length })
    }

    fun renderForProvider(): String {
        if (citations.isEmpty()) return ""
        return buildString {
            appendLine("Разрешённый контекст других чатов проекта ${projectId.value}.")
            appendLine("Это фрагменты разговоров, а не подтверждённые документальные факты.")
            citations.forEachIndexed { index, citation ->
                appendLine()
                appendLine(
                    "[C${index + 1} · Чат: ${citation.source.sourceId}; " +
                        "версия ${citation.source.contentVersion.take(12)}; ${citation.agentName}]"
                )
                appendLine(citation.excerpt)
            }
        }.trim()
    }
}

/**
 * Builds a project-isolated, redacted preview from durable chat files.
 * Exact content hashes fence owner approval: a chat changed after preview is not sent.
 */
class ProjectChatContextAssembler(
    private val directory: File,
    private val historyLoader: (String) -> List<Msg>
) {
    fun preview(
        projectId: ProjectIdentifier,
        currentAgentName: String,
        recipient: String,
        includedSources: Set<ChatContextSourceRef>? = null,
        maxMessagesPerChat: Int = 5,
        maxSources: Int = 10,
        contextBudgetChars: Int = 4_000
    ): ProjectChatContextPreview {
        require(currentAgentName.isNotBlank() && recipient.isNotBlank())
        require(maxMessagesPerChat in 1..20)
        require(maxSources in 1..20)
        require(contextBudgetChars in 256..20_000)

        val prefix = "chat_${projectId.value}_"
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".json") &&
                file.length() in 1..MAX_CHAT_FILE_BYTES
        }.orEmpty().sortedBy { it.name }

        var remaining = contextBudgetChars
        val citations = buildList {
            files.forEach { file ->
                if (size >= maxSources || remaining <= 0) return@forEach
                val agentName = file.name.removePrefix(prefix).removeSuffix(".json")
                if (agentName.isBlank() || agentName == currentAgentName) return@forEach
                val source = ChatContextSourceRef(
                    sourceId = "chat:${projectId.value}:$agentName",
                    contentVersion = sha256(file)
                )
                if (includedSources != null && source !in includedSources) return@forEach
                val historyKey = "${projectId.value}_$agentName"
                val history = runCatching { historyLoader(historyKey) }.getOrNull().orEmpty()
                if (history.isEmpty()) return@forEach
                // Close the hash/load race: approval is valid only when the durable generation
                // remained byte-identical while its messages were read.
                if (sha256(file) != source.contentVersion) return@forEach
                val excerpt = buildString {
                    history.takeLast(maxMessagesPerChat).forEach { message ->
                        val author = if (message.isUser) "Пользователь" else agentName
                        val safe = GlafiraSecretRedactor.redact(message.text)
                            .replace(Regex("[\\r\\n]+"), " ")
                            .take(MAX_MESSAGE_CHARS)
                            .trim()
                        if (safe.isNotBlank()) appendLine("$author: $safe")
                    }
                }.trim().take(minOf(MAX_SOURCE_CHARS, remaining))
                if (excerpt.isBlank()) return@forEach
                add(ChatContextCitation(source, agentName, excerpt))
                remaining -= excerpt.length
            }
        }
        return ProjectChatContextPreview(
            projectId = projectId,
            recipient = recipient,
            citations = citations,
            usedContextChars = citations.sumOf { it.excerpt.length }
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            buffer.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_CHAT_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_MESSAGE_CHARS = 300
        const val MAX_SOURCE_CHARS = 1_200
    }
}
