package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

@JvmInline
value class KnowledgeSourceIdentifier(val value: String) {
    init { require(value.isNotBlank()) { "Knowledge source identifier must not be blank" } }
}

data class KnowledgeSourceRef(
    val sourceId: KnowledgeSourceIdentifier,
    val version: Int
) {
    init { require(version > 0) { "Knowledge source version must be positive" } }
}

data class KnowledgeDocumentInput(
    val projectId: ProjectIdentifier,
    val source: KnowledgeSourceRef,
    val title: String,
    val sourceUri: String,
    val mediaType: String,
    val content: ByteArray,
    val classification: CoreDataClassification,
    val importedAt: Instant
) {
    init {
        require(title.isNotBlank() && sourceUri.isNotBlank())
        require(mediaType.isNotBlank())
        require(content.isNotEmpty())
    }
}

data class KnowledgeAccessContext(
    val actorId: String,
    val projectId: ProjectIdentifier,
    val maximumClassification: CoreDataClassification
) {
    init { require(actorId.isNotBlank()) }
}

sealed interface KnowledgeImportResult {
    data class Stored(val source: KnowledgeSourceRef) : KnowledgeImportResult
    data class Rejected(val reasonCode: String) : KnowledgeImportResult
}

data class KnowledgeQuery(
    val text: String,
    val excludedSources: Set<KnowledgeSourceRef> = emptySet(),
    val limit: Int = 5,
    val contextBudgetChars: Int = 4_000
) {
    init {
        require(text.isNotBlank()) { "Knowledge query must not be blank" }
        require(limit in 1..20) { "Knowledge query limit must be between 1 and 20" }
        require(contextBudgetChars in 256..20_000) { "Context budget must be between 256 and 20000" }
    }
}

enum class KnowledgeEvidenceKind { SOURCE_EXCERPT }

data class KnowledgeCitation(
    val source: KnowledgeSourceRef,
    val title: String,
    val sourceUri: String,
    val excerpt: String,
    val startOffset: Int,
    val endOffset: Int,
    val classification: CoreDataClassification,
    val evidenceKind: KnowledgeEvidenceKind = KnowledgeEvidenceKind.SOURCE_EXCERPT
) {
    init {
        require(title.isNotBlank() && sourceUri.isNotBlank() && excerpt.isNotBlank())
        require(startOffset >= 0 && endOffset >= startOffset)
    }
}

data class KnowledgeRetrieval(
    val citations: List<KnowledgeCitation>,
    val usedContextChars: Int
) {
    init { require(usedContextChars == citations.sumOf { it.excerpt.length }) }
}

interface ProjectKnowledgeRepository {
    fun importDocument(input: KnowledgeDocumentInput, access: KnowledgeAccessContext): KnowledgeImportResult
    fun retrieve(query: KnowledgeQuery, access: KnowledgeAccessContext): KnowledgeRetrieval
}

/**
 * Durable, encrypted project knowledge with versioned citations.
 *
 * Only bounded UTF-8 text and Markdown are accepted. The title, source URI and document body
 * share one authenticated encrypted payload; the durable index contains authorization metadata
 * only. Retrieval decrypts only records authorized for the exact project and classification.
 */
class GlafiraProjectKnowledgeStore(
    directory: File,
    private val payloadProtector: MemoryPayloadProtector
) : ProjectKnowledgeRepository {
    private val store = DurableJsonFile(
        storageFile(directory, FILE_NAME),
        decode = ::decode,
        encode = ::encode
    )

    override fun importDocument(
        input: KnowledgeDocumentInput,
        access: KnowledgeAccessContext
    ): KnowledgeImportResult {
        if (input.projectId != access.projectId) return KnowledgeImportResult.Rejected("KNOWLEDGE_PROJECT_MISMATCH")
        if (input.classification.ordinal > access.maximumClassification.ordinal) {
            return KnowledgeImportResult.Rejected("KNOWLEDGE_CLASSIFICATION_NOT_ALLOWED")
        }
        if (input.mediaType.lowercase() !in SUPPORTED_MEDIA_TYPES) {
            return KnowledgeImportResult.Rejected("KNOWLEDGE_MEDIA_TYPE_NOT_SUPPORTED")
        }
        if (input.content.size > MAX_IMPORT_BYTES) return KnowledgeImportResult.Rejected("KNOWLEDGE_IMPORT_TOO_LARGE")
        val decoded = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.content))
                .toString()
        }.getOrElse {
            return KnowledgeImportResult.Rejected("KNOWLEDGE_TEXT_INVALID_UTF8")
        }
        val text = decoded
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .trim()
        if (text.isBlank()) return KnowledgeImportResult.Rejected("KNOWLEDGE_CONTENT_EMPTY")
        if (text.length > MAX_TEXT_CHARS) return KnowledgeImportResult.Rejected("KNOWLEDGE_TEXT_TOO_LARGE")

        val document = KnowledgeDocument(
            projectId = input.projectId,
            source = input.source,
            title = input.title.trim().take(MAX_TITLE_CHARS),
            sourceUri = input.sourceUri.trim().take(MAX_URI_CHARS),
            mediaType = input.mediaType.lowercase(),
            text = text,
            classification = input.classification,
            importedAt = input.importedAt,
            importedBy = access.actorId
        )
        var result: KnowledgeImportResult = KnowledgeImportResult.Rejected("KNOWLEDGE_VERSION_ALREADY_EXISTS")
        store.update({ emptyList() }) { documents ->
            if (documents.any {
                    it.projectId == document.projectId && it.source == document.source
                }) documents
            else documents.plus(document).also {
                result = KnowledgeImportResult.Stored(document.source)
            }
        }
        return result
    }

    override fun retrieve(
        query: KnowledgeQuery,
        access: KnowledgeAccessContext
    ): KnowledgeRetrieval {
        val terms = query.text.lowercase().split(TOKEN_SEPARATOR)
            .filter { it.length >= MIN_TERM_LENGTH }
            .distinct()
            .take(MAX_QUERY_TERMS)
        if (terms.isEmpty()) return KnowledgeRetrieval(emptyList(), 0)

        val ranked = store.read { emptyList() }.asSequence()
            .filter { it.projectId == access.projectId }
            .filter { it.classification.ordinal <= access.maximumClassification.ordinal }
            .filter { it.source !in query.excludedSources }
            .mapNotNull { document ->
                val normalized = document.text.lowercase()
                val offsets = terms.mapNotNull { term ->
                    normalized.indexOf(term).takeIf { it >= 0 }
                }
                if (offsets.isEmpty()) null
                else RankedDocument(document, offsets.size, offsets.minOrNull() ?: 0)
            }
            .sortedWith(
                compareByDescending<RankedDocument> { it.matchedTerms }
                    .thenByDescending { it.document.source.version }
                    .thenBy { it.document.source.sourceId.value }
            )
            .take(query.limit)
            .toList()

        var remaining = query.contextBudgetChars
        val citations = buildList {
            ranked.forEach { rankedDocument ->
                if (remaining <= 0) return@forEach
                val citation = citation(rankedDocument, remaining) ?: return@forEach
                add(citation)
                remaining -= citation.excerpt.length
            }
        }
        return KnowledgeRetrieval(citations, citations.sumOf { it.excerpt.length })
    }

    private fun citation(ranked: RankedDocument, budget: Int): KnowledgeCitation? {
        val document = ranked.document
        val maxLength = minOf(MAX_EXCERPT_CHARS, budget)
        if (maxLength <= 0) return null
        val windowStart = (ranked.firstOffset - EXCERPT_LEAD_CHARS).coerceAtLeast(0)
        val windowEnd = (windowStart + maxLength).coerceAtMost(document.text.length)
        val window = document.text.substring(windowStart, windowEnd)
        val leadingWhitespace = window.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val trailingWhitespace = window.indexOfLast { !it.isWhitespace() }.coerceAtLeast(leadingWhitespace)
        val start = windowStart + leadingWhitespace
        val end = windowStart + trailingWhitespace + 1
        val raw = document.text.substring(start, end)
        if (raw.isBlank()) return null
        val safe = GlafiraSecretRedactor.redact(raw).take(maxLength)
        if (safe.isBlank()) return null
        return KnowledgeCitation(
            source = document.source,
            title = document.title,
            sourceUri = document.sourceUri,
            excerpt = safe,
            startOffset = start,
            endOffset = end,
            classification = document.classification
        )
    }

    private fun encode(documents: List<KnowledgeDocument>): String = JSONArray().apply {
        documents.sortedWith(
            compareBy<KnowledgeDocument> { it.projectId.value }
                .thenBy { it.source.sourceId.value }
                .thenBy { it.source.version }
        ).forEach { document ->
            val payload = JSONObject().apply {
                put("title", document.title)
                put("sourceUri", document.sourceUri)
                put("text", document.text)
            }.toString().toByteArray(Charsets.UTF_8)
            val protected = try {
                payloadProtector.protect(payloadId(document), payload)
            } finally {
                payload.fill(0)
            }
            put(JSONObject().apply {
                put("projectId", document.projectId.value)
                put("sourceId", document.source.sourceId.value)
                put("version", document.source.version)
                put("mediaType", document.mediaType)
                put("classification", document.classification.name)
                put("importedAt", document.importedAt.toEpochMilli())
                put("importedBy", document.importedBy)
                put("payloadEncoding", "aes-gcm-v1")
                put("payload", protected)
            })
        }
    }.toString()

    private fun decode(raw: String): List<KnowledgeDocument> {
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            require(item.getString("payloadEncoding") == "aes-gcm-v1")
            val projectId = ProjectIdentifier(item.getString("projectId"))
            val source = KnowledgeSourceRef(
                KnowledgeSourceIdentifier(item.getString("sourceId")),
                item.getInt("version")
            )
            val marker = KnowledgeDocument(
                projectId = projectId,
                source = source,
                title = "pending",
                sourceUri = "pending",
                mediaType = item.getString("mediaType"),
                text = "pending",
                classification = CoreDataClassification.valueOf(item.getString("classification")),
                importedAt = Instant.ofEpochMilli(item.getLong("importedAt")),
                importedBy = item.getString("importedBy")
            )
            val plaintext = payloadProtector.unprotect(payloadId(marker), item.getString("payload"))
            try {
                val payload = JSONObject(plaintext.toString(Charsets.UTF_8))
                marker.copy(
                    title = payload.getString("title"),
                    sourceUri = payload.getString("sourceUri"),
                    text = payload.getString("text")
                )
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private fun payloadId(document: KnowledgeDocument) = MemoryIdentifier(
        "knowledge:${document.projectId.value}:${document.source.sourceId.value}:${document.source.version}"
    )

    private data class KnowledgeDocument(
        val projectId: ProjectIdentifier,
        val source: KnowledgeSourceRef,
        val title: String,
        val sourceUri: String,
        val mediaType: String,
        val text: String,
        val classification: CoreDataClassification,
        val importedAt: Instant,
        val importedBy: String
    )

    private data class RankedDocument(
        val document: KnowledgeDocument,
        val matchedTerms: Int,
        val firstOffset: Int
    )

    private companion object {
        const val FILE_NAME = "core_project_knowledge_v1.json"
        const val MAX_IMPORT_BYTES = 512 * 1024
        const val MAX_TEXT_CHARS = 400_000
        const val MAX_TITLE_CHARS = 240
        const val MAX_URI_CHARS = 2_000
        const val MAX_QUERY_TERMS = 12
        const val MIN_TERM_LENGTH = 2
        const val MAX_EXCERPT_CHARS = 600
        const val EXCERPT_LEAD_CHARS = 120
        val SUPPORTED_MEDIA_TYPES = setOf("text/plain", "text/markdown")
        val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}_-]+")
    }
}
