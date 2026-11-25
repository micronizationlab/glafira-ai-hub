package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Appends an already prepared Outbox envelope to the local Core database.
 *
 * This class deliberately does not encrypt payloads, sign events, verify peers,
 * transmit events, or acknowledge delivery. Those operations require the
 * separately reviewed peer-key and envelope protocol.
 */
internal class GlafiraCoreOutboxStore(
    private val db: SQLiteDatabase,
    private val sourceCoreId: String
) {
    init {
        require(sourceCoreId.matches(Regex("^[A-Za-z0-9._-]{8,128}$"))) {
            "CORE_OUTBOX_SOURCE_ID_INVALID"
        }
    }

    @Synchronized
    fun append(draft: GlafiraCoreOutboxDraft): GlafiraCoreOutboxAppendResult {
        require(!db.inTransaction()) { "CORE_OUTBOX_APPEND_REQUIRES_TRANSACTION_BOUNDARY" }
        validate(draft)

        db.beginTransaction()
        try {
            val existing = findByIdempotency(draft)
            if (existing != null) {
                require(existing.eventId == draft.eventId &&
                    existing.recipientCoreId == draft.recipientCoreId &&
                    existing.projectId == draft.projectId &&
                    existing.taskId == draft.taskId &&
                    existing.payloadSha256 == draft.payloadSha256 &&
                    existing.payloadCiphertext == draft.payloadCiphertext &&
                    existing.signatureBase64 == draft.signatureBase64
                ) { "CORE_OUTBOX_IDEMPOTENCY_CONFLICT" }
                db.setTransactionSuccessful()
                return existing.copy(inserted = false)
            }

            db.insertWithOnConflict(
                "core_outbox_sequences",
                null,
                ContentValues().apply {
                    put("source_core_id", sourceCoreId)
                    put("last_sequence", 0L)
                    put("updated_at", draft.createdAtMillis)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )

            val previous = db.rawQuery(
                "SELECT last_sequence FROM core_outbox_sequences WHERE source_core_id = ?",
                arrayOf(sourceCoreId)
            ).use { cursor ->
                check(cursor.moveToFirst()) { "CORE_OUTBOX_SEQUENCE_MISSING" }
                cursor.getLong(0)
            }
            val next = Math.addExact(previous, 1L)
            val updated = db.update(
                "core_outbox_sequences",
                ContentValues().apply {
                    put("last_sequence", next)
                    put("updated_at", draft.createdAtMillis)
                },
                "source_core_id = ? AND last_sequence = ?",
                arrayOf(sourceCoreId, previous.toString())
            )
            check(updated == 1) { "CORE_OUTBOX_SEQUENCE_RACE" }

            db.insertOrThrow(
                GlafiraCoreOutboxSchema.TABLE,
                null,
                ContentValues().apply {
                    put("event_id", draft.eventId)
                    put("source_core_id", sourceCoreId)
                    put("recipient_core_id", draft.recipientCoreId)
                    put("project_id", draft.projectId)
                    put("source_sequence", next)
                    put("correlation_id", draft.correlationId)
                    put("causation_id", draft.causationId)
                    put("source_key_epoch", draft.sourceKeyEpoch)
                    put("protocol_version", draft.protocolVersion)
                    put("event_type", draft.eventType)
                    put("task_id", draft.taskId)
                    put("owner_generation", draft.ownerGeneration)
                    put("state_version", draft.stateVersion)
                    put("idempotency_key", draft.idempotencyKey)
                    put("payload_sha256", draft.payloadSha256)
                    put("payload_ciphertext", draft.payloadCiphertext)
                    put("signature_base64", draft.signatureBase64)
                    put("created_at", draft.createdAtMillis)
                    put("delivery_state", "PENDING")
                    put("attempt_count", 0)
                    put("next_attempt_at", draft.createdAtMillis)
                }
            )

            db.setTransactionSuccessful()
            return GlafiraCoreOutboxAppendResult(
                eventId = draft.eventId,
                sourceCoreId = sourceCoreId,
                sourceSequence = next,
                inserted = true,
                payloadSha256 = draft.payloadSha256,
                payloadCiphertext = draft.payloadCiphertext,
                signatureBase64 = draft.signatureBase64,
                recipientCoreId = draft.recipientCoreId,
                projectId = draft.projectId,
                taskId = draft.taskId
            )
        } finally {
            db.endTransaction()
        }
    }

    private fun findByIdempotency(draft: GlafiraCoreOutboxDraft): GlafiraCoreOutboxAppendResult? =
        db.query(
            GlafiraCoreOutboxSchema.TABLE,
            arrayOf(
                "event_id", "source_core_id", "recipient_core_id", "project_id",
                "source_sequence", "task_id", "payload_sha256", "payload_ciphertext", "signature_base64",
                "source_key_epoch", "protocol_version", "correlation_id", "causation_id", "created_at"
            ),
            "source_core_id = ? AND idempotency_key = ? AND event_type = ? " +
                "AND owner_generation = ? AND state_version = ?",
            arrayOf(
                sourceCoreId,
                draft.idempotencyKey,
                draft.eventType,
                draft.ownerGeneration.toString(),
                draft.stateVersion.toString()
            ),
            null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            require(
                cursor.getLong(9) == draft.sourceKeyEpoch &&
                    cursor.getInt(10) == draft.protocolVersion &&
                    cursor.getString(11) == draft.correlationId &&
                    cursor.getString(12) == draft.causationId &&
                    cursor.getLong(13) == draft.createdAtMillis
            ) { "CORE_OUTBOX_IDEMPOTENCY_CONFLICT" }
            GlafiraCoreOutboxAppendResult(
                eventId = cursor.getString(0),
                sourceCoreId = cursor.getString(1),
                recipientCoreId = cursor.getString(2),
                projectId = cursor.getString(3),
                sourceSequence = cursor.getLong(4),
                taskId = cursor.getString(5),
                payloadSha256 = cursor.getString(6),
                payloadCiphertext = cursor.getString(7),
                signatureBase64 = cursor.getString(8),
                inserted = false
            )
        }

    private fun validate(draft: GlafiraCoreOutboxDraft) {
        require(draft.eventId.isNotBlank() && draft.eventId.length <= 128)
        require(draft.recipientCoreId.matches(Regex("^[A-Za-z0-9._-]{8,128}$")))
        require(draft.recipientCoreId != sourceCoreId)
        require(draft.projectId.isNotBlank() && draft.projectId.length <= 256)
        require(draft.taskId.isNotBlank() && draft.taskId.length <= 256)
        require(draft.eventType in ALLOWED_EVENT_TYPES)
        require(draft.sourceKeyEpoch > 0L && draft.protocolVersion > 0)
        require(draft.ownerGeneration > 0L && draft.stateVersion >= 0L)
        require(draft.idempotencyKey.isNotBlank() && draft.idempotencyKey.length <= 256)
        require(draft.payloadSha256.matches(Regex("^[0-9a-f]{64}$")))
        require(draft.payloadCiphertext.isNotBlank())
        require(draft.signatureBase64.isNotBlank())
        require(draft.createdAtMillis > 0L)
    }

    private companion object {
        val ALLOWED_EVENT_TYPES = setOf(
            "TASK_ASSIGNMENT", "TASK_RESULT", "TASK_CANCELLED",
            "OWNERSHIP_TRANSFER", "TASK_RECONCILIATION"
        )
    }
}

internal data class GlafiraCoreOutboxDraft(
    val eventId: String,
    val recipientCoreId: String,
    val projectId: String,
    val taskId: String,
    val eventType: String,
    val sourceKeyEpoch: Long,
    val protocolVersion: Int,
    val ownerGeneration: Long,
    val stateVersion: Long,
    val idempotencyKey: String,
    val payloadSha256: String,
    /** Must already be encrypted by the separately reviewed envelope protocol. */
    val payloadCiphertext: String,
    /** Must already be signed by the enrolled source signing identity. */
    val signatureBase64: String,
    val createdAtMillis: Long,
    val correlationId: String? = null,
    val causationId: String? = null
)

internal data class GlafiraCoreOutboxAppendResult(
    val eventId: String,
    val sourceCoreId: String,
    val recipientCoreId: String,
    val projectId: String,
    val sourceSequence: Long,
    val taskId: String,
    val payloadSha256: String,
    val payloadCiphertext: String,
    val signatureBase64: String = "",
    val inserted: Boolean
)
