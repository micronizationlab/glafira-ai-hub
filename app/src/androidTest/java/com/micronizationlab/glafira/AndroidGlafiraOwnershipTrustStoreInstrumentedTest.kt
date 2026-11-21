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
class AndroidGlafiraOwnershipTrustStoreInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_800_000_000_000L
    private lateinit var trustFile: File
    private lateinit var keyAlias: String

    @Before
    fun createIsolatedStore() {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        trustFile = File(context.noBackupFilesDir, "ownership-trust-test-$nonce.enc")
        keyAlias = "glafira.ownership.trust.test.$nonce"
    }

    @After
    fun removeIsolatedStore() {
        trustFile.delete()
        File(trustFile.path + ".bak").delete()
        File(trustFile.path + ".new").delete()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    @Test
    fun pairingBindingIsEncryptedPersistedAndResolvesExactIdentity() {
        val pair = keyPair()
        val binding = binding(pair, 7)
        val store = store()

        assertEquals(GlafiraOwnershipTrustInstallResult.INSTALLED, store.install(binding))
        assertEquals(GlafiraOwnershipTrustInstallResult.UNCHANGED, store.install(binding))
        assertArrayEquals(
            pair.public.encoded,
            requireNotNull(
                store().resolve("desktop-core-01", 7, fingerprint(pair.public.encoded))
            ).encoded
        )
        assertNull(store.resolve("desktop-core-01", 8, fingerprint(pair.public.encoded)))

        val raw = trustFile.readText()
        assertFalse(raw.contains("desktop-core-01"))
        assertFalse(raw.contains(Base64.getEncoder().encodeToString(pair.public.encoded)))
        assertThrows(IllegalArgumentException::class.java) {
            store.install(binding(pair, 8, listOf("project.read")))
        }
    }

    @Test
    fun higherEpochRotatesAndRollbackOrSameEpochConflictFailsClosed() {
        val first = keyPair()
        val rotated = keyPair()
        val conflicting = keyPair()
        val store = store()
        store.install(binding(first, 7))

        assertEquals(
            GlafiraOwnershipTrustInstallResult.ROTATED,
            store.install(binding(rotated, 8))
        )
        assertNull(store.resolve("desktop-core-01", 7, fingerprint(first.public.encoded)))
        assertArrayEquals(
            rotated.public.encoded,
            requireNotNull(
                store.resolve("desktop-core-01", 8, fingerprint(rotated.public.encoded))
            ).encoded
        )
        assertThrows(IllegalArgumentException::class.java) { store.install(binding(first, 7)) }
        assertThrows(IllegalArgumentException::class.java) { store.install(binding(conflicting, 8)) }
    }

    @Test
    fun revocationRemovesSelectedAnchorAndRevokeAllDestroysWrappingKey() {
        val pair = keyPair()
        val store = store()
        store.install(binding(pair, 7))

        assertTrue(store.revoke("desktop-core-01"))
        assertNull(store.resolve("desktop-core-01", 7, fingerprint(pair.public.encoded)))
        assertFalse(store.revoke("desktop-core-01"))
        store.install(binding(pair, 7))
        store.revokeAll()

        assertFalse(trustFile.exists())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias(keyAlias))
        assertNull(store().resolve("desktop-core-01", 7, fingerprint(pair.public.encoded)))
    }

    private fun store() = AndroidGlafiraOwnershipTrustStore(context, trustFile, keyAlias)

    private fun binding(
        pair: KeyPair,
        epoch: Long,
        scopes: List<String> = listOf("ownership.read", "project.read")
    ): GlafiraPinnedPairingTrustBinding =
        GlafiraWindowsHubPairingTrustBinding.parse(
            invitation(pair, epoch, scopes).toString(),
            response(pair, epoch, scopes).toString(),
            now
        )

    private fun invitation(pair: KeyPair, epoch: Long, scopes: List<String>) = JSONObject().apply {
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
        put("scopes", JSONArray(scopes))
        put("expiresAtEpochMs", now + 120_000)
    }

    private fun response(pair: KeyPair, epoch: Long, scopes: List<String>) = JSONObject().apply {
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
        put("sharedSecretBase64", Base64.getEncoder().encodeToString(ByteArray(32) { 0x5c }))
        put("scopes", JSONArray(scopes))
        put("enrolledAtEpochMs", now - 1_000)
        put("expiresAtEpochMs", now + 30 * 24 * 60 * 60 * 1_000L)
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun fingerprint(value: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }
}
