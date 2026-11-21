package com.micronizationlab.glafira

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.PublicKey
import java.time.Instant
import java.util.Base64

data class GlafiraOwnershipTransferEnvelope(
    val payload: GlafiraOwnershipTransferSigningPayload,
    val signatureBase64: String
) {
    companion object {
        /** Strict flat-wire decoder for dual-core-ownership.schema.json. */
        fun parse(json: String): GlafiraOwnershipTransferEnvelope {
            val value = runCatching { JSONObject(json) }
                .getOrElse { throw IllegalArgumentException("OWNERSHIP_TRANSFER_JSON_INVALID", it) }
            val expected = setOf(
                "schemaVersion", "transferId", "nonce", "taskId", "projectId",
                "oldOwnerCoreId", "newOwnerCoreId", "oldOwnerGeneration",
                "newOwnerGeneration", "stateVersion", "payloadSha256", "idempotencyKey",
                "effectRisk", "approvalRef", "issuedAt", "expiresAt", "signingKeyEpoch",
                "signingPublicKeySha256", "signatureAlgorithm", "signatureFormat", "signature"
            )
            require(value.keys().asSequence().toSet() == expected) {
                "OWNERSHIP_TRANSFER_FIELDS_INVALID"
            }
            fun string(name: String): String = value.get(name).let {
                require(it is String) { "OWNERSHIP_TRANSFER_FIELD_TYPE_INVALID:$name" }
                it
            }
            fun integer(name: String): Long = value.get(name).let {
                require(it is Int || it is Long) { "OWNERSHIP_TRANSFER_FIELD_TYPE_INVALID:$name" }
                (it as Number).toLong()
            }
            require(integer("schemaVersion") == 1L) { "OWNERSHIP_TRANSFER_SCHEMA_VERSION_UNSUPPORTED" }
            val envelope = GlafiraOwnershipTransferEnvelope(
                GlafiraOwnershipTransferSigningPayload(
                    schemaVersion = 1,
                    transferId = string("transferId"),
                    nonce = string("nonce"),
                    taskId = string("taskId"),
                    projectId = string("projectId"),
                    oldOwnerCoreId = string("oldOwnerCoreId"),
                    newOwnerCoreId = string("newOwnerCoreId"),
                    oldOwnerGeneration = integer("oldOwnerGeneration"),
                    newOwnerGeneration = integer("newOwnerGeneration"),
                    stateVersion = integer("stateVersion"),
                    payloadSha256 = string("payloadSha256"),
                    idempotencyKey = string("idempotencyKey"),
                    effectRisk = string("effectRisk"),
                    approvalRef = if (value.isNull("approvalRef")) null
                        else string("approvalRef"),
                    issuedAt = string("issuedAt"),
                    expiresAt = string("expiresAt"),
                    signingKeyEpoch = integer("signingKeyEpoch"),
                    signingPublicKeySha256 = string("signingPublicKeySha256"),
                    signatureAlgorithm = string("signatureAlgorithm"),
                    signatureFormat = string("signatureFormat")
                ),
                string("signature")
            )
            GlafiraCoreOwnershipTransferCryptography.canonicalBytes(envelope.payload)
            require(envelope.signatureBase64.length in 80..256 &&
                envelope.signatureBase64.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) {
                "OWNERSHIP_TRANSFER_SIGNATURE_ENCODING_INVALID"
            }
            return envelope
        }
    }
}

data class GlafiraMobileOwnershipProjection(
    val taskId: String,
    val projectId: String,
    val ownerCoreId: String,
    val ownerGeneration: Long,
    val stateVersion: Long,
    val payloadSha256: String,
    val idempotencyKey: String,
    val effectRisk: String,
    val approvalRef: String?,
    val lastTransferId: String,
    val lastNonce: String,
    val updatedAtEpochMs: Long
)

enum class GlafiraOwnershipSyncDisposition { APPLIED, EXACT_DUPLICATE }

data class GlafiraOwnershipSyncResult(
    val disposition: GlafiraOwnershipSyncDisposition,
    val projection: GlafiraMobileOwnershipProjection
)

fun interface GlafiraOwnershipTrustedKeyResolver {
    /** Return a key only when Core identity, key epoch and fingerprint are all trusted. */
    fun resolve(oldOwnerCoreId: String, signingKeyEpoch: Long, fingerprintSha256: String): PublicKey?
}

interface GlafiraMobileOwnershipProjectionStore {
    fun read(taskId: String, projectId: String): GlafiraMobileOwnershipProjection?
    fun write(projection: GlafiraMobileOwnershipProjection)
}

/**
 * Owner-facing, read-side ownership synchronization for Android.
 *
 * This adapter deliberately cannot sign a transfer, issue a lease, or authorize execution.
 * Windows Hub remains authoritative. The mobile client only advances its durable projection
 * after authenticating the old Core and proving an exact, monotonic generation transition.
 */
class GlafiraMobileOwnershipSyncAdapter(
    private val store: GlafiraMobileOwnershipProjectionStore,
    private val trustedKeys: GlafiraOwnershipTrustedKeyResolver
) {
    @Synchronized
    fun apply(
        envelope: GlafiraOwnershipTransferEnvelope,
        now: Instant = Instant.now()
    ): GlafiraOwnershipSyncResult {
        val payload = envelope.payload
        // Canonicalization performs the shared schema/semantic validation first.
        GlafiraCoreOwnershipTransferCryptography.canonicalBytes(payload)
        val issuedAt = runCatching { Instant.parse(payload.issuedAt) }
            .getOrElse { throw IllegalArgumentException("OWNERSHIP_TRANSFER_TIME_INVALID", it) }
        val expiresAt = runCatching { Instant.parse(payload.expiresAt) }
            .getOrElse { throw IllegalArgumentException("OWNERSHIP_TRANSFER_TIME_INVALID", it) }
        require(!now.isBefore(issuedAt) && now.isBefore(expiresAt)) {
            "OWNERSHIP_TRANSFER_OUTSIDE_VALIDITY_WINDOW"
        }
        val key = trustedKeys.resolve(
            payload.oldOwnerCoreId,
            payload.signingKeyEpoch,
            payload.signingPublicKeySha256
        ) ?: throw IllegalArgumentException("OWNERSHIP_TRANSFER_SIGNING_KEY_UNTRUSTED")
        val signature = runCatching { Base64.getDecoder().decode(envelope.signatureBase64) }
            .getOrElse { throw IllegalArgumentException("OWNERSHIP_TRANSFER_SIGNATURE_INVALID", it) }
        require(GlafiraCoreOwnershipTransferCryptography.verify(payload, key, signature)) {
            "OWNERSHIP_TRANSFER_SIGNATURE_INVALID"
        }

        val current = store.read(payload.taskId, payload.projectId)
        if (current != null && current.lastTransferId == payload.transferId) {
            require(current.lastNonce == payload.nonce && current.matchesTarget(payload)) {
                "OWNERSHIP_TRANSFER_IDEMPOTENCY_CONFLICT"
            }
            return GlafiraOwnershipSyncResult(
                GlafiraOwnershipSyncDisposition.EXACT_DUPLICATE,
                current
            )
        }
        if (current != null) {
            require(current.ownerCoreId == payload.oldOwnerCoreId &&
                current.ownerGeneration == payload.oldOwnerGeneration) {
                "OWNERSHIP_TRANSFER_PREVIOUS_OWNER_MISMATCH"
            }
            require(current.stateVersion == payload.stateVersion &&
                current.payloadSha256 == payload.payloadSha256 &&
                current.idempotencyKey == payload.idempotencyKey &&
                current.effectRisk == payload.effectRisk &&
                current.approvalRef == payload.approvalRef) {
                "OWNERSHIP_TRANSFER_PREVIOUS_STATE_MISMATCH"
            }
        }

        val projection = GlafiraMobileOwnershipProjection(
            taskId = payload.taskId,
            projectId = payload.projectId,
            ownerCoreId = payload.newOwnerCoreId,
            ownerGeneration = payload.newOwnerGeneration,
            stateVersion = payload.stateVersion,
            payloadSha256 = payload.payloadSha256,
            idempotencyKey = payload.idempotencyKey,
            effectRisk = payload.effectRisk,
            approvalRef = payload.approvalRef,
            lastTransferId = payload.transferId,
            lastNonce = payload.nonce,
            updatedAtEpochMs = now.toEpochMilli()
        )
        store.write(projection)
        return GlafiraOwnershipSyncResult(GlafiraOwnershipSyncDisposition.APPLIED, projection)
    }

    private fun GlafiraMobileOwnershipProjection.matchesTarget(
        payload: GlafiraOwnershipTransferSigningPayload
    ): Boolean = ownerCoreId == payload.newOwnerCoreId &&
        ownerGeneration == payload.newOwnerGeneration &&
        stateVersion == payload.stateVersion &&
        payloadSha256 == payload.payloadSha256 &&
        idempotencyKey == payload.idempotencyKey &&
        effectRisk == payload.effectRisk && approvalRef == payload.approvalRef
}

/** Atomic durable cache for the non-authoritative Android ownership projection. */
class AndroidGlafiraMobileOwnershipProjectionStore(context: Context) :
    GlafiraMobileOwnershipProjectionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mobile-ownership-projection-v1.json"))
    private val lock = Any()

    override fun read(taskId: String, projectId: String): GlafiraMobileOwnershipProjection? =
        synchronized(lock) { readAll()[key(taskId, projectId)] }

    override fun write(projection: GlafiraMobileOwnershipProjection) = synchronized(lock) {
        validateProjection(projection)
        val records = readAll().toMutableMap()
        records[key(projection.taskId, projection.projectId)] = projection
        val output = JSONArray()
        records.toSortedMap().values.forEach { output.put(it.toJson()) }
        val stream = file.startWrite()
        try {
            stream.write(output.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        Unit
    }

    private fun readAll(): Map<String, GlafiraMobileOwnershipProjection> {
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return emptyMap()
        }
        val array = runCatching { JSONArray(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalStateException("OWNERSHIP_PROJECTION_CORRUPT", it) }
        require(array.length() <= 100_000) { "OWNERSHIP_PROJECTION_LIMIT_EXCEEDED" }
        return buildMap {
            repeat(array.length()) {
                val projection = parse(array.getJSONObject(it))
                require(put(key(projection.taskId, projection.projectId), projection) == null) {
                    "OWNERSHIP_PROJECTION_DUPLICATE"
                }
            }
        }
    }

    private fun GlafiraMobileOwnershipProjection.toJson() = JSONObject().apply {
        put("schemaVersion", 1)
        put("taskId", taskId)
        put("projectId", projectId)
        put("ownerCoreId", ownerCoreId)
        put("ownerGeneration", ownerGeneration)
        put("stateVersion", stateVersion)
        put("payloadSha256", payloadSha256)
        put("idempotencyKey", idempotencyKey)
        put("effectRisk", effectRisk)
        put("approvalRef", approvalRef ?: JSONObject.NULL)
        put("lastTransferId", lastTransferId)
        put("lastNonce", lastNonce)
        put("updatedAtEpochMs", updatedAtEpochMs)
    }

    private fun parse(value: JSONObject): GlafiraMobileOwnershipProjection {
        val expected = setOf(
            "schemaVersion", "taskId", "projectId", "ownerCoreId", "ownerGeneration",
            "stateVersion", "payloadSha256", "idempotencyKey", "effectRisk", "approvalRef",
            "lastTransferId", "lastNonce", "updatedAtEpochMs"
        )
        require(value.keys().asSequence().toSet() == expected) {
            "OWNERSHIP_PROJECTION_SCHEMA_INVALID"
        }
        fun string(name: String): String = value.get(name).let {
            require(it is String) { "OWNERSHIP_PROJECTION_FIELD_TYPE_INVALID:$name" }
            it
        }
        fun integer(name: String): Long = value.get(name).let {
            require(it is Int || it is Long) { "OWNERSHIP_PROJECTION_FIELD_TYPE_INVALID:$name" }
            (it as Number).toLong()
        }
        require(integer("schemaVersion") == 1L) { "OWNERSHIP_PROJECTION_SCHEMA_INVALID" }
        val projection = GlafiraMobileOwnershipProjection(
            taskId = string("taskId"),
            projectId = string("projectId"),
            ownerCoreId = string("ownerCoreId"),
            ownerGeneration = integer("ownerGeneration"),
            stateVersion = integer("stateVersion"),
            payloadSha256 = string("payloadSha256"),
            idempotencyKey = string("idempotencyKey"),
            effectRisk = string("effectRisk"),
            approvalRef = if (value.isNull("approvalRef")) null else string("approvalRef"),
            lastTransferId = string("lastTransferId"),
            lastNonce = string("lastNonce"),
            updatedAtEpochMs = integer("updatedAtEpochMs")
        )
        validateProjection(projection)
        return projection
    }

    private fun validateProjection(projection: GlafiraMobileOwnershipProjection) {
        val identifier = Regex("^[A-Za-z0-9._-]{8,128}$")
        val allowedRisks = setOf(
            "READ_ONLY", "DRAFT", "EXTERNAL_SIDE_EFFECT", "FINANCIAL", "DESTRUCTIVE"
        )
        val highRisks = setOf("EXTERNAL_SIDE_EFFECT", "FINANCIAL", "DESTRUCTIVE")
        require(listOf(
            projection.taskId, projection.projectId, projection.ownerCoreId,
            projection.idempotencyKey, projection.lastTransferId, projection.lastNonce
        ).all(identifier::matches) &&
            projection.ownerGeneration > 0 && projection.stateVersion >= 0 &&
            projection.updatedAtEpochMs > 0 &&
            projection.payloadSha256.matches(Regex("^[a-f0-9]{64}$")) &&
            projection.effectRisk in allowedRisks &&
            (projection.approvalRef == null || identifier.matches(projection.approvalRef)) &&
            (projection.effectRisk !in highRisks || projection.approvalRef != null)) {
            "OWNERSHIP_PROJECTION_VALUE_INVALID"
        }
    }

    private fun key(taskId: String, projectId: String) = "$projectId|$taskId"
}
