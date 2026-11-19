package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable Android implementation of the owner-approval and credential ports.
 *
 * Pending requests, credential issuance and the encrypted one-time delivery outbox are
 * persisted in SQLite. Credential and delivery secrets are encrypted with AES-GCM under
 * a non-exportable Android Keystore key. Approval and issuance are one DB transaction.
 */
class AndroidKeystoreBridgePairingApprovalStore(
    context: Context
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), GlafiraBridgePairingApprovalStore, GlafiraBridgeCredentialProvider {

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $APPROVALS (
                approval_id TEXT PRIMARY KEY NOT NULL,
                ticket_id TEXT NOT NULL,
                client_nonce TEXT NOT NULL,
                device_label TEXT NOT NULL,
                platform TEXT NOT NULL,
                requested_scopes TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                delivery_acknowledged INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX bridge_approval_status_idx ON $APPROVALS(status, received_at)")
        db.execSQL(
            """
            CREATE TABLE $CREDENTIALS (
                device_id TEXT PRIMARY KEY NOT NULL,
                approval_id TEXT UNIQUE NOT NULL,
                iv BLOB NOT NULL,
                ciphertext BLOB NOT NULL,
                granted_scopes TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $DELIVERIES (
                approval_id TEXT PRIMARY KEY NOT NULL,
                device_id TEXT NOT NULL,
                iv BLOB NOT NULL,
                ciphertext BLOB NOT NULL,
                granted_scopes TEXT NOT NULL,
                owner_session_id TEXT,
                claim_expires_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        createRevocations(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported Bridge approval database migration." }
        createRevocations(db)
    }

    private fun createRevocations(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS device_revocations (device_id TEXT PRIMARY KEY NOT NULL, revoked_at INTEGER NOT NULL)")
    }

    /** Metadata only; never decrypts or exposes a device secret to settings. */
    @Synchronized
    fun listPairedDevices(): List<GlafiraPairedDeviceSummary> = readableDatabase.rawQuery(
        "SELECT c.device_id,a.device_label,a.platform,c.granted_scopes,c.created_at FROM $CREDENTIALS c JOIN $APPROVALS a ON a.approval_id=c.approval_id ORDER BY c.created_at DESC", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(GlafiraPairedDeviceSummary(cursor.getString(0), cursor.getString(1),
            cursor.getString(2), decodeScopes(cursor.getString(3)), cursor.getLong(4)))
    } }

    /** Atomic revocation invalidates even an unclaimed credential delivery. No remote ACK is required. */
    @Synchronized
    fun revokeDevice(deviceId: String, session: GlafiraBridgeOwnerSession,
        nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (!validId(deviceId) || nowEpochMs <= 0 || !session.isValid(nowEpochMs)) return false
        return runCatching {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.insertWithOnConflict("device_revocations", null, ContentValues().apply {
                    put("device_id", deviceId); put("revoked_at", nowEpochMs)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                db.delete(DELIVERIES, "device_id = ?", arrayOf(deviceId))
                db.delete(CREDENTIALS, "device_id = ?", arrayOf(deviceId))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            true
        }.getOrDefault(false)
    }

    private fun isRevoked(db: SQLiteDatabase, deviceId: String): Boolean = db.rawQuery(
        "SELECT 1 FROM device_revocations WHERE device_id = ?", arrayOf(deviceId)
    ).use { it.moveToFirst() }

    @Synchronized
    override fun enqueue(candidate: GlafiraBridgePendingApproval): Boolean {
        if (!validApproval(candidate)) return false
        return runCatching {
            val db = writableDatabase
            var inserted = false
            db.beginTransaction()
            try {
                val cutoff = (System.currentTimeMillis() - PENDING_APPROVAL_TTL_MS).coerceAtLeast(0L)
                db.update(
                    APPROVALS,
                    ContentValues().apply { put("status", GlafiraBridgeApprovalStatus.EXPIRED.name) },
                    "status = ? AND received_at <= ?",
                    arrayOf(GlafiraBridgeApprovalStatus.PENDING.name, cutoff.toString())
                )
                val pendingCount = db.rawQuery(
                    "SELECT COUNT(*) FROM $APPROVALS WHERE status = ?",
                    arrayOf(GlafiraBridgeApprovalStatus.PENDING.name)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else GlafiraBridgeOwnerApprovalService.MAX_PENDING_APPROVALS }
                if (pendingCount < GlafiraBridgeOwnerApprovalService.MAX_PENDING_APPROVALS) {
                    val values = ContentValues().apply {
                        put("approval_id", candidate.approvalId)
                        put("ticket_id", candidate.ticketId)
                        put("client_nonce", candidate.clientNonce)
                        put("device_label", candidate.deviceLabel)
                        put("platform", candidate.platform.name)
                        put("requested_scopes", encodeScopes(candidate.requestedScopes))
                        put("received_at", candidate.receivedAtEpochMs)
                        put("status", candidate.status.name)
                    }
                    inserted = db.insertOrThrow(APPROVALS, null, values) >= 0L
                }
                // Even when the queue is full, persist the expiry sweep performed above.
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            inserted
        }.getOrDefault(false)
    }

    @Synchronized
    override fun find(approvalId: String): GlafiraBridgePendingApproval? {
        if (!validId(approvalId)) return null
        return runCatching {
            readableDatabase.query(
                APPROVALS,
                APPROVAL_COLUMNS,
                "approval_id = ?",
                arrayOf(approvalId),
                null, null, null, "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toApproval() else null }
        }.getOrNull()
    }

    @Synchronized
    override fun listPending(limit: Int): List<GlafiraBridgePendingApproval> {
        if (limit !in 1..GlafiraBridgeOwnerApprovalService.MAX_PENDING_APPROVALS) return emptyList()
        return runCatching {
            val now = System.currentTimeMillis()
            val cutoff = (now - PENDING_APPROVAL_TTL_MS).coerceAtLeast(0L)
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.update(
                    APPROVALS,
                    ContentValues().apply { put("status", GlafiraBridgeApprovalStatus.EXPIRED.name) },
                    "status = ? AND received_at <= ?",
                    arrayOf(GlafiraBridgeApprovalStatus.PENDING.name, cutoff.toString())
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.query(
                APPROVALS,
                APPROVAL_COLUMNS,
                "status = ? AND received_at > ?",
                arrayOf(GlafiraBridgeApprovalStatus.PENDING.name, cutoff.toString()),
                null, null, "received_at ASC",
                limit.toString()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toApproval())
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun approveAndIssue(
        approvalId: String,
        deviceId: String,
        hmacSecret: ByteArray,
        ownerSelectedScopes: Set<String>,
        approvedAtEpochMs: Long
    ): GlafiraBridgeApprovalMutation {
        if (!validId(approvalId) || !validId(deviceId) ||
            hmacSecret.size != DEVICE_SECRET_BYTES ||
            !validScopes(ownerSelectedScopes) || approvedAtEpochMs <= 0L
        ) return GlafiraBridgeApprovalMutation.InvalidScopes

        return runCatching {
            val credentialCipher = encrypt(hmacSecret, "credential:$deviceId")
            var deliveryCipher: EncryptedSecret? = null
            try {
            val delivery = encrypt(hmacSecret, "delivery:$approvalId")
            deliveryCipher = delivery
            val scopes = encodeScopes(ownerSelectedScopes)
            val db = writableDatabase
            var result: GlafiraBridgeApprovalMutation = GlafiraBridgeApprovalMutation.StorageError
            db.beginTransaction()
            try {
                val pendingRow = db.query(
                    APPROVALS, arrayOf("status", "received_at"), "approval_id = ?",
                    arrayOf(approvalId), null, null, null, "1"
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
                }
                if (isRevoked(db, deviceId)) {
                    result = GlafiraBridgeApprovalMutation.AlreadyDecided
                } else if (pendingRow == null) {
                    result = GlafiraBridgeApprovalMutation.NotFound
                } else if (pendingRow.first != GlafiraBridgeApprovalStatus.PENDING.name ||
                    approvedAtEpochMs < pendingRow.second ||
                    approvedAtEpochMs - pendingRow.second >= PENDING_APPROVAL_TTL_MS
                ) {
                    if (pendingRow.first == GlafiraBridgeApprovalStatus.PENDING.name) {
                        db.update(
                            APPROVALS,
                            ContentValues().apply { put("status", GlafiraBridgeApprovalStatus.EXPIRED.name) },
                            "approval_id = ? AND status = ?",
                            arrayOf(approvalId, GlafiraBridgeApprovalStatus.PENDING.name)
                        )
                    }
                    result = GlafiraBridgeApprovalMutation.AlreadyDecided
                } else {
                    val credentialValues = ContentValues().apply {
                        put("device_id", deviceId)
                        put("approval_id", approvalId)
                        put("iv", credentialCipher.iv)
                        put("ciphertext", credentialCipher.ciphertext)
                        put("granted_scopes", scopes)
                        put("created_at", approvedAtEpochMs)
                    }
                    db.insertOrThrow(CREDENTIALS, null, credentialValues)

                    val deliveryValues = ContentValues().apply {
                        put("approval_id", approvalId)
                        put("device_id", deviceId)
                        put("iv", delivery.iv)
                        put("ciphertext", delivery.ciphertext)
                        put("granted_scopes", scopes)
                        put("claim_expires_at", 0L)
                    }
                    db.insertOrThrow(DELIVERIES, null, deliveryValues)

                    val changed = db.update(
                        APPROVALS,
                        ContentValues().apply { put("status", GlafiraBridgeApprovalStatus.APPROVED.name) },
                        "approval_id = ? AND status = ?",
                        arrayOf(approvalId, GlafiraBridgeApprovalStatus.PENDING.name)
                    )
                    check(changed == 1) { "Pairing approval state changed concurrently." }
                    result = GlafiraBridgeApprovalMutation.Approved(deviceId)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            result
            } finally {
                credentialCipher.wipe()
                deliveryCipher?.wipe()
            }
        }.getOrDefault(GlafiraBridgeApprovalMutation.StorageError)
    }

    @Synchronized
    override fun reject(approvalId: String, rejectedAtEpochMs: Long): Boolean {
        if (!validId(approvalId) || rejectedAtEpochMs <= 0L) return false
        return runCatching {
            val values = ContentValues().apply {
                put("status", GlafiraBridgeApprovalStatus.REJECTED.name)
            }
            writableDatabase.update(
                APPROVALS,
                values,
                "approval_id = ? AND status = ?",
                arrayOf(approvalId, GlafiraBridgeApprovalStatus.PENDING.name)
            ) == 1
        }.getOrDefault(false)
    }

    @Synchronized
    override fun listUndeliveredDeliveries(
        limit: Int
    ): List<GlafiraBridgePendingCredentialDelivery> {
        if (limit !in 1..GlafiraBridgeOwnerApprovalService.MAX_PENDING_APPROVALS) return emptyList()
        return runCatching {
            readableDatabase.rawQuery(
                """
                SELECT d.approval_id, a.ticket_id, d.device_id
                FROM $DELIVERIES d
                INNER JOIN $APPROVALS a ON a.approval_id = d.approval_id
                WHERE a.status = ? AND a.delivery_acknowledged = 0
                ORDER BY a.received_at ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(GlafiraBridgeApprovalStatus.APPROVED.name, limit.toString())
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(GlafiraBridgePendingCredentialDelivery(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun claimDelivery(
        approvalId: String,
        ownerSessionId: String,
        ownerSessionExpiresAtEpochMs: Long,
        nowEpochMs: Long
    ): GlafiraBridgeDeliveryResult {
        if (!validId(approvalId) || !validSessionId(ownerSessionId) ||
            nowEpochMs <= 0L || ownerSessionExpiresAtEpochMs <= nowEpochMs
        ) return GlafiraBridgeDeliveryResult.NotApproved

        return runCatching {
            val db = writableDatabase
            var result: GlafiraBridgeDeliveryResult = GlafiraBridgeDeliveryResult.StorageError
            var encrypted: EncryptedSecret? = null
            var deviceId: String? = null
            var ticketId: String? = null
            var scopes: Set<String> = emptySet()
            db.beginTransaction()
            try {
                val approvalState = db.query(
                    APPROVALS,
                    arrayOf("status", "delivery_acknowledged"),
                    "approval_id = ?",
                    arrayOf(approvalId),
                    null, null, null, "1"
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else cursor.getString(0) to (cursor.getInt(1) == 1)
                }
                if (approvalState == null) {
                    result = GlafiraBridgeDeliveryResult.NotFound
                } else if (approvalState.first != GlafiraBridgeApprovalStatus.APPROVED.name) {
                    result = GlafiraBridgeDeliveryResult.NotApproved
                } else if (approvalState.second) {
                    result = GlafiraBridgeDeliveryResult.AlreadyAcknowledged
                } else {
                    val delivery = db.rawQuery(
                        "SELECT d.device_id, d.iv, d.ciphertext, d.granted_scopes, d.owner_session_id, d.claim_expires_at, a.ticket_id FROM $DELIVERIES d INNER JOIN $APPROVALS a ON a.approval_id = d.approval_id WHERE d.approval_id = ? LIMIT 1",
                        arrayOf(approvalId)
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) null
                        else DeliveryRow(
                            deviceId = cursor.getString(0),
                            iv = cursor.getBlob(1),
                            ciphertext = cursor.getBlob(2),
                            scopes = decodeScopes(cursor.getString(3)),
                            ownerSessionId = cursor.getString(4),
                            claimExpiresAt = cursor.getLong(5),
                            ticketId = cursor.getString(6)
                        )
                    }
                    if (delivery == null) {
                        result = GlafiraBridgeDeliveryResult.StorageError
                    } else {
                        var buffersTransferred = false
                        try {
                            if (delivery.ownerSessionId != null &&
                                delivery.ownerSessionId != ownerSessionId &&
                                delivery.claimExpiresAt > nowEpochMs
                            ) {
                                result = GlafiraBridgeDeliveryResult.ClaimedByAnotherOwnerSession
                            } else {
                                val changed = db.update(
                                    DELIVERIES,
                                    ContentValues().apply {
                                        put("owner_session_id", ownerSessionId)
                                        put("claim_expires_at", ownerSessionExpiresAtEpochMs)
                                    },
                                    "approval_id = ?",
                                    arrayOf(approvalId)
                                )
                                check(changed == 1)
                                encrypted = EncryptedSecret(delivery.iv, delivery.ciphertext)
                                buffersTransferred = true
                                deviceId = delivery.deviceId
                                ticketId = delivery.ticketId
                                scopes = delivery.scopes
                                result = GlafiraBridgeDeliveryResult.StorageError
                            }
                        } finally {
                            if (!buffersTransferred) {
                                delivery.iv.fill(0)
                                delivery.ciphertext.fill(0)
                            }
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            if (encrypted != null && deviceId != null && ticketId != null) {
                try {
                    val secret = decrypt(encrypted!!, "delivery:$approvalId")
                    GlafiraBridgeDeliveryResult.Available(
                        GlafiraBridgeCredentialDelivery(approvalId, ticketId!!, deviceId!!, secret, scopes)
                    )
                } finally {
                    encrypted?.wipe()
                }
            } else {
                encrypted?.wipe()
                result
            }
        }.getOrDefault(GlafiraBridgeDeliveryResult.StorageError)
    }

    @Synchronized
    override fun acknowledgeDelivery(
        approvalId: String,
        ownerSessionId: String
    ): Boolean {
        if (!validId(approvalId) || !validSessionId(ownerSessionId)) return false
        return runCatching {
            val db = writableDatabase
            var acknowledged = false
            db.beginTransaction()
            try {
                val removed = db.delete(
                    DELIVERIES,
                    "approval_id = ? AND owner_session_id = ?",
                    arrayOf(approvalId, ownerSessionId)
                )
                if (removed == 1) {
                    val changed = db.update(
                        APPROVALS,
                        ContentValues().apply { put("delivery_acknowledged", 1) },
                        "approval_id = ? AND status = ?",
                        arrayOf(approvalId, GlafiraBridgeApprovalStatus.APPROVED.name)
                    )
                    acknowledged = changed == 1
                }
                if (acknowledged) db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            acknowledged
        }.getOrDefault(false)
    }

    @Synchronized
    override fun load(deviceId: String): GlafiraBridgeDeviceCredential? {
        if (!validId(deviceId)) return null
        return runCatching {
            if (isRevoked(readableDatabase, deviceId)) return@runCatching null
            readableDatabase.query(
                CREDENTIALS,
                arrayOf("iv", "ciphertext", "granted_scopes"),
                "device_id = ?",
                arrayOf(deviceId),
                null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val encrypted = EncryptedSecret(cursor.getBlob(0), cursor.getBlob(1))
                val secret = try {
                    decrypt(encrypted, "credential:$deviceId")
                } finally {
                    encrypted.wipe()
                }
                try {
                    val scopes = decodeScopes(cursor.getString(2))
                    if (secret.size != DEVICE_SECRET_BYTES || !validScopes(scopes)) {
                        secret.fill(0)
                        null
                    } else GlafiraBridgeDeviceCredential(deviceId, secret, scopes)
                } catch (failure: Exception) {
                    secret.fill(0)
                    throw failure
                }
            }
        }.getOrNull()
    }

    private fun encrypt(plaintext: ByteArray, aad: String): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val associatedData = aad.toByteArray(Charsets.UTF_8)
        return try {
            cipher.updateAAD(associatedData)
            EncryptedSecret(cipher.iv, cipher.doFinal(plaintext))
        } finally {
            associatedData.fill(0)
        }
    }

    private fun decrypt(encrypted: EncryptedSecret, aad: String): ByteArray {
        require(encrypted.iv.size == GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, encrypted.iv))
        val associatedData = aad.toByteArray(Charsets.UTF_8)
        return try {
            cipher.updateAAD(associatedData)
            cipher.doFinal(encrypted.ciphertext)
        } finally {
            associatedData.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey =
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Bridge approval encryption key is missing.")

    private fun android.database.Cursor.toApproval() = GlafiraBridgePendingApproval(
        approvalId = getString(0),
        ticketId = getString(1),
        clientNonce = getString(2),
        deviceLabel = getString(3),
        platform = GlafiraBridgeClientPlatform.valueOf(getString(4)),
        requestedScopes = decodeScopes(getString(5)),
        receivedAtEpochMs = getLong(6),
        status = GlafiraBridgeApprovalStatus.valueOf(getString(7))
    )

    private fun validApproval(value: GlafiraBridgePendingApproval) =
        validId(value.approvalId) && validId(value.ticketId) &&
            value.clientNonce.length in 32..128 &&
            value.clientNonce.all { it.isLetterOrDigit() || it in "-_" } &&
            value.deviceLabel.length in 1..64 &&
            value.deviceLabel.none(Char::isISOControl) &&
            value.receivedAtEpochMs > 0L &&
            value.status == GlafiraBridgeApprovalStatus.PENDING &&
            value.requestedScopes.size <= GlafiraBridgeOwnerApprovalService.MAX_REQUESTED_SCOPES &&
            value.requestedScopes.all(::validScope)

    private fun validId(value: String) =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it in "-_" }

    private fun validSessionId(value: String) =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it in "-_." }

    private fun validScope(value: String) =
        value.length in 1..96 && value.all { it.isLetterOrDigit() || it in "._-" }

    private fun validScopes(value: Set<String>) =
        value.isNotEmpty() &&
            value.size <= GlafiraBridgeOwnerApprovalService.MAX_REQUESTED_SCOPES &&
            value.all { validScope(it) && GlafiraToolRegistry.isKnownScope(it) }

    private fun encodeScopes(value: Set<String>) = value.sorted().joinToString(",")

    private fun decodeScopes(value: String) =
        value.split(",").filter(String::isNotBlank).toSet()

    private data class EncryptedSecret(val iv: ByteArray, val ciphertext: ByteArray) {
        fun wipe() {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private data class DeliveryRow(
        val deviceId: String,
        val iv: ByteArray,
        val ciphertext: ByteArray,
        val scopes: Set<String>,
        val ownerSessionId: String?,
        val claimExpiresAt: Long,
        val ticketId: String
    )

    companion object {
        private const val DATABASE_NAME = "glafira_bridge_approvals.db"
        private const val DATABASE_VERSION = 2
        private const val APPROVALS = "pairing_approvals"
        private const val CREDENTIALS = "device_credentials"
        private const val DELIVERIES = "credential_deliveries"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "glafira.bridge.approvals.aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEVICE_SECRET_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val PENDING_APPROVAL_TTL_MS = 24L * 60L * 60L * 1000L
        private val APPROVAL_COLUMNS = arrayOf(
            "approval_id", "ticket_id", "client_nonce", "device_label",
            "platform", "requested_scopes", "received_at", "status"
        )
    }
}

/** Safe display metadata for a locally approved device. */
data class GlafiraPairedDeviceSummary(val deviceId: String, val label: String, val platform: String,
    val scopes: Set<String>, val createdAtEpochMs: Long)
