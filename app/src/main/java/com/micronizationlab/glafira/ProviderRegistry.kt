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
    if (id == ProviderId.YANDEX) return false
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
/** Provider model IDs are owner-configurable; this does not imply quota or free availability. */
fun loadProviderModel(context: Context, id: ProviderId, defaultModel: String): String =
    context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        .getString("provider_model_${id.name}", null)?.takeIf { validProviderModel(it) } ?: defaultModel

fun saveProviderModel(context: Context, id: ProviderId, model: String): Boolean {
    val value = model.trim()
    if (!validProviderModel(value)) return false
    return context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE).edit()
        .putString("provider_model_${id.name}", value).commit()
}

internal fun validProviderModel(value: String): Boolean =
    value.length in 1..160 && value.none { it.isISOControl() || it.isWhitespace() }

fun defaultProviderModel(id: ProviderId): String = when (id) {
    ProviderId.GIGACHAT -> "GigaChat-2"
    ProviderId.OPENAI -> "gpt-4o-mini"
    ProviderId.DEEPSEEK -> "deepseek-chat"
    ProviderId.GROK -> "grok-4.7"
    ProviderId.GEMINI -> "gemini-3.8-flash"
    ProviderId.OPENROUTER -> "openrouter/free"
    ProviderId.YANDEX -> ""
}
