package com.micronizationlab.glafira

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreOwnershipTransferIdentityInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transferSignatureUsesCurrentInstallationCoreAndKeystoreEpoch() {
        val identityStore = AndroidCoreInstallationIdentityStore(context)
        val identity = identityStore.loadOrCreate()
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(android.util.Base64.decode(identity.publicKeyBase64, android.util.Base64.NO_WRAP))
        )
        val payload = GlafiraOwnershipTransferSigningPayload(
            schemaVersion = 1,
            transferId = "transfer-android-identity-01",
            nonce = "nonce-android-identity-01",
            taskId = "identity-task-01",
            projectId = "identity-project-01",
            oldOwnerCoreId = identity.coreId,
            newOwnerCoreId = "desktop-core-0001",
            oldOwnerGeneration = 1,
            newOwnerGeneration = 2,
            stateVersion = 0,
            payloadSha256 = "a".repeat(64),
            idempotencyKey = "identity-idempotency-01",
            effectRisk = "READ_ONLY",
            approvalRef = null,
            issuedAt = "2026-11-21T12:00:00.000Z",
            expiresAt = "2026-11-21T12:05:00.000Z",
            signingKeyEpoch = identity.keyEpoch,
            signingPublicKeySha256 = GlafiraCoreOwnershipTransferCryptography.fingerprint(publicKey)
        )

        val signature = GlafiraCoreOwnershipTransferCryptography.signWithInstallationIdentity(
            identityStore, payload
        )

        assertEquals(64, signature.size)
        assertTrue(GlafiraCoreOwnershipTransferCryptography.verify(payload, publicKey, signature))
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreOwnershipTransferCryptography.signWithInstallationIdentity(
                identityStore,
                payload.copy(oldOwnerCoreId = "different-core-0001")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreOwnershipTransferCryptography.signWithInstallationIdentity(
                identityStore,
                payload.copy(signingKeyEpoch = identity.keyEpoch + 1)
            )
        }
    }
}
