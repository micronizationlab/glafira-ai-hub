package com.micronizationlab.glafira

/** Single source of truth for backup-safe preference export and restore. */
internal object BackupPreferencePolicy {
    fun isAllowedKey(key: String): Boolean =
        key == "glafira_primary_provider" ||
            key == "peer_consultation_enabled" ||
            key.startsWith("peer_consultation_enabled_") ||
            key == "peer_consultation_providers" ||
            key.startsWith("peer_consultation_providers_")

    fun isStringKey(key: String): Boolean =
        key == "glafira_primary_provider" ||
            key == "peer_consultation_providers" ||
            key.startsWith("peer_consultation_providers_")

    fun isBooleanKey(key: String): Boolean =
        key == "peer_consultation_enabled" ||
            key.startsWith("peer_consultation_enabled_")

    fun isAllowedValue(key: String, value: Any?): Boolean =
        (isStringKey(key) && value is String) ||
            (isBooleanKey(key) && value is Boolean)
}
