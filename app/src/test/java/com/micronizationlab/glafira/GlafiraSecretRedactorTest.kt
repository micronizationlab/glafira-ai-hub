package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraSecretRedactorTest {
    @Test
    fun masksCommonProviderTokensAndPasswords() {
        val source = "sk-abcdefghijklmnopqrstuvwxyz Bearer abcdefghijklmnopqrstuvwxyz api_key=very-secret-key password=hunter2 пароль=секрет"
        val redacted = GlafiraSecretRedactor.redact(source)

        assertFalse(redacted.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(redacted.contains("very-secret-key"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("секрет"))
        assertTrue(redacted.contains("[СКРЫТО]"))
    }

    @Test
    fun masksContactDetailsBeforePeerSharing() {
        val source = "Свяжитесь с valery@example.com или +7 921 123-45-67"
        val redacted = GlafiraSecretRedactor.redact(source)

        assertFalse(redacted.contains("valery@example.com"))
        assertFalse(redacted.contains("+7 921 123-45-67"))
        assertTrue(redacted.contains("[СКРЫТО]"))
    }

    @Test
    fun leavesOrdinaryTextReadable() {
        val source = "Проверь себестоимость мешка одежды и подготовь план продаж."
        assertTrue(GlafiraSecretRedactor.redact(source).contains(source))
    }
}
