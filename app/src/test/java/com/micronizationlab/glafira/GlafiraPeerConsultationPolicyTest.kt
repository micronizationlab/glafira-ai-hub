package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraPeerConsultationPolicyTest {
    @Test fun peerPromptRedactsSecretsAndCarriesNoProjectContextMarkers() {
        val prompt = GlafiraPeerConsultationPolicy.buildPrompt(
            "Проверь запуск password=HiddenSecret и напиши риски"
        )

        assertTrue(prompt.contains("[СКРЫТО]"))
        assertFalse(prompt.contains("HiddenSecret"))
        assertFalse(prompt.contains("Разрешённый контекст документов"))
        assertFalse(prompt.contains("Разрешённый контекст других чатов"))
    }
}
