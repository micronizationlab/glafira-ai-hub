package com.micronizationlab.glafira

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AndroidCoreInstallationIdentityInstrumentedTest {

    @Before
    fun clearIdentityState() = resetIdentityState()

    @After
    fun removeIdentityState() = resetIdentityState()

    private fun resetIdentityState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (alias.startsWith(KEY_ALIAS_PREFIX)) keyStore.deleteEntry(alias)
        }
        context.noBackupFilesDir.resolve(IDENTITY_FILE).delete()
        context.noBackupFilesDir.resolve("$IDENTITY_FILE.tmp").delete()
    }


    @Test
    fun concurrentStoreInstancesObserveOneStableIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map {
                executor.submit(Callable {
                    AndroidCoreInstallationIdentityStore(context).loadOrCreate()
                })
            }
            val identities = futures.map { it.get() }
            assertTrue(identities.all { it == identities.first() })
        } finally {
            executor.shutdownNow()
        }
    }


    @Test
    fun missingKeystorePrivateKeyFailsClosedWithoutReplacingInstallationIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidCoreInstallationIdentityStore(context)
        val identity = store.loadOrCreate()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("$KEY_ALIAS_PREFIX${identity.keyEpoch}")

        assertThrows(IllegalStateException::class.java) { store.loadOrCreate() }
        assertTrue(context.noBackupFilesDir.resolve(IDENTITY_FILE).exists())
    }

    @Test
    fun tamperedPublicKeyMetadataFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidCoreInstallationIdentityStore(context)
        store.loadOrCreate()
        val identityFile = context.noBackupFilesDir.resolve(IDENTITY_FILE)
        val properties = Properties().apply {
            identityFile.inputStream().use(::load)
            setProperty("public_key", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP))
        }
        identityFile.outputStream().use { properties.store(it, "injected test corruption") }

        assertThrows(IllegalArgumentException::class.java) { store.loadOrCreate() }
    }

    @Test
    fun missingNonBackupMetadataCreatesANewIdentityInsteadOfReusingOrphanedKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidCoreInstallationIdentityStore(context)
        val previous = store.loadOrCreate()
        context.noBackupFilesDir.resolve(IDENTITY_FILE).delete()

        val restored = AndroidCoreInstallationIdentityStore(context).loadOrCreate()

        assertNotEquals(previous.coreId, restored.coreId)
        assertEquals(1L, restored.keyEpoch)
    }

    @Test
    fun identityIsStableSignsLocallyAndRotatesWithMonotonicEpoch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidCoreInstallationIdentityStore(context)
        val first = store.loadOrCreate()
        val second = AndroidCoreInstallationIdentityStore(context).loadOrCreate()

        assertEquals(first, second)
        assertTrue(first.keyEpoch > 0)
        assertTrue(context.noBackupFilesDir.resolve("glafira_core_installation_identity.properties").exists())

        val payload = "glafira-core-identity-test".toByteArray()
        val signature = store.sign(payload)
        val firstPublicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(first.publicKeyBase64, Base64.NO_WRAP))
        )
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(firstPublicKey)
        verifier.update(payload)
        assertTrue(verifier.verify(signature))

        val rotated = store.rotateKey()
        assertEquals(first.coreId, rotated.coreId)
        assertEquals(first.keyEpoch + 1, rotated.keyEpoch)
        assertNotEquals(first.publicKeyBase64, rotated.publicKeyBase64)

        val rotatedPublicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(rotated.publicKeyBase64, Base64.NO_WRAP))
        )
        verifier.initVerify(rotatedPublicKey)
        verifier.update(payload)
        assertFalse(verifier.verify(signature))

        val persisted = AndroidCoreInstallationIdentityStore(context).loadOrCreate()
        assertEquals(rotated, persisted)
    }
    companion object {
        private const val IDENTITY_FILE = "glafira_core_installation_identity.properties"
        private const val KEY_ALIAS_PREFIX = "glafira.core.installation.signing."
    }
}
