package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferencePolicyTest {
    @Test
    fun exportsAndRestoresPrimaryProviderAndPeerProviderSelection() {
        assertTrue(BackupPreferencePolicy.isAllowedValue("glafira_primary_provider", "openai"))
        assertTrue(BackupPreferencePolicy.isAllowedValue("peer_consultation_providers", "gigachat"))
        assertTrue(BackupPreferencePolicy.isAllowedValue("peer_consultation_providers_project_a", "openai,gigachat"))
    }

    @Test
    fun exportsAndRestoresPeerConsultationToggles() {
        assertTrue(BackupPreferencePolicy.isAllowedValue("peer_consultation_enabled", true))
        assertTrue(BackupPreferencePolicy.isAllowedValue("peer_consultation_enabled_project_a", false))
    }

    @Test
    fun rejectsUnrelatedPreferences() {
        assertFalse(BackupPreferencePolicy.isAllowedValue("user_password", "secret"))
        assertFalse(BackupPreferencePolicy.isAllowedValue("api_key", "secret"))
        assertFalse(BackupPreferencePolicy.isAllowedValue("unknown_setting", true))
    }

    @Test
    fun rejectsWrongTypesForAllowedKeys() {
        assertFalse(BackupPreferencePolicy.isAllowedValue("glafira_primary_provider", true))
        assertFalse(BackupPreferencePolicy.isAllowedValue("peer_consultation_enabled", "true"))
        assertFalse(BackupPreferencePolicy.isAllowedValue("peer_consultation_providers", false))
    }
}
