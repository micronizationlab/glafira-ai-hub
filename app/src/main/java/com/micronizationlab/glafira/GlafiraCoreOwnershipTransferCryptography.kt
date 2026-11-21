package com.micronizationlab.glafira

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.time.Instant

/** Signature-covered fields of Dual-Core Ownership Transfer v1; signature is excluded. */
data class GlafiraOwnershipTransferSigningPayload(
    val schemaVersion: Int,
    val transferId: String,
    val nonce: String,
    val taskId: String,
    val projectId: String,
    val oldOwnerCoreId: String,
    val newOwnerCoreId: String,
    val oldOwnerGeneration: Long,
    val newOwnerGeneration: Long,
    val stateVersion: Long,
    val payloadSha256: String,
    val idempotencyKey: String,
    val effectRisk: String,
    val approvalRef: String?,
    val issuedAt: String,
    val expiresAt: String,
    val signingKeyEpoch: Long,
    val signingPublicKeySha256: String,
    val signatureAlgorithm: String = ALGORITHM,
    val signatureFormat: String = SIGNATURE_FORMAT
)

/**
 * Android implementation of the shared ownership-transfer v1 canonicalization and
 * ECDSA format. Java's SHA256withECDSA provider returns ASN.1 DER; the wire contract
 * requires IEEE P1363 fixed-width r||s, so conversion is explicit in both directions.
 */
internal object GlafiraCoreOwnershipTransferCryptography {
    const val ALGORITHM = "ECDSA_P256_SHA256"
    const val SIGNATURE_FORMAT = "IEEE_P1363_FIXED_64"

    private val identifier = Regex("^[A-Za-z0-9._-]{8,128}$")
    private val sha256 = Regex("^[a-f0-9]{64}$")
    private val highRisk = setOf("EXTERNAL_SIDE_EFFECT", "FINANCIAL", "DESTRUCTIVE")
    private val allowedRisks = highRisk + setOf("READ_ONLY", "DRAFT")

    fun canonicalBytes(payload: GlafiraOwnershipTransferSigningPayload): ByteArray {
        validate(payload)
        val values = listOf(
            payload.schemaVersion.toString(),
            payload.transferId,
            payload.nonce,
            payload.taskId,
            payload.projectId,
            payload.oldOwnerCoreId,
            payload.newOwnerCoreId,
            payload.oldOwnerGeneration.toString(),
            payload.newOwnerGeneration.toString(),
            payload.stateVersion.toString(),
            payload.payloadSha256,
            payload.idempotencyKey,
            payload.effectRisk,
            payload.approvalRef ?: "null",
            payload.issuedAt,
            payload.expiresAt,
            payload.signingKeyEpoch.toString(),
            payload.signingPublicKeySha256,
            payload.signatureAlgorithm,
            payload.signatureFormat
        )
        return buildString {
            values.forEach { value -> append(value.length).append(':').append(value) }
        }.toByteArray(Charsets.UTF_8)
    }

    fun sign(
        payload: GlafiraOwnershipTransferSigningPayload,
        privateKey: PrivateKey,
        publicKey: PublicKey
    ): ByteArray {
        require(fingerprint(publicKey) == payload.signingPublicKeySha256) {
            "OWNERSHIP_SIGNING_KEY_FINGERPRINT_MISMATCH"
        }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(canonicalBytes(payload))
        return derToP1363(signer.sign())
    }

    fun verify(
        payload: GlafiraOwnershipTransferSigningPayload,
        publicKey: PublicKey,
        signatureP1363: ByteArray
    ): Boolean = try {
        if (signatureP1363.size != 64 || fingerprint(publicKey) != payload.signingPublicKeySha256) {
            return false
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonicalBytes(payload))
        verifier.verify(p1363ToDer(signatureP1363))
    } catch (_: Exception) {
        false
    }

    fun fingerprint(publicKey: PublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
            .joinToString("") { "%02x".format(it) }

    private fun validate(payload: GlafiraOwnershipTransferSigningPayload) {
        require(payload.schemaVersion == 1) { "OWNERSHIP_TRANSFER_SCHEMA_VERSION_UNSUPPORTED" }
        listOf(
            payload.transferId, payload.nonce, payload.taskId, payload.projectId,
            payload.oldOwnerCoreId, payload.newOwnerCoreId, payload.idempotencyKey
        ).forEach { require(identifier.matches(it)) { "OWNERSHIP_TRANSFER_IDENTIFIER_INVALID" } }
        require(payload.oldOwnerCoreId != payload.newOwnerCoreId) {
            "OWNERSHIP_TRANSFER_OWNER_MUST_CHANGE"
        }
        require(payload.oldOwnerGeneration > 0 && payload.oldOwnerGeneration < Long.MAX_VALUE &&
            payload.newOwnerGeneration == payload.oldOwnerGeneration + 1) {
            "OWNERSHIP_TRANSFER_GENERATION_NOT_NEXT"
        }
        require(payload.stateVersion >= 0 && payload.signingKeyEpoch >= 1) {
            "OWNERSHIP_TRANSFER_VERSION_INVALID"
        }
        require(sha256.matches(payload.payloadSha256) &&
            sha256.matches(payload.signingPublicKeySha256)) {
            "OWNERSHIP_TRANSFER_DIGEST_INVALID"
        }
        require(payload.signatureAlgorithm == ALGORITHM && payload.signatureFormat == SIGNATURE_FORMAT) {
            "OWNERSHIP_TRANSFER_SIGNATURE_FORMAT_UNSUPPORTED"
        }
        val issued = Instant.parse(payload.issuedAt)
        val expires = Instant.parse(payload.expiresAt)
        require(expires.isAfter(issued)) { "OWNERSHIP_TRANSFER_TIME_WINDOW_INVALID" }
        require(payload.effectRisk in allowedRisks) { "OWNERSHIP_TRANSFER_EFFECT_RISK_INVALID" }
        if (payload.effectRisk in highRisk) {
            require(payload.approvalRef != null && identifier.matches(payload.approvalRef)) {
                "OWNERSHIP_TRANSFER_APPROVAL_REQUIRED"
            }
        } else {
            require(payload.approvalRef == null || identifier.matches(payload.approvalRef)) {
                "OWNERSHIP_TRANSFER_APPROVAL_INVALID"
            }
        }
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 0
        require(readByte(der, offset++) == 0x30) { "ECDSA_DER_SEQUENCE_REQUIRED" }
        val sequenceLength = readLength(der, offset).also { offset = it.second }.first
        require(offset + sequenceLength == der.size) { "ECDSA_DER_SEQUENCE_LENGTH_INVALID" }
        val sequenceEnd = offset + sequenceLength
        val r = readDerInteger(der, offset).also { offset = it.second }.first
        val s = readDerInteger(der, offset).also { offset = it.second }.first
        require(offset == sequenceEnd) { "ECDSA_DER_TRAILING_DATA" }
        return ByteArray(64).also { output ->
            copyUnsignedInteger(r, output, 0)
            copyUnsignedInteger(s, output, 32)
        }
    }

    private fun p1363ToDer(signature: ByteArray): ByteArray {
        require(signature.size == 64) { "ECDSA_P1363_LENGTH_INVALID" }
        val r = encodeDerInteger(signature.copyOfRange(0, 32))
        val s = encodeDerInteger(signature.copyOfRange(32, 64))
        val contentLength = r.size + s.size
        require(contentLength < 128) { "ECDSA_DER_LENGTH_UNSUPPORTED" }
        return byteArrayOf(0x30, contentLength.toByte()) + r + s
    }

    private fun readDerInteger(input: ByteArray, start: Int): Pair<ByteArray, Int> {
        var offset = start
        require(readByte(input, offset++) == 0x02) { "ECDSA_DER_INTEGER_REQUIRED" }
        val (length, next) = readLength(input, offset)
        offset = next
        require(length in 1..33 && offset + length <= input.size) { "ECDSA_DER_INTEGER_LENGTH_INVALID" }
        val encoded = input.copyOfRange(offset, offset + length)
        require(encoded[0].toInt() and 0x80 == 0) { "ECDSA_DER_NEGATIVE_INTEGER" }
        if (encoded.size > 1 && encoded[0] == 0.toByte()) {
            require(encoded[1].toInt() and 0x80 != 0) { "ECDSA_DER_NON_MINIMAL_INTEGER" }
        }
        var first = 0
        while (first < encoded.lastIndex && encoded[first] == 0.toByte()) first++
        val unsigned = encoded.copyOfRange(first, encoded.size)
        require(unsigned.size <= 32) { "ECDSA_DER_INTEGER_TOO_LARGE" }
        return unsigned to (offset + length)
    }

    private fun readLength(input: ByteArray, start: Int): Pair<Int, Int> {
        val first = readByte(input, start)
        if (first < 0x80) return first to start + 1
        val count = first and 0x7f
        require(count in 1..2 && start + count < input.size) { "ECDSA_DER_LENGTH_INVALID" }
        require(input[start + 1] != 0.toByte()) { "ECDSA_DER_LENGTH_NON_MINIMAL" }
        var length = 0
        for (index in 1..count) length = (length shl 8) or readByte(input, start + index)
        require(length >= 0x80) { "ECDSA_DER_LENGTH_NON_MINIMAL" }
        return length to (start + count + 1)
    }

    private fun encodeDerInteger(unsignedFixed: ByteArray): ByteArray {
        var first = 0
        while (first < unsignedFixed.lastIndex && unsignedFixed[first] == 0.toByte()) first++
        val trimmed = unsignedFixed.copyOfRange(first, unsignedFixed.size)
        val needsSignByte = trimmed[0].toInt() and 0x80 != 0
        val length = trimmed.size + if (needsSignByte) 1 else 0
        return byteArrayOf(0x02, length.toByte()) +
            (if (needsSignByte) byteArrayOf(0) else byteArrayOf()) + trimmed
    }

    private fun copyUnsignedInteger(unsigned: ByteArray, output: ByteArray, offset: Int) {
        unsigned.copyInto(output, offset + 32 - unsigned.size)
    }

    private fun readByte(input: ByteArray, offset: Int): Int {
        require(offset in input.indices) { "ECDSA_DER_TRUNCATED" }
        return input[offset].toInt() and 0xff
    }
}
