package com.micronizationlab.glafira

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Endpoint defaults are deliberately centralized so provider model IDs can be updated without UI changes. */
private data class CompatibleEndpoint(val url: String, val model: String)

private const val PROVIDER_CONNECT_TIMEOUT_MS = 20_000
private const val PROVIDER_READ_TIMEOUT_MS = 90_000
private const val MAX_PROVIDER_RESPONSE_BYTES = 1_048_576

/** Reads a bounded UTF-8 response so a provider cannot exhaust app memory. */
internal fun readBoundedProviderResponse(
    input: InputStream,
    maxBytes: Int = MAX_PROVIDER_RESPONSE_BYTES
): String {
    require(maxBytes > 0) { "Response limit must be positive." }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val single = input.read()
            if (single < 0) break
            if (total >= maxBytes) {
                throw IllegalStateException("Provider response exceeded the allowed size.")
            }
            output.write(single)
            total++
            continue
        }
        if (count > maxBytes - total) {
            throw IllegalStateException("Provider response exceeded the allowed size.")
        }
        output.write(buffer, 0, count)
        total += count
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}

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

    return withContext(Dispatchers.IO) {
        var connection: HttpsURLConnection? = null
        try {
            connection = (URL(endpoint.url).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = PROVIDER_CONNECT_TIMEOUT_MS
                readTimeout = PROVIDER_READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                if (providerId == ProviderId.OPENROUTER) {
                    setRequestProperty("X-Title", "GLAFIRA AI HUB")
                    setRequestProperty("HTTP-Referer", "https://github.com/micronizationlab/glafira-ai-hub")
                }
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

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = responseStream?.use { readBoundedProviderResponse(it) }.orEmpty()

            if (status in 200..299) {
                JSONObject(responseText)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .ifBlank { "Модель вернула пустой ответ." }
            } else {
                val details = try {
                    JSONObject(responseText).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val safeDetails = GlafiraSecretRedactor.redact(details).take(400)
                "Ошибка API ${providerId.displayName}: HTTP $status${if (safeDetails.isBlank()) "" else " — $safeDetails"}"
            }
        } catch (_: Exception) {
            "Ошибка соединения с ${providerId.displayName}. Проверьте сеть, настройки ключа и повторите попытку."
        } finally {
            connection?.disconnect()
        }
    }
}
