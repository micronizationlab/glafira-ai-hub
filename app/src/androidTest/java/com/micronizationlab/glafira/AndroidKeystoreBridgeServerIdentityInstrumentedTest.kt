package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreBridgeServerIdentityInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val host = BridgeServerHost.ipv4("10.0.2.15")

    @Before
    fun cleanIdentity() {
        deleteIdentity()
    }

    @After
    fun removeIdentity() {
        deleteIdentity()
    }

    @Test
    fun createsAndReusesNonExportableEcTlsIdentityWithIpSanAndServerAuth() {
        val store = AndroidKeystoreBridgeServerIdentityStore(context)

        val first = store.getOrCreate(host)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null)

        assertNotNull("Android Keystore must retain the generated private key.", privateKey)
        assertEquals("EC", privateKey.algorithm)
        assertNull("The Android Keystore private key must remain non-exportable.", privateKey.encoded)
        assertEquals("EC", first.certificate.publicKey.algorithm)
        assertEquals(-1, first.certificate.basicConstraints)
        assertTrue(first.certificate.keyUsage?.getOrNull(0) == true)
        assertTrue(first.certificate.extendedKeyUsage?.contains(SERVER_AUTH_OID) == true)
        assertTrue(
            first.certificate.subjectAlternativeNames.orEmpty().any { entry ->
                entry.getOrNull(0) == 7 && entry.getOrNull(1) == host.value
            }
        )
        // Verifying the self-signed certificate also proves SHA256withECDSA signing
        // completed successfully with the non-exportable Android Keystore key.
        first.certificate.verify(first.certificate.publicKey)

        val second = store.getOrCreate(host)
        assertEquals(first.certificateSha256, second.certificateSha256)
        assertEquals(first.certificate.serialNumber, second.certificate.serialNumber)
        assertNotNull(store.currentConnectionDetails())
        assertFalse(second.certificateSha256.isBlank())
    }

    private fun deleteIdentity() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    companion object {
        private const val KEY_ALIAS = "glafira.bridge.server.identity"
        private const val PREFS_NAME = "glafira_bridge_server_identity"
        private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    }
}
