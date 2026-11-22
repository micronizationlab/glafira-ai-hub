package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreMemoryPayloadProtectorInstrumentedTest {
    @Test
    fun providerGeneratedIvEncryptsAndRoundTripsWithRandomizedKeystoreKey() {
        val alias = "glafira.test.memory-payload.${UUID.randomUUID()}"
        val plaintext = "device-bound-memory".toByteArray()
        try {
            val protector = AndroidKeystoreMemoryPayloadProtector(alias)
            val memoryId = MemoryIdentifier("memory-keystore-test")

            val first = protector.protect(memoryId, plaintext)
            val second = protector.protect(memoryId, plaintext)

            assertNotEquals(first, second)
            assertArrayEquals(plaintext, protector.unprotect(memoryId, first))
            assertArrayEquals(plaintext, protector.unprotect(memoryId, second))
        } finally {
            plaintext.fill(0)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
