package com.micronizationlab.glafira

/** Single source of truth for backup-safe preference export and restore. */
internal object BackupPreferencePolicy {
    private val providerNames = ProviderId.values().map { it.name }.toSet()

    fun isAllowedKey(key: String): Boolean =
        key == "glafira_primary_provider" ||
            key == "peer_consultation_enabled" ||
            isProjectScopedKey(key, "peer_consultation_enabled_") ||
            key == "peer_consultation_providers" ||
            isProjectScopedKey(key, "peer_consultation_providers_")

    fun isStringKey(key: String): Boolean =
        key == "glafira_primary_provider" ||
            key == "peer_consultation_providers" ||
            isProjectScopedKey(key, "peer_consultation_providers_")

    fun isBooleanKey(key: String): Boolean =
        key == "peer_consultation_enabled" ||
            isProjectScopedKey(key, "peer_consultation_enabled_")

    fun isAllowedValue(key: String, value: Any?): Boolean = when {
        key == "glafira_primary_provider" ->
            value is String && value in providerNames
        key == "peer_consultation_providers" ||
            isProjectScopedKey(key, "peer_consultation_providers_") ->
            value is String && isProviderSelection(value)
        key == "peer_consultation_enabled" ||
            isProjectScopedKey(key, "peer_consultation_enabled_") ->
            value is Boolean
        else -> false
    }

    private fun isProviderSelection(value: String): Boolean {
        if (value.isEmpty()) return true
        val values = value.split(',')
        return values.size <= providerNames.size &&
            values.all { it in providerNames } &&
            values.distinct().size == values.size
    }

    private fun isProjectScopedKey(key: String, prefix: String): Boolean {
        if (!key.startsWith(prefix)) return false
        val projectId = key.removePrefix(prefix)
        return projectId.length in 1..128 &&
            projectId.all { it.isLetterOrDigit() || it in "-_." }
    }
}
