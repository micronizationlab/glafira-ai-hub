package com.micronizationlab.glafira

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Platform-neutral pairing ticket primitives.
 *
 * This component verifies a one-time enrollment proof only. A successful proof is NOT
 * device approval and does NOT create credentials or grant scopes. The caller must put
 * the verified request into an owner-visible approval flow before issuing device keys.
 */
data class GlafiraBridgePairingTicket(
    val ticketId: String,
    val verifierKey: ByteArray,
    val expiresAtEpochMs: Long
)

interface GlafiraBridgePairingTicketStore {
    fun create(ticket: GlafiraBridgePairingTicket): Boolean
    fun find(ticketId: String): GlafiraBridgePairingTicket?

    /** Best-effort bounded cleanup of expired invitation records. */
    fun pruneExpired(nowEpochMs: Long): Int = 0

    /** Atomically remove the matching, unexpired ticket. Must fail if already consumed. */
    fun consume(
        ticketId: String,
        expectedVerifierKey: ByteArray,
        nowEpochMs: Long
    ): Boolean
}

data class GlafiraBridgePairingInvitation(
    val ticketId: String,
    /** Display once to the owner; never log or persist this value in plaintext. */
    val pairingCapability: String,
    val expiresAtEpochMs: Long
)

data class GlafiraBridgeVerifiedEnrollment(
    val ticketId: String,
    val clientNonce: String,
    val verifiedAtEpochMs: Long
)

enum class GlafiraBridgePairingError {
    INVALID_INPUT,
    TICKET_NOT_FOUND,
    TICKET_EXPIRED,
    INVALID_PROOF,
    TICKET_ALREADY_USED,
    STORAGE_ERROR
}

sealed class GlafiraBridgePairingResult {
    data class Verified(val enrollment: GlafiraBridgeVerifiedEnrollment) :
        GlafiraBridgePairingResult()

    data class Rejected(val error: GlafiraBridgePairingError) :
        GlafiraBridgePairingResult()
}

class GlafiraBridgePairingProtocol(
    private val ticketStore: GlafiraBridgePairingTicketStore,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun issueInvitation(
        nowEpochMs: Long = System.currentTimeMillis(),
        lifetimeMs: Long = DEFAULT_TICKET_LIFETIME_MS
    ): GlafiraBridgePairingInvitation {
        require(nowEpochMs > 0L)
        require(lifetimeMs in 1L..MAX_TICKET_LIFETIME_MS)

        val ticketIdBytes = ByteArray(TICKET_ID_BYTES).also(secureRandom::nextBytes)
        val capabilityBytes = ByteArray(CAPABILITY_BYTES).also(secureRandom::nextBytes)
        var verifierKey: ByteArray? = null
        try {
            val ticketId = ticketIdBytes.encodeUrl()
            val capability = capabilityBytes.encodeUrl()
            val expiresAt = Math.addExact(nowEpochMs, lifetimeMs)
            val verifier = sha256(capabilityBytes)
            verifierKey = verifier

            val stored = runCatching {
                ticketStore.pruneExpired(nowEpochMs)
                ticketStore.create(
                    GlafiraBridgePairingTicket(
                        ticketId = ticketId,
                        verifierKey = verifier,
                        expiresAtEpochMs = expiresAt
                    )
                )
            }.getOrDefault(false)
            check(stored) { "Unable to persist one-time pairing ticket." }
            return GlafiraBridgePairingInvitation(ticketId, capability, expiresAt)
        } finally {
            ticketIdBytes.fill(0)
            capabilityBytes.fill(0)
            verifierKey?.fill(0)
        }
    }

    fun verifyAndConsume(
        ticketId: String,
        clientNonce: String,
        proofHex: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraBridgePairingResult {
        if (!isIdentifier(ticketId) || !isNonce(clientNonce) || !isProof(proofHex) ||
            nowEpochMs <= 0L
        ) return rejected(GlafiraBridgePairingError.INVALID_INPUT)

        val ticket = runCatching { ticketStore.find(ticketId) }.getOrNull()
            ?: return rejected(GlafiraBridgePairingError.TICKET_NOT_FOUND)

        try {
            if (ticket.expiresAtEpochMs <= nowEpochMs) {
                return rejected(GlafiraBridgePairingError.TICKET_EXPIRED)
            }
            if (ticket.verifierKey.size != SHA256_BYTES) {
                return rejected(GlafiraBridgePairingError.STORAGE_ERROR)
            }

            val suppliedProof = runCatching { proofHex.hexToBytes() }.getOrNull()
                ?: return rejected(GlafiraBridgePairingError.INVALID_INPUT)
            val proofMatches = try {
                val canonical = canonicalProofMessage(ticketId, clientNonce)
                val expectedProof = try {
                    hmacSha256(ticket.verifierKey, canonical)
                } finally {
                    canonical.fill(0)
                }
                try {
                    MessageDigest.isEqual(expectedProof, suppliedProof)
                } finally {
                    expectedProof.fill(0)
                }
            } finally {
                suppliedProof.fill(0)
            }
            if (!proofMatches) return rejected(GlafiraBridgePairingError.INVALID_PROOF)

            val consumed = runCatching {
                ticketStore.consume(ticketId, ticket.verifierKey, nowEpochMs)
            }.getOrDefault(false)
            if (!consumed) return rejected(GlafiraBridgePairingError.TICKET_ALREADY_USED)

            return GlafiraBridgePairingResult.Verified(
                GlafiraBridgeVerifiedEnrollment(ticketId, clientNonce, nowEpochMs)
            )
        } finally {
            ticket.verifierKey.fill(0)
        }
    }

    companion object {
        const val DEFAULT_TICKET_LIFETIME_MS = 120_000L
        const val MAX_TICKET_LIFETIME_MS = 120_000L
        private const val TICKET_ID_BYTES = 16
        private const val CAPABILITY_BYTES = 32
        private const val NONCE_BYTES = 32
        private const val SHA256_BYTES = 32
        private const val PROOF_BYTES = 32

        /** Joining-client helper. The capability is never included in the proof payload. */
        fun createClientProof(
            ticketId: String,
            pairingCapability: String,
            clientNonce: String
        ): String {
            require(isIdentifier(ticketId) && isNonce(clientNonce))
            val capability = decodeUrl(pairingCapability)
            var verifierKey: ByteArray? = null
            var message: ByteArray? = null
            var proof: ByteArray? = null
            try {
                require(capability.size == CAPABILITY_BYTES)
                val verifier = sha256(capability)
                verifierKey = verifier
                val canonical = canonicalProofMessage(ticketId, clientNonce)
                message = canonical
                val proofBytes = hmacSha256(verifier, canonical)
                proof = proofBytes
                return proofBytes.toHex()
            } finally {
                capability.fill(0)
                verifierKey?.fill(0)
                message?.fill(0)
                proof?.fill(0)
            }
        }

        private fun canonicalProofMessage(ticketId: String, clientNonce: String): ByteArray =
            "GLAFIRA-BRIDGE-PAIRING-V1\u0000$ticketId\u0000$clientNonce"
                .toByteArray(Charsets.UTF_8)

        private fun sha256(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)

        private fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(value)
        }

        private fun ByteArray.encodeUrl(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(this)

        private fun decodeUrl(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value)

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        private fun String.hexToBytes(): ByteArray {
            require(length == PROOF_BYTES * 2 && all { it in "0123456789abcdefABCDEF" })
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun isIdentifier(value: String): Boolean =
            value.length in 16..128 && value.all {
                it.isLetterOrDigit() || it in "-_"
            }

        private fun isNonce(value: String): Boolean =
            runCatching { decodeUrl(value).size == NONCE_BYTES }.getOrDefault(false) &&
                value.all { it.isLetterOrDigit() || it in "-_" }

        private fun isProof(value: String): Boolean =
            value.length == PROOF_BYTES * 2 &&
                value.all { it in "0123456789abcdefABCDEF" }

        private fun rejected(error: GlafiraBridgePairingError) =
            GlafiraBridgePairingResult.Rejected(error)
    }
}
