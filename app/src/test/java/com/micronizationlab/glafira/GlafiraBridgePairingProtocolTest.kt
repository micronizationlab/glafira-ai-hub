package com.micronizationlab.glafira

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgePairingProtocolTest {
    private class FakeTicketStore : GlafiraBridgePairingTicketStore {
        private val tickets = mutableMapOf<String, GlafiraBridgePairingTicket>()

        @Synchronized
        override fun create(ticket: GlafiraBridgePairingTicket): Boolean {
            if (tickets.containsKey(ticket.ticketId)) return false
            tickets[ticket.ticketId] = ticket.copy(verifierKey = ticket.verifierKey.copyOf())
            return true
        }

        @Synchronized
        override fun find(ticketId: String): GlafiraBridgePairingTicket? =
            tickets[ticketId]?.copy(verifierKey = tickets[ticketId]!!.verifierKey.copyOf())

        @Synchronized
        override fun consume(
            ticketId: String,
            expectedVerifierKey: ByteArray,
            nowEpochMs: Long
        ): Boolean {
            val ticket = tickets[ticketId] ?: return false
            if (ticket.expiresAtEpochMs <= nowEpochMs ||
                !ticket.verifierKey.contentEquals(expectedVerifierKey)
            ) return false
            tickets.remove(ticketId)
            return true
        }
    }

    private class DeterministicRandom : SecureRandom() {
        private var next = 1
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = (next++ and 0xff).toByte() }
        }
    }

    private val now = 5_000_000L

    private fun nonce(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 7).toByte() })

    @Test
    fun invitationHas256BitCapabilityAndMaximum120SecondLifetime() {
        val protocol = GlafiraBridgePairingProtocol(FakeTicketStore(), DeterministicRandom())
        val invitation = protocol.issueInvitation(now, 120_000L)
        val capability = Base64.getUrlDecoder().decode(invitation.pairingCapability)
        assertEquals(32, capability.size)
        assertEquals(now + 120_000L, invitation.expiresAtEpochMs)
    }

    @Test
    fun validProofIsAcceptedOnceAndDoesNotIssueDeviceCredential() {
        val store = FakeTicketStore()
        val protocol = GlafiraBridgePairingProtocol(store, DeterministicRandom())
        val invitation = protocol.issueInvitation(now)
        val nonce = nonce()
        val proof = GlafiraBridgePairingProtocol.createClientProof(
            invitation.ticketId, invitation.pairingCapability, nonce
        )

        val first = protocol.verifyAndConsume(invitation.ticketId, nonce, proof, now + 1L)
        assertTrue(first is GlafiraBridgePairingResult.Verified)
        val second = protocol.verifyAndConsume(invitation.ticketId, nonce, proof, now + 2L)
        assertEquals(
            GlafiraBridgePairingError.TICKET_NOT_FOUND,
            (second as GlafiraBridgePairingResult.Rejected).error
        )
    }

    @Test
    fun alteredProofIsRejectedWithoutConsumingTicket() {
        val store = FakeTicketStore()
        val protocol = GlafiraBridgePairingProtocol(store, DeterministicRandom())
        val invitation = protocol.issueInvitation(now)
        val nonce = nonce()
        val proof = GlafiraBridgePairingProtocol.createClientProof(
            invitation.ticketId, invitation.pairingCapability, nonce
        )
        val altered = (if (proof[0] == '0') "1" else "0") + proof.drop(1)

        val denied = protocol.verifyAndConsume(invitation.ticketId, nonce, altered, now + 1L)
        assertEquals(
            GlafiraBridgePairingError.INVALID_PROOF,
            (denied as GlafiraBridgePairingResult.Rejected).error
        )
        val accepted = protocol.verifyAndConsume(invitation.ticketId, nonce, proof, now + 2L)
        assertTrue(accepted is GlafiraBridgePairingResult.Verified)
    }

    @Test
    fun expiredTicketIsRejected() {
        val protocol = GlafiraBridgePairingProtocol(FakeTicketStore(), DeterministicRandom())
        val invitation = protocol.issueInvitation(now)
        val nonce = nonce()
        val proof = GlafiraBridgePairingProtocol.createClientProof(
            invitation.ticketId, invitation.pairingCapability, nonce
        )
        val result = protocol.verifyAndConsume(
            invitation.ticketId, nonce, proof, invitation.expiresAtEpochMs
        )
        assertEquals(
            GlafiraBridgePairingError.TICKET_EXPIRED,
            (result as GlafiraBridgePairingResult.Rejected).error
        )
    }

    @Test
    fun capabilityCannotBeReusedForAnotherTicketOrNonce() {
        val protocol = GlafiraBridgePairingProtocol(FakeTicketStore(), DeterministicRandom())
        val first = protocol.issueInvitation(now)
        val second = protocol.issueInvitation(now)
        val proofForFirst = GlafiraBridgePairingProtocol.createClientProof(
            first.ticketId, first.pairingCapability, nonce()
        )
        val result = protocol.verifyAndConsume(
            second.ticketId, nonce(), proofForFirst, now + 1L
        )
        assertEquals(
            GlafiraBridgePairingError.INVALID_PROOF,
            (result as GlafiraBridgePairingResult.Rejected).error
        )
    }

    @Test
    fun ticketLifetimeCannotExceed120Seconds() {
        val protocol = GlafiraBridgePairingProtocol(FakeTicketStore())
        val rejected = runCatching {
            protocol.issueInvitation(now, 120_001L)
        }
        assertTrue(rejected.isFailure)
    }
}
