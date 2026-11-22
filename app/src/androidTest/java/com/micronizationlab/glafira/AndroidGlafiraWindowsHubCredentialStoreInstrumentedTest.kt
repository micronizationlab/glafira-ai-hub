package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidGlafiraWindowsHubCredentialStoreInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_800_000_000_000L
    private lateinit var stateFile: File
    private lateinit var keyAlias: String

    @Before
    fun createIsolatedStore() {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        stateFile = File(context.noBackupFilesDir, "windows-hub-pairing-test-$nonce.enc")
        keyAlias = "glafira.windows.hub.pairing.test.$nonce"
    }

    @After
    fun removeIsolatedStore() {
        stateFile.delete()
        File(stateFile.path + ".bak").delete()
        File(stateFile.path + ".new").delete()
        File(stateFile.path + ".revoked").delete()
        File(stateFile.path + ".revoked.bak").delete()
        File(stateFile.path + ".revoked.new").delete()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    @Test
    fun credentialAndOwnershipAnchorPersistAsOneEncryptedState() {
        val pair = keyPair()
        val enrollment = enrollment(pair, 7)
        val store = store()

        try {
            assertEquals(
                GlafiraWindowsHubCredentialInstallResult.INSTALLED,
                store.install(enrollment)
            )
        } finally {
            enrollment.clearSecret()
        }

        val credential = requireNotNull(store().load(now))
        try {
            assertEquals("android-device-01", credential.deviceId)
            assertEquals("desktop-hub-01", credential.desktopHubId)
            assertEquals(setOf("ownership.read", "project.read"), credential.scopes)
            assertArrayEquals(ByteArray(32) { 0x5c }, credential.secretCopy())
        } finally {
            credential.clearSecret()
        }
        assertArrayEquals(
            pair.public.encoded,
            requireNotNull(store.resolve("desktop-core-01", 7, fingerprint(pair.public.encoded))).encoded
        )
        val raw = stateFile.readText()
        assertFalse(raw.contains("desktop-core-01"))
        assertFalse(raw.contains(Base64.getEncoder().encodeToString(ByteArray(32) { 0x5c })))
    }

    @Test
    fun refreshRotationAndRollbackRemainAtomic() {
        val first = keyPair()
        val rotated = keyPair()
        val store = store()
        installAndClear(store, enrollment(first, 7))

        assertEquals(
            GlafiraWindowsHubCredentialInstallResult.UNCHANGED,
            installAndClear(store, enrollment(first, 7))
        )
        assertEquals(
            GlafiraWindowsHubCredentialInstallResult.REFRESHED,
            installAndClear(store, enrollment(first, 7, 0x4d))
        )
        assertEquals(
            GlafiraWindowsHubCredentialInstallResult.ROTATED,
            installAndClear(store, enrollment(rotated, 8, 0x6e))
        )
        assertNull(store.resolve("desktop-core-01", 7, fingerprint(first.public.encoded)))
        assertArrayEquals(
            rotated.public.encoded,
            requireNotNull(
                store.resolve("desktop-core-01", 8, fingerprint(rotated.public.encoded))
            ).encoded
        )
        val rollback = enrollment(first, 7)
        try {
            assertThrows(IllegalArgumentException::class.java) { store.install(rollback) }
        } finally {
            rollback.clearSecret()
        }
        val credential = requireNotNull(store.load(now))
        try {
            assertArrayEquals(ByteArray(32) { 0x6e }, credential.secretCopy())
            assertEquals(8L, credential.ownershipAnchor.signingKeyEpoch)
        } finally {
            credential.clearSecret()
        }
    }

    @Test
    fun revocationRemovesCredentialTrustCiphertextAndWrappingKey() {
        val pair = keyPair()
        val store = store()
        installAndClear(store, enrollment(pair, 7))

        assertTrue(store.revoke("desktop-core-01"))
        assertEquals(GlafiraWindowsHubPairingStatus.REVOKED, store.state(now).status)
        assertNull(store.load(now))
        assertNull(store.resolve("desktop-core-01", 7, fingerprint(pair.public.encoded)))
        assertFalse(stateFile.exists())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias(keyAlias))

        installAndClear(store, enrollment(pair, 7))
        assertEquals(GlafiraWindowsHubPairingStatus.PAIRED, store.state(now).status)
        store.revokeAll()
        assertEquals(GlafiraWindowsHubPairingStatus.REVOKED, store.state(now).status)
        assertFalse(stateFile.exists())
        assertFalse(keyStore.containsAlias(keyAlias))
    }

    @Test
    fun ownerVisibleStateDistinguishesUnpairedExpiredAndLocalUnpair() {
        val pair = keyPair()
        val store = store()
        assertEquals(GlafiraWindowsHubPairingStatus.UNPAIRED, store.state(now).status)

        installAndClear(store, enrollment(pair, 7, expiresAtEpochMs = now + 500))
        val paired = store.state(now)
        assertEquals(GlafiraWindowsHubPairingStatus.PAIRED, paired.status)
        assertEquals("android-device-01", paired.deviceId)
        assertEquals("desktop-hub-01", paired.desktopHubId)
        assertEquals(now + 500, paired.expiresAtEpochMs)
        assertEquals(GlafiraWindowsHubPairingStatus.EXPIRED, store.state(now + 500).status)
        assertNull(store.load(now + 500))

        store.unpair()
        assertEquals(GlafiraWindowsHubPairingStatus.UNPAIRED, store.state(now).status)
        assertFalse(File(stateFile.path + ".revoked").exists())
    }

    private fun store() = AndroidGlafiraWindowsHubCredentialStore(context, stateFile, keyAlias)

    private fun installAndClear(
        store: AndroidGlafiraWindowsHubCredentialStore,
        enrollment: GlafiraPinnedPairingEnrollment
    ): GlafiraWindowsHubCredentialInstallResult = try {
        store.install(enrollment)
    } finally {
        enrollment.clearSecret()
    }

    private fun enrollment(
        pair: KeyPair,
        epoch: Long,
        secretByte: Int = 0x5c,
        expiresAtEpochMs: Long = now + 30 * 24 * 60 * 60 * 1_000L
    ): GlafiraPinnedPairingEnrollment {
        val invitation = GlafiraWindowsHubPairingTrustBinding.parseInvitation(
            invitation(pair, epoch).toString(),
            now
        )
        return GlafiraWindowsHubPairingTrustBinding.correlate(
            invitation,
            response(pair, epoch, secretByte, expiresAtEpochMs).toString(),
            now
        )
    }

    private fun invitation(pair: KeyPair, epoch: Long) = JSONObject().apply {
        put("version", 1)
        put("agentId", "android-device-01")
        put("endpoint", "https://192.168.1.20:8443/bridge/v1")
        put("tlsCertificateSha256", "a".repeat(64))
        put("ownerCoreId", "desktop-core-01")
        put("ownershipSigningKeyEpoch", epoch)
        put("ownershipSigningPublicKeySpkiBase64", Base64.getEncoder().encodeToString(pair.public.encoded))
        put("ownershipSigningPublicKeySha256", fingerprint(pair.public.encoded))
        put("ticketId", "0123456789abcdef0123456789abcdef")
        put("pairingCode", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x2a }))
        put("scopes", JSONArray(listOf("ownership.read", "project.read")))
        put("expiresAtEpochMs", now + 120_000)
    }

    private fun response(
        pair: KeyPair,
        epoch: Long,
        secretByte: Int,
        expiresAtEpochMs: Long
    ) = JSONObject().apply {
        put("version", 1)
        put("status", "ENROLLED")
        put("errorCode", JSONObject.NULL)
        put("agentId", "android-device-01")
        put("enrollmentId", "0123456789abcdef0123456789abcdef")
        put("desktopHubId", "desktop-hub-01")
        put("endpoint", "https://192.168.1.20:8443/bridge/v1")
        put("tlsCertificateSha256", "a".repeat(64))
        put("ownerCoreId", "desktop-core-01")
        put("ownershipSigningKeyEpoch", epoch)
        put("ownershipSigningPublicKeySpkiBase64", Base64.getEncoder().encodeToString(pair.public.encoded))
        put("ownershipSigningPublicKeySha256", fingerprint(pair.public.encoded))
        put("sharedSecretBase64", Base64.getEncoder().encodeToString(ByteArray(32) { secretByte.toByte() }))
        put("scopes", JSONArray(listOf("ownership.read", "project.read")))
        put("enrolledAtEpochMs", now - 1_000)
        put("expiresAtEpochMs", expiresAtEpochMs)
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun fingerprint(value: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }
}
