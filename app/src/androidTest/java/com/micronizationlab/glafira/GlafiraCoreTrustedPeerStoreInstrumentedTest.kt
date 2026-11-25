package com.micronizationlab.glafira

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreTrustedPeerStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseFile = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "peer-trust-test-${UUID.randomUUID()}.db"
    )
    private val localCoreId: String
        get() = AndroidCoreInstallationIdentityStore(context).loadOrCreate().coreId
    private var store: GlafiraCoreTrustedPeerStore? = null

    @After
    fun tearDown() {
        store?.close()
        listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"))
            .forEach { it.delete() }
    }

    @Test
    fun enrollmentRequiresExactFingerprintAndPersistsAcrossProcessStoreReopen() {
        val peer = candidate("peer-core-0001", 1)
        val result = store().enroll(peer, peer.claimedFingerprintSha256, ownerSession(), 10_000L)
        assertEquals(GlafiraPeerTrustMutation.ENROLLED, result)
        assertEquals(peer.coreId, store().resolveActive(peer.coreId, 1)?.coreId)
        assertEquals(peer.claimedFingerprintSha256, store().listActive().single().fingerprintSha256)

        store!!.close()
        store = GlafiraCoreTrustedPeerStore(context, databaseFile)
        val restored = store().resolveActive(peer.coreId, 1)
        assertNotNull(restored)
        assertEquals(peer.publicKeySpkiBase64, restored!!.publicKeySpkiBase64)
        assertEquals(peer.claimedFingerprintSha256, restored.fingerprintSha256)
    }

    @Test
    fun fingerprintMismatchDoesNotCreateTrustedPeer() {
        val peer = candidate("peer-core-0002", 1)
        assertEquals(
            GlafiraPeerTrustMutation.FINGERPRINT_MISMATCH,
            store().enroll(peer, "0".repeat(64), ownerSession(), 11_000L)
        )
        assertNull(store().resolveActive(peer.coreId, 1))
    }

    @Test
    fun oneBiometricSessionCanAuthorizeOnlyOneNewPeerMutation() {
        val session = ownerSession()
        val first = candidate("peer-core-0003", 1)
        val second = candidate("peer-core-0004", 1)
        assertEquals(
            GlafiraPeerTrustMutation.ENROLLED,
            store().enroll(first, first.claimedFingerprintSha256, session, 12_000L)
        )
        assertEquals(
            GlafiraPeerTrustMutation.OWNER_AUTHENTICATION_REQUIRED,
            store().enroll(second, second.claimedFingerprintSha256, session, 12_001L)
        )
        assertNull(store().resolveActive(second.coreId, 1))
    }

    @Test
    fun duplicateIsIdempotentButDifferentActiveKeyRequiresRevocation() {
        val first = candidate("peer-core-0005", 1)
        assertEquals(
            GlafiraPeerTrustMutation.ENROLLED,
            store().enroll(first, first.claimedFingerprintSha256, ownerSession(), 13_000L)
        )
        assertEquals(
            GlafiraPeerTrustMutation.ALREADY_TRUSTED,
            store().enroll(first, first.claimedFingerprintSha256, ownerSession(), 13_001L)
        )
        val replacement = candidate(first.coreId, 2)
        assertEquals(
            GlafiraPeerTrustMutation.ACTIVE_KEY_EXISTS,
            store().enroll(replacement, replacement.claimedFingerprintSha256, ownerSession(), 13_002L)
        )
        assertEquals(first.claimedFingerprintSha256, store().resolveActive(first.coreId, 1)?.fingerprintSha256)
        assertNull(store().resolveActive(first.coreId, 2))
    }

    @Test
    fun revocationRemovesTrustAndOnlyHigherEpochCanBeEnrolledAfterward() {
        val first = candidate("peer-core-0006", 1)
        assertEquals(
            GlafiraPeerTrustMutation.ENROLLED,
            store().enroll(first, first.claimedFingerprintSha256, ownerSession(), 14_000L)
        )
        assertEquals(
            GlafiraPeerTrustMutation.REVOKED,
            store().revoke(first.coreId, 1, ownerSession(), 14_001L)
        )
        assertNull(store().resolveActive(first.coreId, 1))
        assertEquals(
            GlafiraPeerTrustMutation.ALREADY_REVOKED,
            store().revoke(first.coreId, 1, ownerSession(), 14_002L)
        )

        val stale = candidate(first.coreId, 1)
        assertEquals(
            GlafiraPeerTrustMutation.KEY_EPOCH_NOT_MONOTONIC,
            store().enroll(stale, stale.claimedFingerprintSha256, ownerSession(), 14_003L)
        )
        val next = candidate(first.coreId, 2)
        assertEquals(
            GlafiraPeerTrustMutation.ENROLLED,
            store().enroll(next, next.claimedFingerprintSha256, ownerSession(), 14_004L)
        )
        assertNull(store().resolveActive(first.coreId, 1))
        assertEquals(next.claimedFingerprintSha256, store().resolveActive(first.coreId, 2)?.fingerprintSha256)
    }

    @Test
    fun rejectsSelfTrustMalformedKeysAndNonBackupableDatabaseLocations() {
        val self = candidate(localCoreId, 1)
        assertEquals(
            GlafiraPeerTrustMutation.INVALID_PEER_IDENTITY,
            store().enroll(self, self.claimedFingerprintSha256, ownerSession(), 15_000L)
        )
        val malformed = self.copy(
            coreId = "peer-core-0007",
            publicKeySpkiBase64 = "not-a-public-key",
            claimedFingerprintSha256 = "a".repeat(64)
        )
        assertEquals(
            GlafiraPeerTrustMutation.INVALID_PEER_IDENTITY,
            store().enroll(malformed, malformed.claimedFingerprintSha256, ownerSession(), 15_001L)
        )

        val outsideNoBackup = File(context.cacheDir, "peer-trust-outside-no-backup.db")
        val rejected = runCatching { GlafiraCoreTrustedPeerStore(context, outsideNoBackup) }.isFailure
        assertTrue(rejected)
        outsideNoBackup.delete()
    }


    @Test
    fun missingOrAlreadyRevokedPeerDoesNotConsumeFreshOwnerSession() {
        val session = ownerSession()
        assertEquals(
            GlafiraPeerTrustMutation.NOT_FOUND,
            store().revoke("peer-core-0008", 1, session, 16_000L)
        )
        val first = candidate("peer-core-0008", 1)
        assertEquals(
            GlafiraPeerTrustMutation.ENROLLED,
            store().enroll(first, first.claimedFingerprintSha256, session, 16_001L)
        )
        assertEquals(
            GlafiraPeerTrustMutation.REVOKED,
            store().revoke(first.coreId, 1, ownerSession(), 16_002L)
        )

        val retrySession = ownerSession()
        assertEquals(
            GlafiraPeerTrustMutation.ALREADY_REVOKED,
            store().revoke(first.coreId, 1, retrySession, 16_003L)
        )
        val nextEpoch = candidate(first.coreId, 2)
        assertEquals(
            GlafiraPeerTrustMutation.ENROLLED,
            store().enroll(nextEpoch, nextEpoch.claimedFingerprintSha256, retrySession, 16_004L)
        )
    }

    private fun store(): GlafiraCoreTrustedPeerStore =
        store ?: GlafiraCoreTrustedPeerStore(context, databaseFile).also { store = it }

    private fun candidate(coreId: String, epoch: Long): GlafiraPeerEnrollmentCandidate {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair: KeyPair = generator.generateKeyPair()
        val encoded = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        val fingerprint = GlafiraCoreOwnershipTransferCryptography.fingerprint(pair.public)
        return GlafiraPeerEnrollmentCandidate(coreId, epoch, encoded, fingerprint)
    }

    private fun ownerSession(): GlafiraCoreOwnerSession {
        val constructor = GlafiraCoreOwnerSession::class.java.getDeclaredConstructor(
            String::class.java,
            java.lang.Long.TYPE
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            UUID.randomUUID().toString(),
            SystemClock.elapsedRealtime() + 60_000L
        )
    }
}
