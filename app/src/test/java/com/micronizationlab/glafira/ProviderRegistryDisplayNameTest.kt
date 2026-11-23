package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRegistryDisplayNameTest {
    @Test
    fun openAiIsNamedAsProviderNotChatGptApplication() {
        assertEquals("OpenAI", ProviderId.OPENAI.displayName)
    }
}
