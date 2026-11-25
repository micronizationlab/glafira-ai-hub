package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

data class GlafiraSignedOwnershipTransferEnvelope(
    val payload: GlafiraOwnershipTransferSigningPayload,
    val signatureBase64: String
)

enum class GlafiraOwnershipTransferApplyResult {
    APPLIED,
    ALREADY_APPLIED,
    TRANSFER_ID_CONFLICT,
    NONCE_REPLAY,
    INVALID_ENVELOPE,
    INVALID_SIGNATURE,
    UNTRUSTED_PEER,
    WRONG_RECIPIENT,
    EXPIRED,
    TASK_NOT_FOUND,
    TASK_NOT_QUIESCENT,
    TASK_CONTENT_MISMATCH,
    STALE_OWNERSHIP,
    OWNER_AUTHENTICATION_REQUIRED
}

data class GlafiraCoreOwnershipTransferReceipt(
    val transferId: String,
    val nonce: String,
    val taskId: String,
    val projectId: String,
    val oldOwnerCoreId: String,
    val newOwnerCoreId: String,
    val oldOwnerGeneration: Long,
    val newOwnerGeneration: Long,
    val stateVersion: Long,
    val envelopeSha256: String,
    val signingKeyEpoch: Long,
    val signingPublicKeySha256: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val appliedAtMillis: Long
)

/**
 * Applies an incoming signed ownership transfer to this Core's local task replica.
 *
 * The peer trust lock is held until the SQLite transaction commits, preventing a local revoke
 * or key-epoch rotation from racing acceptance. The transaction changes the owner generation,
 * invalidates old grants, stores a replay receipt and appends the redacted Trace event atomically.
 */
class GlafiraCoreOwnershipTransferStore(
    private val taskStore: GlafiraTaskStore,
    private val trustedPeerStore: GlafiraCoreTrustedPeerStore,
    private val localCoreId: String,
    private val traceStore: GlafiraCoreTraceStore = GlafiraCoreTraceStore(taskStore),
    private val ownerApprovalStore: GlafiraCoreOwnerApprovalStore = GlafiraCoreOwnerApprovalStore(taskStore)
) {
    init {
        require(localCoreId.matches(Regex("^[A-Za-z0-9._-]{8,128}$")))
        require(trustedPeerStore.localCoreId == localCoreId) { "OWNERSHIP_TRANSFER_LOCAL_IDENTITY_MISMATCH" }
        // Receipts are local anti-replay evidence and intentionally stay outside portable snapshots.
        taskStore.writableDatabase.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_RECEIPTS (
                transfer_id TEXT PRIMARY KEY NOT NULL,
                nonce TEXT NOT NULL UNIQUE,
                task_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                old_owner_core_id TEXT NOT NULL,
                new_owner_core_id TEXT NOT NULL,
                old_owner_generation INTEGER NOT NULL CHECK(old_owner_generation > 0),
                new_owner_generation INTEGER NOT NULL CHECK(new_owner_generation > old_owner_generation),
                state_version INTEGER NOT NULL CHECK(state_version >= 0),
                envelope_sha256 TEXT NOT NULL CHECK(length(envelope_sha256) = 64),
                signing_key_epoch INTEGER NOT NULL CHECK(signing_key_epoch > 0),
                signing_public_key_sha256 TEXT NOT NULL CHECK(length(signing_public_key_sha256) = 64),
                issued_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                applied_at INTEGER NOT NULL
            )"""
        )
        taskStore.writableDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS core_ownership_transfer_receipt_task_idx ON $TABLE_RECEIPTS(task_id, applied_at)"
        )
    }

    @Synchronized
    fun applyIncoming(
        envelope: GlafiraSignedOwnershipTransferEnvelope,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraOwnershipTransferApplyResult {
        if (nowMillis <= 0L) return GlafiraOwnershipTransferApplyResult.INVALID_ENVELOPE
        val canonicalPayload = runCatching {
            GlafiraCoreOwnershipTransferCryptography.canonicalBytes(envelope.payload)
        }.getOrNull() ?: return GlafiraOwnershipTransferApplyResult.INVALID_ENVELOPE
        val signature = decodeCanonicalSignature(envelope.signatureBase64)
            ?: return GlafiraOwnershipTransferApplyResult.INVALID_SIGNATURE
        val envelopeSha256 = envelopeSha256(canonicalPayload, signature)

        // A duplicate of an already committed transfer must still receive an idempotent ACK
        // after its original validity window or peer key has expired/revoked.
        synchronized(taskStore) {
            val existing = findReceipt(taskStore.writableDatabase, envelope.payload.transferId)
            if (existing != null) {
                return if (existing.envelopeSha256 == envelopeSha256) {
                    GlafiraOwnershipTransferApplyResult.ALREADY_APPLIED
                } else {
                    GlafiraOwnershipTransferApplyResult.TRANSFER_ID_CONFLICT
                }
            }
        }

        val payload = envelope.payload
        if (payload.newOwnerCoreId != localCoreId) {
            return GlafiraOwnershipTransferApplyResult.WRONG_RECIPIENT
        }
        val validityWindow = validityWindow(payload, nowMillis)
            ?: return GlafiraOwnershipTransferApplyResult.EXPIRED

        return trustedPeerStore.withActivePeer(payload.oldOwnerCoreId, payload.signingKeyEpoch) { peer ->
            if (peer.fingerprintSha256 != payload.signingPublicKeySha256 ||
                peer.coreId != payload.oldOwnerCoreId ||
                peer.keyEpoch != payload.signingKeyEpoch
            ) {
                return@withActivePeer GlafiraOwnershipTransferApplyResult.UNTRUSTED_PEER
            }
            val publicKey = decodePeerPublicKey(peer.publicKeySpkiBase64)
                ?: return@withActivePeer GlafiraOwnershipTransferApplyResult.INVALID_SIGNATURE
            if (!GlafiraCoreOwnershipTransferCryptography.verify(payload, publicKey, signature)) {
                return@withActivePeer GlafiraOwnershipTransferApplyResult.INVALID_SIGNATURE
            }
            applyVerified(payload, envelopeSha256, validityWindow.first, validityWindow.second, ownerSession, nowMillis)
        } ?: GlafiraOwnershipTransferApplyResult.UNTRUSTED_PEER
    }

    fun receipt(transferId: String): GlafiraCoreOwnershipTransferReceipt? {
        if (!GlafiraCoreTraceEvent.isSafeIdentifier(transferId)) return null
        synchronized(taskStore) {
            return findReceipt(taskStore.writableDatabase, transferId)
        }
    }

    private fun applyVerified(
        payload: GlafiraOwnershipTransferSigningPayload,
        envelopeSha256: String,
        issuedAtMillis: Long,
        expiresAtMillis: Long,
        ownerSession: GlafiraCoreOwnerSession,
        nowMillis: Long
    ): GlafiraOwnershipTransferApplyResult = synchronized(taskStore) {
        val db = taskStore.writableDatabase
        var result = GlafiraOwnershipTransferApplyResult.STALE_OWNERSHIP
        db.beginTransaction()
        try {
            val existing = findReceipt(db, payload.transferId)
            if (existing != null) {
                result = if (existing.envelopeSha256 == envelopeSha256) {
                    GlafiraOwnershipTransferApplyResult.ALREADY_APPLIED
                } else {
                    GlafiraOwnershipTransferApplyResult.TRANSFER_ID_CONFLICT
                }
                db.setTransactionSuccessful()
                return@synchronized result
            }

            if (nonceExists(db, payload.nonce)) {
                result = GlafiraOwnershipTransferApplyResult.NONCE_REPLAY
                db.setTransactionSuccessful()
                return@synchronized result
            }

            val task = taskStore.getTask(payload.taskId, payload.projectId)
            val ownership = taskStore.taskOwnership(payload.taskId, payload.projectId)
            if (task == null || ownership == null) {
                result = GlafiraOwnershipTransferApplyResult.TASK_NOT_FOUND
                db.setTransactionSuccessful()
                return@synchronized result
            }
            if (task.state != GlafiraTaskState.QUEUED || hasTaskResult(db, payload.taskId)) {
                result = GlafiraOwnershipTransferApplyResult.TASK_NOT_QUIESCENT
                db.setTransactionSuccessful()
                return@synchronized result
            }
            if (task.idempotencyKey != payload.idempotencyKey ||
                sha256(task.payload) != payload.payloadSha256 ||
                ownership.payloadSha256 != payload.payloadSha256 ||
                ownership.projectId != payload.projectId ||
                ownership.idempotencyKey != payload.idempotencyKey ||
                ownership.effectRisk != payload.effectRisk ||
                ownership.approvalRef != payload.approvalRef
            ) {
                result = GlafiraOwnershipTransferApplyResult.TASK_CONTENT_MISMATCH
                db.setTransactionSuccessful()
                return@synchronized result
            }
            if (ownership.ownerCoreId != payload.oldOwnerCoreId ||
                ownership.ownerGeneration != payload.oldOwnerGeneration ||
                ownership.stateVersion != payload.stateVersion ||
                payload.newOwnerGeneration != payload.oldOwnerGeneration + 1L
            ) {
                result = GlafiraOwnershipTransferApplyResult.STALE_OWNERSHIP
                db.setTransactionSuccessful()
                return@synchronized result
            }

            val leaseExpiry = activeOrExpiredLeaseExpiry(db, payload.taskId)
            if (leaseExpiry != null && leaseExpiry > nowMillis) {
                result = GlafiraOwnershipTransferApplyResult.TASK_NOT_QUIESCENT
                db.setTransactionSuccessful()
                return@synchronized result
            }
            if (!ownerSession.consume()) {
                result = GlafiraOwnershipTransferApplyResult.OWNER_AUTHENTICATION_REQUIRED
                db.setTransactionSuccessful()
                return@synchronized result
            }

            val changed = db.update(
                TABLE_OWNERSHIP,
                ContentValues().apply {
                    put("owner_core_id", payload.newOwnerCoreId)
                    put("owner_generation", payload.newOwnerGeneration)
                    putNull("approval_ref")
                    put("updated_at", nowMillis)
                },
                "task_id = ? AND project_id = ? AND owner_core_id = ? AND owner_generation = ? " +
                    "AND state_version = ? AND payload_sha256 = ? AND idempotency_key = ? " +
                    "AND effect_risk = ? AND approval_ref IS ?",
                arrayOf(
                    payload.taskId,
                    payload.projectId,
                    payload.oldOwnerCoreId,
                    payload.oldOwnerGeneration.toString(),
                    payload.stateVersion.toString(),
                    payload.payloadSha256,
                    payload.idempotencyKey,
                    payload.effectRisk,
                    payload.approvalRef
                )
            )
            if (changed != 1) {
                result = GlafiraOwnershipTransferApplyResult.STALE_OWNERSHIP
                return@synchronized result
            }

            // An expired lease is stale authority; a live lease was rejected above.
            if (leaseExpiry != null) {
                db.delete("core_desktop_leases", "task_id = ?", arrayOf(payload.taskId))
            }
            // Grants and approvals were bound to the old owner generation and cannot follow
            // the task to a new owner. The new owner must obtain its own approval if required.
            db.delete(GlafiraCoreLiveRunStore.TABLE, "task_id = ?", arrayOf(payload.taskId))
            ownerApprovalStore.invalidateForOwnershipTransfer(
                db, payload.taskId, payload.oldOwnerCoreId, payload.oldOwnerGeneration
            )

            db.insertOrThrow(TABLE_RECEIPTS, null, ContentValues().apply {
                put("transfer_id", payload.transferId)
                put("nonce", payload.nonce)
                put("task_id", payload.taskId)
                put("project_id", payload.projectId)
                put("old_owner_core_id", payload.oldOwnerCoreId)
                put("new_owner_core_id", payload.newOwnerCoreId)
                put("old_owner_generation", payload.oldOwnerGeneration)
                put("new_owner_generation", payload.newOwnerGeneration)
                put("state_version", payload.stateVersion)
                put("envelope_sha256", envelopeSha256)
                put("signing_key_epoch", payload.signingKeyEpoch)
                put("signing_public_key_sha256", payload.signingPublicKeySha256)
                put("issued_at", issuedAtMillis)
                put("expires_at", expiresAtMillis)
                put("applied_at", nowMillis)
            })

            traceStore.appendInTransaction(
                db,
                GlafiraCoreTraceDraft(
                    eventId = UUID.randomUUID().toString(),
                    projectId = payload.projectId,
                    taskId = payload.taskId,
                    correlationId = payload.transferId,
                    occurredAtMillis = nowMillis,
                    actorType = GlafiraCoreTraceActorType.OWNER,
                    actorId = "owner",
                    component = "CoreOwnershipTransferStore",
                    eventType = GlafiraCoreTraceEventType.TASK_OWNERSHIP_TRANSFERRED,
                    actionCode = "TASK_OWNERSHIP_TRANSFER_ACCEPTED",
                    targetType = "CORE",
                    targetId = payload.newOwnerCoreId,
                    resultStatus = "APPLIED",
                    approvalRef = payload.approvalRef,
                    evidenceRefs = listOf(payload.transferId, envelopeSha256)
                )
            )
            db.setTransactionSuccessful()
            result = GlafiraOwnershipTransferApplyResult.APPLIED
            return@synchronized result
        } finally {
            db.endTransaction()
        }
    }

    private fun validityWindow(
        payload: GlafiraOwnershipTransferSigningPayload,
        nowMillis: Long
    ): Pair<Long, Long>? = runCatching {
        val issuedAt = OffsetDateTime.parse(payload.issuedAt).toInstant().toEpochMilli()
        val expiresAt = OffsetDateTime.parse(payload.expiresAt).toInstant().toEpochMilli()
        if (issuedAt <= nowMillis &&
            nowMillis < expiresAt &&
            expiresAt > issuedAt &&
            expiresAt - issuedAt <= MAX_VALIDITY_MILLIS
        ) issuedAt to expiresAt else null
    }.getOrNull()

    private fun decodeCanonicalSignature(encoded: String): ByteArray? = runCatching {
        val decoded = Base64.getDecoder().decode(encoded)
        if (decoded.size != SIGNATURE_BYTES || Base64.getEncoder().encodeToString(decoded) != encoded) null
        else decoded
    }.getOrNull()

    private fun decodePeerPublicKey(encoded: String) = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()

    private fun envelopeSha256(canonicalPayload: ByteArray, signature: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(canonicalPayload)
        digest.update(signature)
        return digest.digest().toHex()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun findReceipt(db: SQLiteDatabase, transferId: String): GlafiraCoreOwnershipTransferReceipt? =
        db.query(
            TABLE_RECEIPTS,
            arrayOf(
                "transfer_id", "nonce", "task_id", "project_id", "old_owner_core_id",
                "new_owner_core_id", "old_owner_generation", "new_owner_generation",
                "state_version", "envelope_sha256", "signing_key_epoch",
                "signing_public_key_sha256", "issued_at", "expires_at", "applied_at"
            ),
            "transfer_id = ?",
            arrayOf(transferId),
            null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else GlafiraCoreOwnershipTransferReceipt(
                transferId = cursor.getString(0),
                nonce = cursor.getString(1),
                taskId = cursor.getString(2),
                projectId = cursor.getString(3),
                oldOwnerCoreId = cursor.getString(4),
                newOwnerCoreId = cursor.getString(5),
                oldOwnerGeneration = cursor.getLong(6),
                newOwnerGeneration = cursor.getLong(7),
                stateVersion = cursor.getLong(8),
                envelopeSha256 = cursor.getString(9),
                signingKeyEpoch = cursor.getLong(10),
                signingPublicKeySha256 = cursor.getString(11),
                issuedAtMillis = cursor.getLong(12),
                expiresAtMillis = cursor.getLong(13),
                appliedAtMillis = cursor.getLong(14)
            )
        }

    private fun nonceExists(db: SQLiteDatabase, nonce: String): Boolean =
        db.query(TABLE_RECEIPTS, arrayOf("transfer_id"), "nonce = ?", arrayOf(nonce),
            null, null, null, "1").use { it.moveToFirst() }

    private fun activeOrExpiredLeaseExpiry(db: SQLiteDatabase, taskId: String): Long? =
        db.query("core_desktop_leases", arrayOf("lease_expires_at"), "task_id = ?",
            arrayOf(taskId), null, null, null, "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun hasTaskResult(db: SQLiteDatabase, taskId: String): Boolean {
        val desktopResult = db.query("core_desktop_results", arrayOf("task_id"), "task_id = ?",
            arrayOf(taskId), null, null, null, "1").use { it.moveToFirst() }
        if (desktopResult) return true
        return db.query("core_task_results", arrayOf("task_id"), "task_id = ?",
            arrayOf(taskId), null, null, null, "1").use { it.moveToFirst() }
    }

    companion object {
        private const val TABLE_RECEIPTS = "core_ownership_transfer_receipts"
        private const val TABLE_OWNERSHIP = "core_task_ownership"
        private const val MAX_VALIDITY_MILLIS = 15 * 60 * 1000L
        private const val SIGNATURE_BYTES = 64
    }
}
