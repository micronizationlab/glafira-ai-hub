package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class BackupPreferencePolicyTest {
    @Test fun validModelsArePortableOnlyForExactKnownProviderKeys() {
        ProviderId.values().forEach { provider ->
            assertEquals("vendor/model-v2:free", BackupArchive.portablePreferenceValue(
                "provider_model_${provider.name}", "vendor/model-v2:free"))
        }
        listOf("provider_model_UNKNOWN", "provider_model_openai", "provider_model_OPENAI_api_key",
            "provider_model_OPENAI/secret", "openai_api_key", "oauth_token", "backup_recovery_passphrase").forEach { key ->
            assertNull("Unexpected credential/provider key: $key", BackupArchive.portablePreferenceValue(key, "secret"))
        }
    }

    @Test fun malformedModelValuesAndTypesAreNotPortable() {
        listOf<Any>("", "white space", "model\nvalue", "x".repeat(161), true, 123L).forEach { value ->
            assertNull(BackupArchive.portablePreferenceValue("provider_model_OPENAI", value))
        }
        assertNull(BackupArchive.portablePreferenceValue("provider_model_OPENAI", null))
    }

    @Test fun peerProviderSelectionAndEnabledSettingRetainTheirTypedValues() {
        val providers = listOf(ProviderId.OPENAI, ProviderId.DEEPSEEK).joinToString(",") { it.name }
        listOf("", "_acceptance").forEach { suffix ->
            assertEquals(providers, BackupArchive.portablePreferenceValue("peer_consultation_providers$suffix", providers))
            assertEquals(true, BackupArchive.portablePreferenceValue("peer_consultation_enabled$suffix", true))
            assertEquals(false, BackupArchive.portablePreferenceValue("peer_consultation_enabled$suffix", false))
            assertNull(BackupArchive.portablePreferenceValue("peer_consultation_enabled$suffix", "true"))
            assertNull(BackupArchive.portablePreferenceValue("peer_consultation_providers$suffix", 123))
        }
    }

    @Test fun primaryProviderMustBeAnActualEnumValue() {
        assertEquals(ProviderId.GEMINI.name, BackupArchive.portablePreferenceValue("glafira_primary_provider", ProviderId.GEMINI.name))
        assertNull(BackupArchive.portablePreferenceValue("glafira_primary_provider", "local"))
        assertNull(BackupArchive.portablePreferenceValue("glafira_primary_provider", true))
    }
}
