package com.micronizationlab.glafira

/** Resolves the user-entered backup passphrase or the already protected local copy. */
internal object BackupManualPassphrase {
    fun resolve(entered: String, stored: String): String =
        entered.takeIf { it.length >= MINIMUM_LENGTH }
            ?: stored.takeIf { it.length >= MINIMUM_LENGTH }
            ?: ""

    const val MINIMUM_LENGTH = 12
}
