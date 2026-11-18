package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupArchiveTest {
    @Test
    fun sanitizesRestoredPeerProvidersAndExcludesDisallowedYandex() {
        assertEquals(
            "OPENAI,DEEPSEEK",
            BackupArchive.sanitizePeerProviderIds("OPENAI, YANDEX,DEEPSEEK,OPENAI,UNKNOWN")
        )
    }

    @Test
    fun removesUnknownPeerProviders() {
        assertEquals("", BackupArchive.sanitizePeerProviderIds("UNKNOWN,NOT_A_PROVIDER"))
    }
}
