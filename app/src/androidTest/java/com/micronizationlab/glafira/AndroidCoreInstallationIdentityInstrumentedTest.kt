package com.micronizationlab.glafira

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AndroidCoreInstallationIdentityInstrumentedTest {

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
}
