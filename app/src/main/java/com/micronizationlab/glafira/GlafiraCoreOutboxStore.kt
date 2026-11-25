package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Local durable append only. Crypto, peer verification, transport and ACK are deliberately external. */
internal class GlafiraCoreOutboxStore(private val db: SQLiteDatabase, private val sourceCoreId: String) {
    init { require(sourceCoreId.matches(Regex("^[A-Za-z0-9._-]{8,128}$"))) }

    @Synchronized
    fun append(d: GlafiraCoreOutboxDraft): GlafiraCoreOutboxAppendResult {
        require(!db.inTransaction()) { "CORE_OUTBOX_APPEND_REQUIRES_TRANSACTION_BOUNDARY" }
        require(d.eventId.isNotBlank() && d.eventId.length <= 128)
        require(d.recipientCoreId.matches(Regex("^[A-Za-z0-9._-]{8,128}$")) && d.recipientCoreId != sourceCoreId)
        require(d.projectId.isNotBlank() && d.taskId.isNotBlank())
        require(d.eventType in EVENTS && d.sourceKeyEpoch > 0 && d.protocolVersion > 0)
        require(d.ownerGeneration > 0 && d.stateVersion >= 0)
        require(d.idempotencyKey.isNotBlank() && d.idempotencyKey.length <= 256)
        require(d.payloadSha256.matches(Regex("^[0-9a-f]{64}$")))
        require(d.payloadCiphertext.isNotBlank() && d.signatureBase64.isNotBlank() && d.createdAtMillis > 0)

        db.beginTransaction()
        try {
            val old = db.query(GlafiraCoreOutboxSchema.TABLE, arrayOf(
                "event_id", "recipient_core_id", "project_id", "task_id", "payload_sha256",
                "payload_ciphertext", "signature_base64", "source_key_epoch", "protocol_version",
                "correlation_id", "causation_id", "created_at", "source_sequence"
            ), "source_core_id=? AND idempotency_key=? AND event_type=? AND owner_generation=? AND state_version=?",
                arrayOf(sourceCoreId, d.idempotencyKey, d.eventType, d.ownerGeneration.toString(), d.stateVersion.toString()),
                null, null, null, "1").use { c ->
                if (!c.moveToFirst()) null else {
                    require(c.getString(0) == d.eventId && c.getString(1) == d.recipientCoreId &&
                        c.getString(2) == d.projectId && c.getString(3) == d.taskId &&
                        c.getString(4) == d.payloadSha256 && c.getString(5) == d.payloadCiphertext &&
                        c.getString(6) == d.signatureBase64 && c.getLong(7) == d.sourceKeyEpoch &&
                        c.getInt(8) == d.protocolVersion && c.getString(9) == d.correlationId &&
                        c.getString(10) == d.causationId && c.getLong(11) == d.createdAtMillis
                    ) { "CORE_OUTBOX_IDEMPOTENCY_CONFLICT" }
                    c.getLong(12)
                }
            }
            if (old != null) {
                db.setTransactionSuccessful()
                return result(d, old, false)
            }

            db.insertWithOnConflict("core_outbox_sequences", null, ContentValues().apply {
                put("source_core_id", sourceCoreId); put("last_sequence", 0L); put("updated_at", d.createdAtMillis)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val previous = db.rawQuery("SELECT last_sequence FROM core_outbox_sequences WHERE source_core_id=?",
                arrayOf(sourceCoreId)).use { c -> check(c.moveToFirst()); c.getLong(0) }
            val next = Math.addExact(previous, 1L)
            check(db.update("core_outbox_sequences", ContentValues().apply {
                put("last_sequence", next); put("updated_at", d.createdAtMillis)
            }, "source_core_id=? AND last_sequence=?", arrayOf(sourceCoreId, previous.toString())) == 1)

            db.insertOrThrow(GlafiraCoreOutboxSchema.TABLE, null, ContentValues().apply {
                put("event_id", d.eventId); put("source_core_id", sourceCoreId)
                put("recipient_core_id", d.recipientCoreId); put("project_id", d.projectId)
                put("source_sequence", next); put("correlation_id", d.correlationId); put("causation_id", d.causationId)
                put("source_key_epoch", d.sourceKeyEpoch); put("protocol_version", d.protocolVersion)
                put("event_type", d.eventType); put("task_id", d.taskId)
                put("owner_generation", d.ownerGeneration); put("state_version", d.stateVersion)
                put("idempotency_key", d.idempotencyKey); put("payload_sha256", d.payloadSha256)
                put("payload_ciphertext", d.payloadCiphertext); put("signature_base64", d.signatureBase64)
                put("created_at", d.createdAtMillis); put("delivery_state", "PENDING")
                put("attempt_count", 0); put("next_attempt_at", d.createdAtMillis)
            })
            db.setTransactionSuccessful()
            return result(d, next, true)
        } finally { db.endTransaction() }
    }

    private fun result(d: GlafiraCoreOutboxDraft, sequence: Long, inserted: Boolean) =
        GlafiraCoreOutboxAppendResult(d.eventId, sourceCoreId, d.recipientCoreId, d.projectId,
            sequence, d.taskId, d.payloadSha256, d.payloadCiphertext, d.signatureBase64, inserted)

    private companion object {
        val EVENTS = setOf("TASK_ASSIGNMENT", "TASK_RESULT", "TASK_CANCELLED", "OWNERSHIP_TRANSFER", "TASK_RECONCILIATION")
    }
}

internal data class GlafiraCoreOutboxDraft(
    val eventId: String, val recipientCoreId: String, val projectId: String, val taskId: String,
    val eventType: String, val sourceKeyEpoch: Long, val protocolVersion: Int,
    val ownerGeneration: Long, val stateVersion: Long, val idempotencyKey: String,
    val payloadSha256: String,
    /** Must already be encrypted by the separately reviewed envelope protocol. */
    val payloadCiphertext: String,
    /** Must already be signed by the enrolled source signing identity. */
    val signatureBase64: String,
    val createdAtMillis: Long, val correlationId: String? = null, val causationId: String? = null
)

internal data class GlafiraCoreOutboxAppendResult(
    val eventId: String, val sourceCoreId: String, val recipientCoreId: String, val projectId: String,
    val sourceSequence: Long, val taskId: String, val payloadSha256: String, val payloadCiphertext: String,
    val signatureBase64: String, val inserted: Boolean
)
