package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GlafiraRollbackSnapshotCipherInstrumentedTest {
    @Test
    fun rollbackSnapshotHidesPlaintextAndAuthenticatesCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "rollback-cipher-source.db")
        val encrypted = File(context.cacheDir, "rollback-cipher-snapshot.enc")
        val restored = File(context.cacheDir, "rollback-cipher-restored.db")
        val cipher = GlafiraRollbackSnapshotCipher("glafira_test_cipher_" + UUID.randomUUID().toString().replace("-", ""))
        val canary = "GLAFIRA_ROLLBACK_PLAINTEXT_CANARY_7F31".toByteArray()
        try {
            source.writeBytes(canary)
            cipher.encrypt(source, encrypted)
            assertFalse(encrypted.readBytes().toString(Charsets.ISO_8859_1)
                .contains(String(canary, Charsets.ISO_8859_1)))
            cipher.decrypt(encrypted, restored)
            assertArrayEquals(canary, restored.readBytes())

            val damaged = encrypted.readBytes()
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
            encrypted.writeBytes(damaged)
            assertThrows(Exception::class.java) {
                cipher.decrypt(encrypted, restored)
            }
            assertFalse(restored.exists())
        } finally {
            source.delete()
            encrypted.delete()
            restored.delete()
            cipher.deleteKey()
        }
    }
    @Test
    fun decryptFailsClosedWhenRollbackKeyIsMissing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "rollback-missing-key-source.db")
        val encrypted = File(context.cacheDir, "rollback-missing-key-snapshot.enc")
        val restored = File(context.cacheDir, "rollback-missing-key-restored.db")
        val cipher = GlafiraRollbackSnapshotCipher("glafira_test_missing_" + UUID.randomUUID().toString().replace("-", ""))
        try {
            source.writeText("rollback-key-loss-canary")
            cipher.encrypt(source, encrypted)
            cipher.deleteKey()

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keyStore.containsAlias(cipher.keyAlias))

            val error = assertThrows(IllegalStateException::class.java) {
                cipher.decrypt(encrypted, restored)
            }
            org.junit.Assert.assertEquals("ROLLBACK_KEY_MISSING", error.message)
            val afterDecrypt = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(afterDecrypt.containsAlias(cipher.keyAlias))
            assertFalse(restored.exists())
        } finally {
            source.delete()
            encrypted.delete()
            restored.delete()
            cipher.deleteKey()
        }
    }

}
