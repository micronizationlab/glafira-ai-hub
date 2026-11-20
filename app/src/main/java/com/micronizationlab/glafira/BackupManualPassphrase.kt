package com.micronizationlab.glafira

/** Resolves a typed passphrase or the previously protected local copy without silently overriding user input. */
internal object BackupManualPassphrase {
    fun resolve(entered: String, stored: String): String {
        if (entered.isNotBlank()) return entered.takeIf { it.length >= MINIMUM_LENGTH } ?: ""
        return stored.takeIf { it.isNotBlank() && it.length >= MINIMUM_LENGTH } ?: ""
    }

    const val MINIMUM_LENGTH = 12
}
