package com.micronizationlab.glafira

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class GlafiraCoreOwnershipTransferCryptographyTest {
    @Test
    fun canonicalBytesMatchSharedDualCoreOwnershipV1GoldenVector() {
        val payload = payload(publicKeyFingerprint = "b".repeat(64))
        val expected =
            "1:113:transfer-000112:nonce-00000111:task-00000112:project-000115:android-core-0115:desktop-core-011:71:82:4264:" +
            "a".repeat(64) + "11:idem-0000019:READ_ONLY4:null" +
            "24:2026-11-21T12:00:00.000Z24:2026-11-21T12:05:00.000Z1:364:" +
            "b".repeat(64) + "17:ECDSA_P256_SHA25619:IEEE_P1363_FIXED_64"

        assertArrayEquals(expected.toByteArray(Charsets.UTF_8),
            GlafiraCoreOwnershipTransferCryptography.canonicalBytes(payload))
    }

    @Test
    fun p256SignatureUsesFixed64ByteP1363AndVerifiesAcrossPayloadChanges() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val payload = payload(
            publicKeyFingerprint = GlafiraCoreOwnershipTransferCryptography.fingerprint(keyPair.public)
        )

        val signature = GlafiraCoreOwnershipTransferCryptography.sign(
            payload, keyPair.private, keyPair.public
        )

        assertEquals(64, signature.size)
        assertTrue(GlafiraCoreOwnershipTransferCryptography.verify(payload, keyPair.public, signature))
        assertFalse(GlafiraCoreOwnershipTransferCryptography.verify(
            payload.copy(stateVersion = payload.stateVersion + 1), keyPair.public, signature
        ))
        assertFalse(GlafiraCoreOwnershipTransferCryptography.verify(
            payload, keyPair.public, signature.copyOf(63)
        ))
        assertFalse(GlafiraCoreOwnershipTransferCryptography.verify(
            payload.copy(signingPublicKeySha256 = "0".repeat(64)), keyPair.public, signature
        ))
    }

    @Test
    fun ownershipGenerationAndHighRiskApprovalAreValidatedBeforeSigning() {
        val payload = payload(publicKeyFingerprint = "b".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreOwnershipTransferCryptography.canonicalBytes(
                payload.copy(newOwnerGeneration = payload.oldOwnerGeneration + 2)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreOwnershipTransferCryptography.canonicalBytes(
                payload.copy(newOwnerCoreId = payload.oldOwnerCoreId)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreOwnershipTransferCryptography.canonicalBytes(
                payload.copy(effectRisk = "FINANCIAL", approvalRef = null)
            )
        }
    }

    private fun payload(publicKeyFingerprint: String) = GlafiraOwnershipTransferSigningPayload(
        schemaVersion = 1,
        transferId = "transfer-0001",
        nonce = "nonce-000001",
        taskId = "task-000001",
        projectId = "project-0001",
        oldOwnerCoreId = "android-core-01",
        newOwnerCoreId = "desktop-core-01",
        oldOwnerGeneration = 7,
        newOwnerGeneration = 8,
        stateVersion = 42,
        payloadSha256 = "a".repeat(64),
        idempotencyKey = "idem-000001",
        effectRisk = "READ_ONLY",
        approvalRef = null,
        issuedAt = "2026-11-21T12:00:00.000Z",
        expiresAt = "2026-11-21T12:05:00.000Z",
        signingKeyEpoch = 3,
        signingPublicKeySha256 = publicKeyFingerprint
    )
}
