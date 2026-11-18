package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * SQLite-backed governed memory. Content, provenance and tags are encrypted as one
 * payload; searchable metadata is limited to scope/type/status/classification/times.
 */
class GlafiraSqliteMemoryRepository(
    private val database: GlafiraCoreDatabase,
    private val crypto: GlafiraMemoryCrypto,
    private val policyEngine: CorePolicyEngine,
    private val traceService: GlafiraTraceService
) : MemoryRepository {

    override suspend fun put(record: CoreMemoryRecord, access: MemoryAccessContext): MemoryWriteResult =
        withContext(Dispatchers.IO) {
            if (!record.scopeId.matches(SAFE_SCOPE_ID) ||
                GlafiraSecretRedactor.redact(record.scopeId) != record.scopeId ||
                !record.id.value.matches(SAFE_SCOPE_ID) ||
                GlafiraSecretRedactor.redact(record.id.value) != record.id.value ||
                record.content.length > MAX_CONTENT_CHARS ||
                record.tags.size > MAX_TAGS ||
                record.tags.any { it.isBlank() || it.length > MAX_TAG_LENGTH } ||
                record.status == MemoryStatus.DELETED) {
                return@withContext MemoryWriteResult.Rejected("MEMORY_METADATA_INVALID")
            }
            if (!canAccessScope(record.scopeType, record.scopeId, access)) {
                return@withContext MemoryWriteResult.Rejected("MEMORY_SCOPE_DENIED")
            }
            if (record.createdBy != access.actorId ||
                record.lastModifiedBy != access.actorId ||
                record.provenance.actorId != access.actorId) {
                return@withContext MemoryWriteResult.Rejected("MEMORY_PROVENANCE_ACTOR_MISMATCH")
            }
            if (record.classification.ordinal > access.maximumClassification.ordinal) {
                return@withContext MemoryWriteResult.Rejected("MEMORY_CLASSIFICATION_DENIED")
            }
            if (record.confidenceBasis == MemoryConfidenceBasis.OWNER_CONFIRMED &&
                access.actorType != CoreActorType.OWNER) {
                return@withContext MemoryWriteResult.Rejected("OWNER_CONFIRMATION_REQUIRED")
            }
            if (record.status == MemoryStatus.CONFIRMED &&
                (access.actorType != CoreActorType.OWNER ||
                    record.confidenceBasis != MemoryConfidenceBasis.OWNER_CONFIRMED)) {
                return@withContext MemoryWriteResult.Rejected("OWNER_CONFIRMATION_REQUIRED")
            }
            val decision = policyEngine.evaluate(
                policyRequest(access, CoreAction.WRITE_MEMORY, record.classification, record.id.value)
            )
            if (!decision.allowed || decision.requiresOwnerApproval) {
                return@withContext MemoryWriteResult.Rejected("POLICY_WRITE_DENIED")
            }

            val payload = encryptPayload(record)
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                val values = recordValues(record, payload)
                db.insertOrThrow(TABLE, null, values)
                traceService.recordInTransaction(
                    db,
                    memoryEvent(record, access, if (record.status == MemoryStatus.CONFIRMED)
                        CoreTraceEventType.MEMORY_CONFIRMED else CoreTraceEventType.MEMORY_PROPOSED)
                )
                db.setTransactionSuccessful()
                MemoryWriteResult.Stored(record.id)
            } catch (_: android.database.SQLException) {
                MemoryWriteResult.Rejected("MEMORY_STORAGE_CONFLICT")
            } finally {
                db.endTransaction()
            }
        }

    override suspend fun get(id: MemoryIdentifier, access: MemoryAccessContext): CoreMemoryRecord? =
        withContext(Dispatchers.IO) {
            val db = database.readableDatabase
            db.query(TABLE, null, "id = ?", arrayOf(id.value), null, null, null, "1").use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val metadata = cursor.toMetadata()
                if (!canAccessScope(metadata.scopeType, metadata.scopeId, access) ||
                    metadata.classification.ordinal > access.maximumClassification.ordinal ||
                    metadata.status == MemoryStatus.DELETED) return@withContext null
                val decision = policyEngine.evaluate(
                    policyRequest(access, CoreAction.READ_MEMORY, metadata.classification, id.value)
                )
                if (!decision.allowed || decision.requiresOwnerApproval) return@withContext null
                decodeRecord(metadata, cursor.getBlob(cursor.getColumnIndexOrThrow("payload")))
            }
        }

    override suspend fun search(query: MemoryQuery, access: MemoryAccessContext): List<CoreMemoryRecord> =
        withContext(Dispatchers.IO) {
            val scopes = query.scopes.ifEmpty { access.allowedScopes } intersect access.allowedScopes
            if (scopes.isEmpty()) return@withContext emptyList()
            val scopeNames = scopes.map { it.name }
            val scopePlaceholders = scopeNames.joinToString(",") { "?" }
            val statusValues = query.statuses.filter { it != MemoryStatus.DELETED }
            if (query.statuses.isNotEmpty() && statusValues.isEmpty()) return@withContext emptyList()
            val statusClause = if (statusValues.isEmpty()) "status != ?" else
                "status IN (${statusValues.joinToString(",") { "?" }})"
            val args = mutableListOf<String>().apply {
                addAll(scopeNames)
                if (statusValues.isEmpty()) add(MemoryStatus.DELETED.name) else addAll(statusValues.map { it.name })
            }
            val db = database.readableDatabase
            val records = mutableListOf<CoreMemoryRecord>()
            db.query(
                TABLE, null,
                "scope_type IN ($scopePlaceholders) AND $statusClause",
                args.toTypedArray(), null, null, "updated_at DESC", "500"
            ).use { cursor ->
                while (cursor.moveToNext() && records.size < query.limit) {
                    val metadata = cursor.toMetadata()
                    if (!canAccessScope(metadata.scopeType, metadata.scopeId, access)) continue
                    if (metadata.classification.ordinal > access.maximumClassification.ordinal) continue
                    if (!query.includeExpired && metadata.expiresAt?.isBefore(Instant.now()) == true) continue
                    if (query.kinds.isNotEmpty() && metadata.kind !in query.kinds) continue
                    val decision = policyEngine.evaluate(
                        policyRequest(access, CoreAction.READ_MEMORY, metadata.classification, metadata.id.value)
                    )
                    if (!decision.allowed || decision.requiresOwnerApproval) continue
                    val blobIndex = cursor.getColumnIndexOrThrow("payload")
                    if (cursor.isNull(blobIndex)) continue
                    val record = decodeRecord(metadata, cursor.getBlob(blobIndex))
                    val needle = query.text.trim()
                    if (needle.isNotEmpty() &&
                        !record.content.contains(needle, ignoreCase = true) &&
                        record.tags.none { it.contains(needle, ignoreCase = true) }) continue
                    records += record
                }
            }
            records
        }

    override suspend fun update(
        id: MemoryIdentifier,
        change: MemoryChange,
        access: MemoryAccessContext
    ): MemoryWriteResult = withContext(Dispatchers.IO) {
        val current = get(id, access)
            ?: return@withContext MemoryWriteResult.Rejected("MEMORY_NOT_FOUND_OR_DENIED")
        val updatedClassification = change.classification ?: current.classification
        if (updatedClassification.ordinal > access.maximumClassification.ordinal) {
            return@withContext MemoryWriteResult.Rejected("MEMORY_CLASSIFICATION_DENIED")
        }
        if ((change.content?.length ?: 0) > MAX_CONTENT_CHARS ||
            (change.tags?.size ?: 0) > MAX_TAGS ||
            change.tags?.any { it.isBlank() || it.length > MAX_TAG_LENGTH } == true) {
            return@withContext MemoryWriteResult.Rejected("MEMORY_CHANGE_TOO_LARGE")
        }
        val updatedStatus = change.status ?: current.status
        if (updatedStatus == MemoryStatus.DELETED) {
            return@withContext MemoryWriteResult.Rejected("USE_DELETE_OPERATION")
        }
        if (updatedStatus == MemoryStatus.CONFIRMED && access.actorType != CoreActorType.OWNER) {
            return@withContext MemoryWriteResult.Rejected("OWNER_CONFIRMATION_REQUIRED")
        }
        val decision = policyEngine.evaluate(
            policyRequest(access, CoreAction.WRITE_MEMORY, updatedClassification, id.value)
        )
        if (!decision.allowed || decision.requiresOwnerApproval) {
            return@withContext MemoryWriteResult.Rejected("POLICY_WRITE_DENIED")
        }
        val updated = current.copy(
            content = change.content ?: current.content,
            classification = updatedClassification,
            status = updatedStatus,
            confidenceBasis = if (updatedStatus == MemoryStatus.CONFIRMED)
                MemoryConfidenceBasis.OWNER_CONFIRMED else current.confidenceBasis,
            updatedAt = Instant.now(),
            lastModifiedBy = access.actorId,
            expiresAt = change.expiresAt ?: current.expiresAt,
            tags = change.tags ?: current.tags
        )
        if (!canAccessScope(updated.scopeType, updated.scopeId, access)) {
            return@withContext MemoryWriteResult.Rejected("MEMORY_SCOPE_DENIED")
        }

        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val changed = db.update(
                TABLE,
                recordValues(updated, encryptPayload(updated)),
                "id = ? AND status != ? AND updated_at = ?",
                arrayOf(id.value, MemoryStatus.DELETED.name, current.updatedAt.toString())
            )
            if (changed != 1) return@withContext MemoryWriteResult.Rejected("MEMORY_NOT_FOUND")
            traceService.recordInTransaction(
                db,
                memoryEvent(updated, access, if (updated.status == MemoryStatus.CONFIRMED)
                    CoreTraceEventType.MEMORY_CONFIRMED else CoreTraceEventType.MEMORY_UPDATED)
            )
            db.setTransactionSuccessful()
            MemoryWriteResult.Stored(id)
        } catch (_: Exception) {
            MemoryWriteResult.Rejected("MEMORY_UPDATE_FAILED")
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun delete(id: MemoryIdentifier, access: MemoryAccessContext): MemoryDeleteResult =
        withContext(Dispatchers.IO) {
            val current = get(id, access) ?: return@withContext MemoryDeleteResult.NotFound
            val decision = policyEngine.evaluate(
                policyRequest(access, CoreAction.DELETE_MEMORY, current.classification, id.value)
            )
            if (!decision.allowed || decision.requiresOwnerApproval) {
                return@withContext MemoryDeleteResult.Rejected("POLICY_DELETE_DENIED")
            }
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("status", MemoryStatus.DELETED.name)
                    putNull("payload")
                    put("updated_at", Instant.now().toString())
                }
                val changed = db.update(
                    TABLE, values,
                    "id = ? AND status != ? AND updated_at = ?",
                    arrayOf(id.value, MemoryStatus.DELETED.name, current.updatedAt.toString())
                )
                if (changed != 1) return@withContext MemoryDeleteResult.Rejected("MEMORY_CONCURRENT_MODIFICATION")
                traceService.recordInTransaction(
                    db,
                    memoryEvent(current, access, CoreTraceEventType.MEMORY_DELETED)
                )
                db.setTransactionSuccessful()
                MemoryDeleteResult.Deleted
            } catch (_: Exception) {
                MemoryDeleteResult.Rejected("MEMORY_DELETE_FAILED")
            } finally {
                db.endTransaction()
            }
        }

    override suspend fun export(query: MemoryQuery, access: MemoryAccessContext): MemoryExport =
        MemoryExport(schemaVersion = 1, records = search(query, access))

    private fun canAccessScope(scope: MemoryScopeType, scopeId: String, access: MemoryAccessContext): Boolean {
        if (scope !in access.allowedScopes) return false
        return when (scope) {
            MemoryScopeType.OWNER -> access.actorType == CoreActorType.OWNER && scopeId == access.actorId
            MemoryScopeType.PROJECT -> scopeId == access.projectId?.value
            MemoryScopeType.AGENT -> when (access.actorType) {
                CoreActorType.OWNER, CoreActorType.SYSTEM -> true
                else -> scopeId == access.agentRoleId?.value
            }
            MemoryScopeType.CONVERSATION, MemoryScopeType.WORKING ->
                access.projectId != null &&
                    (scopeId == access.projectId.value || scopeId.startsWith(access.projectId.value + ":"))
        }
    }

    private fun policyRequest(
        access: MemoryAccessContext,
        action: CoreAction,
        classification: CoreDataClassification,
        resourceId: String?
    ) = CorePolicyRequest(
        actor = CoreActor(access.actorId, access.actorType),
        projectId = requireNotNull(access.projectId) { "A project-scoped Core context is required" },
        action = action,
        classification = classification,
        resourceId = resourceId
    )

    private fun encryptPayload(record: CoreMemoryRecord): ByteArray =
        crypto.encrypt(
            memoryId = record.id,
            schemaVersion = record.schemaVersion,
            scopeType = record.scopeType,
            scopeId = record.scopeId,
            kind = record.kind,
            classification = record.classification,
            status = record.status,
            confidence = record.confidence,
            confidenceBasis = record.confidenceBasis,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            validFrom = record.validFrom,
            expiresAt = record.expiresAt,
            plaintext = payloadJson(record).toByteArray(Charsets.UTF_8)
        )

    private fun payloadJson(record: CoreMemoryRecord): String = JSONObject().apply {
        put("content", record.content)
        put("sourceType", record.provenance.sourceType)
        put("sourceId", record.provenance.sourceId)
        put("provenanceActorId", record.provenance.actorId)
        put("provenanceOccurredAt", record.provenance.occurredAt.toString())
        put("createdBy", record.createdBy)
        put("lastModifiedBy", record.lastModifiedBy)
        put("supersedes", record.supersedes?.value)
        put("tags", JSONArray().apply { record.tags.sorted().forEach { tag -> put(tag) } })
    }.toString()

    private data class MemoryMetadata(
        val id: MemoryIdentifier,
        val schemaVersion: Int,
        val scopeType: MemoryScopeType,
        val scopeId: String,
        val kind: MemoryKind,
        val classification: CoreDataClassification,
        val status: MemoryStatus,
        val confidence: Double,
        val confidenceBasis: MemoryConfidenceBasis,
        val createdAt: Instant,
        val updatedAt: Instant,
        val validFrom: Instant?,
        val expiresAt: Instant?
    )

    private fun Cursor.toMetadata() = MemoryMetadata(
        id = MemoryIdentifier(getString(getColumnIndexOrThrow("id"))),
        schemaVersion = getInt(getColumnIndexOrThrow("schema_version")),
        scopeType = MemoryScopeType.valueOf(getString(getColumnIndexOrThrow("scope_type"))),
        scopeId = getString(getColumnIndexOrThrow("scope_id")),
        kind = MemoryKind.valueOf(getString(getColumnIndexOrThrow("kind"))),
        classification = CoreDataClassification.valueOf(getString(getColumnIndexOrThrow("classification"))),
        status = MemoryStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        confidence = getDouble(getColumnIndexOrThrow("confidence")),
        confidenceBasis = MemoryConfidenceBasis.valueOf(getString(getColumnIndexOrThrow("confidence_basis"))),
        createdAt = Instant.parse(getString(getColumnIndexOrThrow("created_at"))),
        updatedAt = Instant.parse(getString(getColumnIndexOrThrow("updated_at"))),
        validFrom = getStringOrNull("valid_from")?.let(Instant::parse),
        expiresAt = getStringOrNull("expires_at")?.let(Instant::parse)
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun decodeRecord(metadata: MemoryMetadata, encrypted: ByteArray): CoreMemoryRecord {
        val payload = JSONObject(
            String(
                crypto.decrypt(
                    memoryId = metadata.id,
                    schemaVersion = metadata.schemaVersion,
                    scopeType = metadata.scopeType,
                    scopeId = metadata.scopeId,
                    kind = metadata.kind,
                    classification = metadata.classification,
                    status = metadata.status,
                    confidence = metadata.confidence,
                    confidenceBasis = metadata.confidenceBasis,
                    createdAt = metadata.createdAt,
                    updatedAt = metadata.updatedAt,
                    validFrom = metadata.validFrom,
                    expiresAt = metadata.expiresAt,
                    payload = encrypted
                ),
                Charsets.UTF_8
            )
        )
        val tagsJson = payload.optJSONArray("tags") ?: JSONArray()
        val tags = buildSet {
            for (index in 0 until tagsJson.length()) add(tagsJson.getString(index))
        }
        val supersedes = payload.optString("supersedes").takeIf { it.isNotBlank() && it != "null" }
        return CoreMemoryRecord(
            id = metadata.id,
            schemaVersion = metadata.schemaVersion,
            scopeType = metadata.scopeType,
            scopeId = metadata.scopeId,
            kind = metadata.kind,
            content = payload.getString("content"),
            classification = metadata.classification,
            status = metadata.status,
            confidence = metadata.confidence,
            confidenceBasis = metadata.confidenceBasis,
            provenance = MemoryProvenance(
                sourceType = payload.getString("sourceType"),
                sourceId = payload.optString("sourceId").takeIf { it.isNotBlank() && it != "null" },
                actorId = payload.getString("provenanceActorId"),
                occurredAt = Instant.parse(payload.getString("provenanceOccurredAt"))
            ),
            createdAt = metadata.createdAt,
            updatedAt = metadata.updatedAt,
            validFrom = metadata.validFrom,
            expiresAt = metadata.expiresAt,
            createdBy = payload.getString("createdBy"),
            lastModifiedBy = payload.getString("lastModifiedBy"),
            supersedes = supersedes?.let(::MemoryIdentifier),
            tags = tags
        )
    }

    private fun recordValues(record: CoreMemoryRecord, encryptedPayload: ByteArray) = ContentValues().apply {
        put("id", record.id.value)
        put("schema_version", record.schemaVersion)
        put("scope_type", record.scopeType.name)
        put("scope_id", record.scopeId)
        put("kind", record.kind.name)
        put("classification", record.classification.name)
        put("status", record.status.name)
        put("confidence", record.confidence)
        put("confidence_basis", record.confidenceBasis.name)
        put("created_at", record.createdAt.toString())
        put("updated_at", record.updatedAt.toString())
        if (record.validFrom == null) putNull("valid_from") else put("valid_from", record.validFrom.toString())
        if (record.expiresAt == null) putNull("expires_at") else put("expires_at", record.expiresAt.toString())
        put("payload", encryptedPayload)
    }

    private fun memoryEvent(
        record: CoreMemoryRecord,
        access: MemoryAccessContext,
        type: CoreTraceEventType
    ) = CoreTraceEvent(
        eventId = TraceEventIdentifier(java.util.UUID.randomUUID().toString()),
        occurredAt = Instant.now(),
        recordedAt = Instant.now(),
        eventType = type,
        severity = CoreTraceSeverity.INFO,
        actor = CoreActor(access.actorId, access.actorType),
        projectId = access.projectId,
        taskId = null,
        parentTaskId = null,
        correlationId = CoreIdentifier(record.id.value),
        causationId = null,
        component = "MemoryRepository",
        action = type.name.lowercase(),
        targetType = "MEMORY",
        targetId = record.id.value,
        resultCode = "OK",
        approvalRef = null,
        dataClassification = CoreDataClassification.INTERNAL,
        redactionApplied = true,
        safeAttributes = mapOf(
            "scopeType" to record.scopeType.name,
            "classification" to record.classification.name,
            "kind" to record.kind.name
        )
    )

    companion object {
        private const val TABLE = "memory_records"
        private const val MAX_CONTENT_CHARS = 100_000
        private const val MAX_TAGS = 32
        private const val MAX_TAG_LENGTH = 64
        private val SAFE_SCOPE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
    }
}
