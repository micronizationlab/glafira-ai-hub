package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class ContextCitationAccess(
    val projectId: ProjectIdentifier,
    val actorId: String
) {
    init { require(actorId.isNotBlank()) }
}

data class DocumentCitationReference(
    val sourceId: String,
    val version: Long,
    val title: String,
    val sourceUri: String,
    val classification: CoreDataClassification
) {
    init {
        require(sourceId.isNotBlank() && version > 0 && title.isNotBlank() && sourceUri.isNotBlank())
    }
}

data class ChatCitationReference(
    val sourceId: String,
    val contentVersion: String,
    val agentName: String,
    val classification: CoreDataClassification
) {
    init {
        require(sourceId.isNotBlank() && agentName.isNotBlank())
        require(contentVersion.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Exact sources used for one provider operation. Excerpts and prompt text are never stored here. */
data class ContextCitationManifest(
    val operationId: String,
    val projectId: ProjectIdentifier,
    val recipient: String,
    val createdAt: Instant,
    val documents: List<DocumentCitationReference>,
    val chats: List<ChatCitationReference>,
    val attributionStatus: SentenceAttributionStatus = SentenceAttributionStatus.PENDING,
    val sentenceAttributions: List<SentenceCitationAttribution> = emptyList()
) {
    init {
        require(operationId.isNotBlank() && recipient.isNotBlank())
        require(documents.isNotEmpty() || chats.isNotEmpty())
        require(documents.distinctBy { it.sourceId to it.version }.size == documents.size)
        require(chats.distinctBy { it.sourceId to it.contentVersion }.size == chats.size)
        require((attributionStatus == SentenceAttributionStatus.VALIDATED) == sentenceAttributions.isNotEmpty())
        val allowedAliases = buildSet {
            documents.indices.forEach { add("D${it + 1}") }
            chats.indices.forEach { add("C${it + 1}") }
        }
        require(sentenceAttributions.flatMap { it.sourceAliases }.all { it in allowedAliases })
        require(sentenceAttributions.map { it.sentenceOrdinal }.distinct().size == sentenceAttributions.size)
    }
}

enum class CitationManifestWriteResult { STORED, ALREADY_STORED }
enum class SentenceAttributionWriteResult { VALIDATED, ABSENT, REJECTED, ALREADY_FINALIZED, NOT_FOUND, CONFLICT }

class GlafiraContextCitationManifestStore(
    directory: File,
    private val payloadProtector: MemoryPayloadProtector
) {
    private val store = DurableJsonFile(
        File(directory.apply { check(mkdirs() || isDirectory) }, FILE_NAME),
        decode = ::decodeRecords,
        encode = ::encodeRecords
    )

    fun put(manifest: ContextCitationManifest, access: ContextCitationAccess): CitationManifestWriteResult {
        require(manifest.projectId == access.projectId) { "Citation manifest project mismatch" }
        var result = CitationManifestWriteResult.ALREADY_STORED
        store.update({ emptyList() }) { records ->
            val existing = records.firstOrNull { it.operationId == manifest.operationId }
            if (existing != null) {
                check(existing.projectId == access.projectId) { "Citation manifest operation belongs to another project" }
                check(decrypt(existing) == manifest) { "Citation manifest operation already has different sources" }
                records
            } else {
                result = CitationManifestWriteResult.STORED
                records + encrypt(manifest)
            }
        }
        return result
    }

    fun get(operationId: String, access: ContextCitationAccess): ContextCitationManifest? {
        if (operationId.isBlank()) return null
        val record = store.read { emptyList() }.firstOrNull { it.operationId == operationId } ?: return null
        if (record.projectId != access.projectId) return null
        return decrypt(record).takeIf { it.projectId == access.projectId }
    }

    fun finalizeAnswerAttributions(
        operationId: String,
        answer: String,
        access: ContextCitationAccess
    ): SentenceAttributionWriteResult {
        if (operationId.isBlank()) return SentenceAttributionWriteResult.NOT_FOUND
        var result = SentenceAttributionWriteResult.NOT_FOUND
        store.update({ emptyList() }) { records ->
            val index = records.indexOfFirst { it.operationId == operationId && it.projectId == access.projectId }
            if (index < 0) return@update records
            val manifest = decrypt(records[index])
            val parsed = GlafiraSentenceAttribution.parse(answer, manifest.documents.size, manifest.chats.size)
            val finalized = manifest.copy(
                attributionStatus = parsed.status,
                sentenceAttributions = parsed.attributions
            )
            if (manifest.attributionStatus != SentenceAttributionStatus.PENDING) {
                result = if (manifest == finalized) SentenceAttributionWriteResult.ALREADY_FINALIZED
                else SentenceAttributionWriteResult.CONFLICT
                return@update records
            }
            result = when (parsed.status) {
                SentenceAttributionStatus.VALIDATED -> SentenceAttributionWriteResult.VALIDATED
                SentenceAttributionStatus.ABSENT -> SentenceAttributionWriteResult.ABSENT
                SentenceAttributionStatus.REJECTED -> SentenceAttributionWriteResult.REJECTED
                SentenceAttributionStatus.PENDING -> error("Parser returned pending attribution")
            }
            records.toMutableList().apply { this[index] = encrypt(finalized) }
        }
        return result
    }

    fun delete(operationId: String, access: ContextCitationAccess) {
        store.update({ emptyList() }) { records ->
            records.filterNot { it.operationId == operationId && it.projectId == access.projectId }
        }
    }

    fun deleteProject(access: ContextCitationAccess) {
        store.update({ emptyList() }) { records -> records.filterNot { it.projectId == access.projectId } }
    }

    private fun encrypt(manifest: ContextCitationManifest): StoredCitationManifest {
        val plaintext = encodeManifest(manifest).toByteArray(Charsets.UTF_8)
        return try {
            StoredCitationManifest(
                operationId = manifest.operationId,
                projectId = manifest.projectId,
                createdAt = manifest.createdAt,
                payload = payloadProtector.protect(payloadId(manifest.operationId), plaintext)
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(record: StoredCitationManifest): ContextCitationManifest {
        val plaintext = payloadProtector.unprotect(payloadId(record.operationId), record.payload)
        return try {
            decodeManifest(String(plaintext, Charsets.UTF_8)).also {
                require(it.operationId == record.operationId && it.projectId == record.projectId)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun payloadId(operationId: String) = MemoryIdentifier("context-citation:$operationId")

    private data class StoredCitationManifest(
        val operationId: String,
        val projectId: ProjectIdentifier,
        val createdAt: Instant,
        val payload: String
    )

    private companion object {
        const val FILE_NAME = "context_citation_manifests_v1.json"

        fun encodeManifest(manifest: ContextCitationManifest): String = JSONObject().apply {
            put("operationId", manifest.operationId)
            put("projectId", manifest.projectId.value)
            put("recipient", manifest.recipient)
            put("createdAt", manifest.createdAt.toString())
            put("documents", JSONArray().apply { manifest.documents.forEach { citation -> put(JSONObject().apply {
                put("sourceId", citation.sourceId); put("version", citation.version); put("title", citation.title)
                put("sourceUri", citation.sourceUri); put("classification", citation.classification.name)
            }) } })
            put("chats", JSONArray().apply { manifest.chats.forEach { citation -> put(JSONObject().apply {
                put("sourceId", citation.sourceId); put("contentVersion", citation.contentVersion)
                put("agentName", citation.agentName); put("classification", citation.classification.name)
            }) } })
            put("attributionStatus", manifest.attributionStatus.name)
            put("sentenceAttributions", JSONArray().apply {
                manifest.sentenceAttributions.forEach { attribution -> put(JSONObject().apply {
                    put("sentenceOrdinal", attribution.sentenceOrdinal)
                    put("statementDigest", attribution.statementDigest)
                    put("sourceAliases", JSONArray(attribution.sourceAliases))
                }) }
            })
        }.toString()

        fun decodeManifest(raw: String): ContextCitationManifest {
            val value = JSONObject(raw)
            return ContextCitationManifest(
                operationId = value.getString("operationId"),
                projectId = ProjectIdentifier(value.getString("projectId")),
                recipient = value.getString("recipient"),
                createdAt = Instant.parse(value.getString("createdAt")),
                documents = value.getJSONArray("documents").let { array -> List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    DocumentCitationReference(
                        item.getString("sourceId"), item.getLong("version"), item.getString("title"),
                        item.getString("sourceUri"), CoreDataClassification.valueOf(item.getString("classification"))
                    )
                } },
                chats = value.getJSONArray("chats").let { array -> List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    ChatCitationReference(
                        item.getString("sourceId"), item.getString("contentVersion"), item.getString("agentName"),
                        CoreDataClassification.valueOf(item.getString("classification"))
                    )
                } },
                attributionStatus = SentenceAttributionStatus.valueOf(
                    value.optString("attributionStatus", SentenceAttributionStatus.PENDING.name)
                ),
                sentenceAttributions = value.optJSONArray("sentenceAttributions")?.let { array ->
                    List(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        SentenceCitationAttribution(
                            sentenceOrdinal = item.getInt("sentenceOrdinal"),
                            statementDigest = item.getString("statementDigest"),
                            sourceAliases = item.getJSONArray("sourceAliases").let { aliases ->
                                List(aliases.length()) { aliasIndex -> aliases.getString(aliasIndex) }
                            }
                        )
                    }
                }.orEmpty()
            )
        }

        fun decodeRecords(raw: String): List<StoredCitationManifest> {
            val array = JSONArray(raw)
            return List(array.length()) { index -> array.getJSONObject(index).let { item ->
                StoredCitationManifest(
                    item.getString("operationId"), ProjectIdentifier(item.getString("projectId")),
                    Instant.parse(item.getString("createdAt")), item.getString("payload")
                )
            } }.also { records ->
                require(records.map { it.operationId }.distinct().size == records.size)
            }
        }

        fun encodeRecords(records: List<StoredCitationManifest>): String = JSONArray().apply {
            records.forEach { record -> put(JSONObject().apply {
                put("operationId", record.operationId); put("projectId", record.projectId.value)
                put("createdAt", record.createdAt.toString()); put("payload", record.payload)
            }) }
        }.toString()
    }
}

object GlafiraContextCitationManifestRuntime {
    fun store(context: Context): GlafiraContextCitationManifestStore = GlafiraContextCitationManifestStore(
        File(context.noBackupFilesDir, "context-citation-manifests"),
        AndroidKeystoreMemoryPayloadProtector("glafira.core.context-citation.v1")
    )
}
