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
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AndroidCoreInstallationIdentityInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var identityFile: File
    private lateinit var keyAliasPrefix: String

    @Before
    fun createIsolatedIdentityState() {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        identityFile = context.noBackupFilesDir.resolve("glafira_test_identity_$nonce.properties")
        keyAliasPrefix = "glafira.test.core.installation.signing.$nonce."
    }

    @After
    fun removeIsolatedIdentityState() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = keyStore.aliases().toList().filter { it.startsWith(keyAliasPrefix) }
        aliases.forEach(keyStore::deleteEntry)
        identityFile.delete()
        File(identityFile.parentFile, identityFile.name + ".tmp").delete()
    }

    private fun store() = AndroidCoreInstallationIdentityStore(context, identityFile, keyAliasPrefix)


    @Test
    fun concurrentStoreInstancesObserveOneStableIdentity() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map {
                executor.submit(Callable {
                    store().loadOrCreate()
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
        val store = store()
        val identity = store.loadOrCreate()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("$keyAliasPrefix${identity.keyEpoch}")

        assertThrows(IllegalStateException::class.java) { store.loadOrCreate() }
        assertTrue(identityFile.exists())
    }

    @Test
    fun tamperedPublicKeyMetadataFailsClosed() {
        val store = store()
        store.loadOrCreate()
        val properties = Properties().apply {
            identityFile.inputStream().use(::load)
            setProperty("public_key", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP))
        }
        identityFile.outputStream().use { properties.store(it, "injected test corruption") }

        assertThrows(IllegalArgumentException::class.java) { store.loadOrCreate() }
    }

    @Test
    fun missingNonBackupMetadataCreatesANewIdentityInsteadOfReusingOrphanedKey() {
        val store = store()
        val previous = store.loadOrCreate()
        identityFile.delete()

        val restored = store().loadOrCreate()

        assertNotEquals(previous.coreId, restored.coreId)
        assertEquals(1L, restored.keyEpoch)
    }

    @Test
    fun identityIsStableSignsLocallyAndRotatesWithMonotonicEpoch() {
        val store = store()
        val first = store.loadOrCreate()
        val second = store().loadOrCreate()

        assertEquals(first, second)
        assertTrue(first.keyEpoch > 0)
        assertTrue(identityFile.exists())

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

        val persisted = store().loadOrCreate()
        assertEquals(rotated, persisted)
    }
}
