package com.micronizationlab.glafira

import android.content.Context

enum class ProviderId(val prefKey: String, val displayName: String) {
    GIGACHAT("gigachat_api_key", "GigaChat"),
    OPENAI("openai_api_key", "ChatGPT"),
    DEEPSEEK("deepseek_api_key", "DeepSeek"),
    GROK("grok_api_key", "Grok"),
    YANDEX("yandex_api_key", "Алиса")
}

fun isProviderConfigured(context: Context, id: ProviderId): Boolean {
    if (id == ProviderId.GIGACHAT) {
        return loadApiKey(context).isNotBlank()
    }
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    return (prefs.getString(id.prefKey, "") ?: "").isNotBlank()
}

fun saveProviderKey(context: Context, id: ProviderId, key: String) {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString(id.prefKey, key.trim()).commit()
}

fun loadProviderKey(context: Context, id: ProviderId): String {
    if (id == ProviderId.GIGACHAT) return loadApiKey(context)
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    return prefs.getString(id.prefKey, "") ?: ""
}
