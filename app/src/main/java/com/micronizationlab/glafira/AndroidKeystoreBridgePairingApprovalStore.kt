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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            throw IllegalStateException("Unsupported Bridge approval database migration.")
        }
    }

    @Synchronized
    override fun enqueue(candidate: GlafiraBridgePendingApproval): Boolean {
        if (!validApproval(candidate)) return false
        return runCatching {
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
            writableDatabase.insertOrThrow(APPROVALS, null, values) >= 0L
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
            readableDatabase.query(
                APPROVALS,
                APPROVAL_COLUMNS,
                "status = ?",
                arrayOf(GlafiraBridgeApprovalStatus.PENDING.name),
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
            val deliveryCipher = encrypt(hmacSecret, "delivery:$approvalId")
            val scopes = encodeScopes(ownerSelectedScopes)
            val db = writableDatabase
            var result: GlafiraBridgeApprovalMutation = GlafiraBridgeApprovalMutation.StorageError
            db.beginTransaction()
            try {
                val status = db.query(
                    APPROVALS, arrayOf("status"), "approval_id = ?",
                    arrayOf(approvalId), null, null, null, "1"
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                if (status == null) {
                    result = GlafiraBridgeApprovalMutation.NotFound
                } else if (status != GlafiraBridgeApprovalStatus.PENDING.name) {
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
                        put("iv", deliveryCipher.iv)
                        put("ciphertext", deliveryCipher.ciphertext)
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
                SELECT d.approval_id, d.device_id
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
                        add(GlafiraBridgePendingCredentialDelivery(cursor.getString(0), cursor.getString(1)))
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
                    val delivery = db.query(
                        DELIVERIES,
                        arrayOf("device_id", "iv", "ciphertext", "granted_scopes", "owner_session_id", "claim_expires_at"),
                        "approval_id = ?",
                        arrayOf(approvalId),
                        null, null, null, "1"
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) null
                        else DeliveryRow(
                            deviceId = cursor.getString(0),
                            iv = cursor.getBlob(1),
                            ciphertext = cursor.getBlob(2),
                            scopes = decodeScopes(cursor.getString(3)),
                            ownerSessionId = cursor.getString(4),
                            claimExpiresAt = cursor.getLong(5)
                        )
                    }
                    if (delivery == null) {
                        result = GlafiraBridgeDeliveryResult.StorageError
                    } else if (delivery.ownerSessionId != null &&
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
                        deviceId = delivery.deviceId
                        scopes = delivery.scopes
                        result = GlafiraBridgeDeliveryResult.StorageError
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            if (encrypted != null && deviceId != null) {
                val secret = decrypt(encrypted!!, "delivery:$approvalId")
                GlafiraBridgeDeliveryResult.Available(
                    GlafiraBridgeCredentialDelivery(approvalId, deviceId!!, secret, scopes)
                )
            } else result
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
            readableDatabase.query(
                CREDENTIALS,
                arrayOf("iv", "ciphertext", "granted_scopes"),
                "device_id = ?",
                arrayOf(deviceId),
                null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val secret = decrypt(
                    EncryptedSecret(cursor.getBlob(0), cursor.getBlob(1)),
                    "credential:$deviceId"
                )
                val scopes = decodeScopes(cursor.getString(2))
                if (secret.size != DEVICE_SECRET_BYTES || !validScopes(scopes)) {
                    secret.fill(0)
                    null
                } else GlafiraBridgeDeviceCredential(deviceId, secret, scopes)
            }
        }.getOrNull()
    }

    private fun encrypt(plaintext: ByteArray, aad: String): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return EncryptedSecret(cipher.iv, cipher.doFinal(plaintext))
    }

    private fun decrypt(encrypted: EncryptedSecret, aad: String): ByteArray {
        require(encrypted.iv.size == GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, encrypted.iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted.ciphertext)
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

    private fun validScopes(value: Set<String>): Boolean {
        val knownScopes = GlafiraToolRegistry.all().flatMap { it.requiredScopes }.toSet()
        return value.isNotEmpty() &&
            value.size <= GlafiraBridgeOwnerApprovalService.MAX_REQUESTED_SCOPES &&
            value.all { validScope(it) && it in knownScopes }
    }

    private fun encodeScopes(value: Set<String>) = value.sorted().joinToString(",")

    private fun decodeScopes(value: String) =
        value.split(",").filter(String::isNotBlank).toSet()

    private data class EncryptedSecret(val iv: ByteArray, val ciphertext: ByteArray)

    private data class DeliveryRow(
        val deviceId: String,
        val iv: ByteArray,
        val ciphertext: ByteArray,
        val scopes: Set<String>,
        val ownerSessionId: String?,
        val claimExpiresAt: Long
    )

    companion object {
        private const val DATABASE_NAME = "glafira_bridge_approvals.db"
        private const val DATABASE_VERSION = 1
        private const val APPROVALS = "pairing_approvals"
        private const val CREDENTIALS = "device_credentials"
        private const val DELIVERIES = "credential_deliveries"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "glafira.bridge.approvals.aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEVICE_SECRET_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val APPROVAL_COLUMNS = arrayOf(
            "approval_id", "ticket_id", "client_nonce", "device_label",
            "platform", "requested_scopes", "received_at", "status"
        )
    }
}
