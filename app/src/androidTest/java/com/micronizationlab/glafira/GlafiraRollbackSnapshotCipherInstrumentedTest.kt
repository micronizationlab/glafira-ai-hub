package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.io.File

@RunWith(AndroidJUnit4::class)
class GlafiraRollbackSnapshotCipherInstrumentedTest {
    @Test
    fun rollbackSnapshotHidesPlaintextAndAuthenticatesCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "rollback-cipher-source.db")
        val encrypted = File(context.cacheDir, "rollback-cipher-snapshot.enc")
        val restored = File(context.cacheDir, "rollback-cipher-restored.db")
        val canary = "GLAFIRA_ROLLBACK_PLAINTEXT_CANARY_7F31".toByteArray()
        try {
            source.writeBytes(canary)
            GlafiraRollbackSnapshotCipher.encrypt(source, encrypted)
            assertFalse(encrypted.readBytes().toString(Charsets.ISO_8859_1)
                .contains(String(canary, Charsets.ISO_8859_1)))
            GlafiraRollbackSnapshotCipher.decrypt(encrypted, restored)
            assertArrayEquals(canary, restored.readBytes())

            val damaged = encrypted.readBytes()
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
            encrypted.writeBytes(damaged)
            assertThrows(Exception::class.java) {
                GlafiraRollbackSnapshotCipher.decrypt(encrypted, restored)
            }
            assertFalse(restored.exists())
        } finally {
            source.delete()
            encrypted.delete()
            restored.delete()
            GlafiraRollbackSnapshotCipher.deleteKey()
        }
    }
}
