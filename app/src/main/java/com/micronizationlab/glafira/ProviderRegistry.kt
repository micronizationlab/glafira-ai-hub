package com.micronizationlab.glafira

import android.content.Context

enum class ProviderId(val prefKey: String, val displayName: String) {
    GIGACHAT("gigachat_api_key", "GigaChat"),
    OPENAI("openai_api_key", "ChatGPT"),
    DEEPSEEK("deepseek_api_key", "DeepSeek"),
    GROK("grok_api_key", "Grok"),
    YANDEX("yandex_api_key", "Алиса"),
    GEMINI("gemini_api_key", "Google Gemini"),
    OPENROUTER("openrouter_api_key", "OpenRouter")
}

fun isProviderConfigured(context: Context, id: ProviderId): Boolean {
    if (id == ProviderId.GIGACHAT) {
        return loadApiKey(context).isNotBlank()
    }
    return loadProviderKey(context, id).isNotBlank()
}

fun saveProviderKey(context: Context, id: ProviderId, key: String): Boolean =
    EncryptedApiKeyStore.save(context, id.prefKey, key.trim())

fun loadProviderKey(context: Context, id: ProviderId): String {
    if (id == ProviderId.GIGACHAT) return loadApiKey(context)
    return EncryptedApiKeyStore.load(context, id.prefKey)
}


private val glafiraSupportedProviders = listOf(
    ProviderId.GIGACHAT,
    ProviderId.OPENAI,
    ProviderId.DEEPSEEK,
    ProviderId.GROK,
    ProviderId.GEMINI,
    ProviderId.OPENROUTER
)

fun configuredGlafiraProviders(context: Context): List<ProviderId> =
    glafiraSupportedProviders.filter { isProviderConfigured(context, it) }

fun hasAnyGlafiraProvider(context: Context): Boolean =
    configuredGlafiraProviders(context).isNotEmpty()

fun loadGlafiraPrimaryProvider(context: Context): ProviderId {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    val saved = runCatching {
        ProviderId.valueOf(prefs.getString("glafira_primary_provider", "") ?: "")
    }.getOrNull()
    val configured = configuredGlafiraProviders(context)
    if (saved != null && saved in configured) return saved
    return when {
        ProviderId.GIGACHAT in configured -> ProviderId.GIGACHAT
        configured.isNotEmpty() -> configured.first()
        else -> ProviderId.GIGACHAT
    }
}

fun saveGlafiraPrimaryProvider(context: Context, provider: ProviderId): Boolean {
    if (provider !in configuredGlafiraProviders(context)) return false
    return context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        .edit().putString("glafira_primary_provider", provider.name).commit()
}
