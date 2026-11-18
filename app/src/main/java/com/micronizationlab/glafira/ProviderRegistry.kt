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
