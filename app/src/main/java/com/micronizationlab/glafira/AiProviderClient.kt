package com.micronizationlab.glafira

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Endpoint defaults are deliberately centralized so provider model IDs can be updated without UI changes. */
private data class CompatibleEndpoint(val url: String, val model: String)

suspend fun callAiProvider(
    context: Context,
    providerId: ProviderId,
    history: List<Msg>,
    systemPrompt: String
): String {
    if (providerId == ProviderId.GIGACHAT) {
        return callGigaChatWithTrustedCert(context, history, systemPrompt)
    }

    val endpoint = when (providerId) {
        ProviderId.OPENAI -> CompatibleEndpoint("https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
        ProviderId.DEEPSEEK -> CompatibleEndpoint("https://api.deepseek.com/chat/completions", "deepseek-chat")
        ProviderId.GROK -> CompatibleEndpoint("https://api.x.ai/v1/chat/completions", "grok-4.7")
        ProviderId.GEMINI -> CompatibleEndpoint("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-3.8-flash")
        ProviderId.OPENROUTER -> CompatibleEndpoint("https://openrouter.ai/api/v1/chat/completions", "openrouter/free")
        ProviderId.GIGACHAT -> return callGigaChatWithTrustedCert(context, history, systemPrompt)
        ProviderId.YANDEX -> return "Ошибка: подключение YandexGPT пока не реализовано."
    }

    val apiKey = loadProviderKey(context, providerId)
    if (apiKey.isBlank()) {
        return "Ошибка: API-ключ ${providerId.displayName} не задан. Добавьте его в Настройках."
    }

    val messages = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        history.forEach { message ->
            put(JSONObject().apply {
                put("role", if (message.isUser) "user" else "assistant")
                put("content", message.text)
            })
        }
    }
    val body = JSONObject().apply {
        put("model", endpoint.model)
        put("messages", messages)
    }.toString()

    return withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            var connection: HttpsURLConnection? = null
            try {
                connection = (URL(endpoint.url).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = if (providerId == ProviderId.GROK) 180_000 else 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    if (providerId == ProviderId.OPENROUTER) {
                        setRequestProperty("X-Title", "GLAFIRA AI HUB")
                        setRequestProperty("HTTP-Referer", "https://github.com/micronizationlab/glafira-ai-hub")
                    }
                }

                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (status in 200..299) {
                    return@withContext JSONObject(responseText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content")
                        .ifBlank { "Модель вернула пустой ответ." }
                }

                // Retry once only for transient server/rate-limit responses.
                // Do not replay transport timeouts: the provider may already have processed the request.
                if (attempt == 0 && (status == 429 || status in 500..599)) {
                    val retryAfterSeconds = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.coerceIn(1L, 5L)
                    attempt++
                    connection.disconnect()
                    connection = null
                    delay((retryAfterSeconds ?: 2L) * 1_000L)
                    continue
                }

                val details = try {
                    JSONObject(responseText).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) {
                    ""
                }
                if (providerId == ProviderId.GEMINI && status == 404) {
                    return@withContext "Ошибка Gemini: модель ${endpoint.model} не найдена или недоступна для этого API-ключа (HTTP 404). Проверьте список моделей, доступных вашему ключу."
                }
                return@withContext "Ошибка API ${providerId.displayName}: HTTP $status${if (details.isBlank()) "" else " — $details"}"
            } catch (error: Exception) {
                return@withContext "Ошибка сети (${providerId.displayName}): ${error.message ?: "неизвестная ошибка"}"
            } finally {
                connection?.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        "Ошибка: запрос не выполнен."
    }
}
