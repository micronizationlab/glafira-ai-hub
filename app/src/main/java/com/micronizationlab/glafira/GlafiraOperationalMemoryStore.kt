package com.micronizationlab.glafira

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protects only the memory payload; authorization metadata remains queryable. */
interface MemoryPayloadProtector {
    fun protect(memoryId: MemoryIdentifier, plaintext: ByteArray): String
    fun unprotect(memoryId: MemoryIdentifier, encoded: String): ByteArray
}

/** AES-256-GCM envelope with the memory identifier bound as authenticated data. */
class AesGcmMemoryPayloadProtector(
    private val key: SecretKey
) : MemoryPayloadProtector {
    override fun protect(memoryId: MemoryIdentifier, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore keys with randomized encryption enabled reject a caller-provided IV.
        // Let the provider generate it and persist that IV in the existing envelope format.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected AES-GCM IV length" }
        cipher.updateAAD(memoryId.value.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = iv + ciphertext
        return try {
            Base64.getEncoder().encodeToString(envelope)
        } finally {
            envelope.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    override fun unprotect(memoryId: MemoryIdentifier, encoded: String): ByteArray {
        val envelope = Base64.getDecoder().decode(encoded)
        require(envelope.size > IV_BYTES + TAG_BYTES) { "Invalid memory payload envelope" }
        val iv = envelope.copyOfRange(0, IV_BYTES)
        val ciphertext = envelope.copyOfRange(IV_BYTES, envelope.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(memoryId.value.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext)
        } finally {
            envelope.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/** Device-bound production key. Recovery exports must re-encrypt through the portable backup flow. */
class AndroidKeystoreMemoryPayloadProtector(
    alias: String = "glafira.core.operational-memory.v1"
) : MemoryPayloadProtector {
    private val delegate = AesGcmMemoryPayloadProtector(loadOrCreate(alias))
    override fun protect(memoryId: MemoryIdentifier, plaintext: ByteArray): String =
        delegate.protect(memoryId, plaintext)
    override fun unprotect(memoryId: MemoryIdentifier, encoded: String): ByteArray =
        delegate.unprotect(memoryId, encoded)

    private companion object {
        fun loadOrCreate(alias: String): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }
    }
}

/**
 * Durable owner/project-scoped operational memory.
 *
 * The file is written with DurableJsonFile generation recovery. CONFIDENTIAL and
 * RESTRICTED content is always an authenticated ciphertext; no plaintext fallback exists.
 */
class GlafiraOperationalMemoryStore(
    directory: File,
    private val payloadProtector: MemoryPayloadProtector,
    private val now: () -> Instant = Instant::now
) : MemoryRepository {
    private val store = DurableJsonFile(
        storageFile(directory, FILE_NAME),
        decode = ::decode,
        encode = ::encode
    )

    override suspend fun put(
        record: CoreMemoryRecord,
        access: MemoryAccessContext
    ): MemoryWriteResult {
        authorizationFailure(record, access)?.let { return MemoryWriteResult.Rejected(it) }
        provenanceFailure(record)?.let { return MemoryWriteResult.Rejected(it) }
        var result: MemoryWriteResult = MemoryWriteResult.Rejected("MEMORY_ALREADY_EXISTS")
        store.update({ emptyList() }) { records ->
            if (records.any { it.id == record.id }) records
            else records.plus(record).also { result = MemoryWriteResult.Stored(record.id) }
        }
        return result
    }

    override suspend fun get(
        id: MemoryIdentifier,
        access: MemoryAccessContext
    ): CoreMemoryRecord? = store.read { emptyList() }
        .firstOrNull { it.id == id && authorizationFailure(it, access) == null }

    override suspend fun search(
        query: MemoryQuery,
        access: MemoryAccessContext
    ): List<CoreMemoryRecord> {
        val instant = now()
        val needle = query.text.trim().lowercase()
        return store.read { emptyList() }.asSequence()
            .filter { authorizationFailure(it, access) == null }
            .filter { query.scopes.isEmpty() || it.scopeType in query.scopes }
            .filter { query.statuses.isEmpty() || it.status in query.statuses }
            .filter { query.kinds.isEmpty() || it.kind in query.kinds }
            .filter { query.includeExpired || it.expiresAt == null || it.expiresAt.isAfter(instant) }
            .filter {
                needle.isEmpty() || it.content.lowercase().contains(needle) ||
                    it.tags.any { tag -> tag.lowercase().contains(needle) } ||
                    it.provenance.sourceId?.lowercase()?.contains(needle) == true
            }
            .sortedWith(compareByDescending<CoreMemoryRecord> { it.updatedAt }.thenBy { it.id.value })
            .take(query.limit)
            .toList()
    }

    override suspend fun update(
        id: MemoryIdentifier,
        change: MemoryChange,
        access: MemoryAccessContext
    ): MemoryWriteResult {
        var result: MemoryWriteResult = MemoryWriteResult.Rejected("MEMORY_NOT_FOUND")
        store.update({ emptyList() }) { records ->
            val index = records.indexOfFirst { it.id == id }
            if (index < 0) return@update records
            val current = records[index]
            authorizationFailure(current, access)?.let {
                result = MemoryWriteResult.Rejected(it)
                return@update records
            }
            val changed = runCatching {
                current.copy(
                    content = change.content ?: current.content,
                    status = change.status ?: current.status,
                    classification = change.classification ?: current.classification,
                    expiresAt = change.expiresAt ?: current.expiresAt,
                    tags = change.tags ?: current.tags,
                    updatedAt = now(),
                    lastModifiedBy = access.actorId
                )
            }.getOrElse {
                result = MemoryWriteResult.Rejected("INVALID_MEMORY_CHANGE")
                return@update records
            }
            authorizationFailure(changed, access)?.let {
                result = MemoryWriteResult.Rejected(it)
                return@update records
            }
            val updated = records.toMutableList().apply { this[index] = changed }
            result = MemoryWriteResult.Stored(id)
            updated
        }
        return result
    }

    override suspend fun delete(
        id: MemoryIdentifier,
        access: MemoryAccessContext
    ): MemoryDeleteResult {
        var result: MemoryDeleteResult = MemoryDeleteResult.NotFound
        store.update({ emptyList() }) { records ->
            val index = records.indexOfFirst { it.id == id }
            if (index < 0) return@update records
            val current = records[index]
            authorizationFailure(current, access)?.let {
                result = MemoryDeleteResult.Rejected(it)
                return@update records
            }
            val tombstone = current.copy(
                content = DELETED_CONTENT,
                status = MemoryStatus.DELETED,
                updatedAt = now(),
                lastModifiedBy = access.actorId,
                tags = emptySet()
            )
            result = MemoryDeleteResult.Deleted
            records.toMutableList().apply { this[index] = tombstone }
        }
        return result
    }

    override suspend fun export(
        query: MemoryQuery,
        access: MemoryAccessContext
    ): MemoryExport = MemoryExport(EXPORT_SCHEMA_VERSION, search(query, access))

    private fun authorizationFailure(record: CoreMemoryRecord, access: MemoryAccessContext): String? {
        if (record.scopeType !in access.allowedScopes) return "MEMORY_SCOPE_NOT_ALLOWED"
        if (record.classification.ordinal > access.maximumClassification.ordinal)
            return "MEMORY_CLASSIFICATION_NOT_ALLOWED"
        return when (record.scopeType) {
            MemoryScopeType.PROJECT -> if (access.projectId?.value == record.scopeId) null else "MEMORY_PROJECT_MISMATCH"
            MemoryScopeType.OWNER -> if (access.actorId == record.scopeId) null else "MEMORY_OWNER_MISMATCH"
            else -> "MEMORY_SCOPE_NOT_IMPLEMENTED"
        }
    }

    private fun provenanceFailure(record: CoreMemoryRecord): String? =
        if (record.kind == MemoryKind.FACT && record.provenance.sourceId.isNullOrBlank())
            "MEMORY_FACT_SOURCE_REQUIRED"
        else null

    private fun encode(records: List<CoreMemoryRecord>): String = JSONArray().apply {
        records.sortedBy { it.id.value }.forEach { record -> put(encodeRecord(record)) }
    }.toString()

    private fun encodeRecord(record: CoreMemoryRecord): JSONObject {
        val isProtected = record.classification >= CoreDataClassification.CONFIDENTIAL
        val contentBytes = record.content.toByteArray(Charsets.UTF_8)
        val storedContent = try {
            if (isProtected) payloadProtector.protect(record.id, contentBytes) else record.content
        } finally {
            contentBytes.fill(0)
        }
        return JSONObject().apply {
            put("id", record.id.value)
            put("schemaVersion", record.schemaVersion)
            put("scopeType", record.scopeType.name)
            put("scopeId", record.scopeId)
            put("kind", record.kind.name)
            put("contentEncoding", if (isProtected) "aes-gcm-v1" else "plain-v1")
            put("content", storedContent)
            put("classification", record.classification.name)
            put("status", record.status.name)
            put("confidence", record.confidence)
            put("confidenceBasis", record.confidenceBasis.name)
            put("sourceType", record.provenance.sourceType)
            put("sourceId", record.provenance.sourceId ?: JSONObject.NULL)
            put("provenanceActorId", record.provenance.actorId)
            put("occurredAt", record.provenance.occurredAt.toEpochMilli())
            put("createdAt", record.createdAt.toEpochMilli())
            put("updatedAt", record.updatedAt.toEpochMilli())
            put("validFrom", record.validFrom?.toEpochMilli() ?: JSONObject.NULL)
            put("expiresAt", record.expiresAt?.toEpochMilli() ?: JSONObject.NULL)
            put("createdBy", record.createdBy)
            put("lastModifiedBy", record.lastModifiedBy)
            put("supersedes", record.supersedes?.value ?: JSONObject.NULL)
            put("tags", JSONArray(record.tags.sorted()))
        }
    }

    private fun decode(raw: String): List<CoreMemoryRecord> {
        val array = JSONArray(raw)
        return List(array.length()) { index -> decodeRecord(array.getJSONObject(index)) }
    }

    private fun decodeRecord(json: JSONObject): CoreMemoryRecord {
        val id = MemoryIdentifier(json.getString("id"))
        val encoding = json.getString("contentEncoding")
        val storedContent = json.getString("content")
        val content = when (encoding) {
            "plain-v1" -> storedContent
            "aes-gcm-v1" -> payloadProtector.unprotect(id, storedContent).let { bytes ->
                try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
            }
            else -> error("Unsupported memory content encoding")
        }
        return CoreMemoryRecord(
            id = id,
            schemaVersion = json.getInt("schemaVersion"),
            scopeType = MemoryScopeType.valueOf(json.getString("scopeType")),
            scopeId = json.getString("scopeId"),
            kind = MemoryKind.valueOf(json.getString("kind")),
            content = content,
            classification = CoreDataClassification.valueOf(json.getString("classification")),
            status = MemoryStatus.valueOf(json.getString("status")),
            confidence = json.getDouble("confidence"),
            confidenceBasis = MemoryConfidenceBasis.valueOf(json.getString("confidenceBasis")),
            provenance = MemoryProvenance(
                json.getString("sourceType"),
                json.nullableString("sourceId"),
                json.getString("provenanceActorId"),
                Instant.ofEpochMilli(json.getLong("occurredAt"))
            ),
            createdAt = Instant.ofEpochMilli(json.getLong("createdAt")),
            updatedAt = Instant.ofEpochMilli(json.getLong("updatedAt")),
            validFrom = json.nullableInstant("validFrom"),
            expiresAt = json.nullableInstant("expiresAt"),
            createdBy = json.getString("createdBy"),
            lastModifiedBy = json.getString("lastModifiedBy"),
            supersedes = json.nullableString("supersedes")?.let(::MemoryIdentifier),
            tags = json.getJSONArray("tags").let { tags ->
                buildSet { repeat(tags.length()) { add(tags.getString(it)) } }
            }
        )
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)
    private fun JSONObject.nullableInstant(name: String): Instant? =
        if (isNull(name)) null else Instant.ofEpochMilli(getLong(name))

    private companion object {
        const val FILE_NAME = "core_operational_memory_v1.json"
        const val EXPORT_SCHEMA_VERSION = 1
        const val DELETED_CONTENT = "[deleted]"
    }
}
