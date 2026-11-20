package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupManualPassphraseTest {
    @Test fun prefersAnExplicitValidPassphrase() {
        assertEquals("new-passphrase-123", BackupManualPassphrase.resolve(
            "new-passphrase-123", "old-passphrase-123"
        ))
    }

    @Test fun reusesTheProtectedPassphraseWhenInputIsBlank() {
        assertEquals("stored-passphrase-123", BackupManualPassphrase.resolve(
            "", "stored-passphrase-123"
        ))
    }

    @Test fun fallsBackToStoredPassphraseWhenNewInputIsTooShort() {
        assertEquals("stored-passphrase-123", BackupManualPassphrase.resolve(
            "short", "stored-passphrase-123"
        ))
    }

    @Test fun rejectsWhenNeitherPassphraseMeetsTheMinimumLength() {
        assertEquals("", BackupManualPassphrase.resolve("short", "also-short"))
    }
}
