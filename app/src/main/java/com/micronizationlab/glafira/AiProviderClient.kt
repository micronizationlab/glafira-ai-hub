package com.micronizationlab.glafira

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.net.SocketTimeoutException
import java.util.UUID

/** Endpoint defaults are deliberately centralized so provider model IDs can be updated without UI changes. */
private data class CompatibleEndpoint(val url: String)

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
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
}

/** Compatibility API. Queue decisions must use the typed result API below. */
suspend fun callAiProvider(context: Context, providerId: ProviderId, history: List<Msg>,
    systemPrompt: String): String = callAiProviderResult(context, providerId, history, systemPrompt).userText()

suspend fun callAiProviderResult(context: Context, providerId: ProviderId, history: List<Msg>,
    systemPrompt: String): AiProviderResult {
    val runtime = GlafiraCoreRuntimeOwner.get(context)
    return when (val outcome = executeWithProviderRuntimeBudget(
        budget = runtime.runtimeBudget(),
        limiter = runtime.executionLimiter
    ) {
        callAiProviderResultWithinBudget(context, providerId, history, systemPrompt)
    }) {
        is GlafiraProviderBudgetOutcome.Completed -> outcome.value
        is GlafiraProviderBudgetOutcome.Rejected -> providerBudgetFailure(outcome.reason)
    }
}

private suspend fun callAiProviderResultWithinBudget(
    context: Context,
    providerId: ProviderId,
    history: List<Msg>,
    systemPrompt: String
): AiProviderResult {
    val modelId = loadProviderModel(context, providerId, defaultProviderModel(providerId))
    val result = if (providerId == ProviderId.YANDEX) {
        AiProviderResult.Failure(AiProviderError(
            AiProviderErrorCategory.UNSUPPORTED, "Подключение YandexGPT пока не реализовано.",
            outcome = AiRequestOutcome.NOT_SENT
        ))
    } else {
        val apiKey = loadProviderKey(context, providerId)
        if (apiKey.isBlank()) {
            AiProviderResult.Failure(AiProviderError(
                AiProviderErrorCategory.NOT_CONFIGURED,
                "API-ключ ${providerId.displayName} не задан.",
                outcome = AiRequestOutcome.NOT_SENT
            ))
        } else withContext(Dispatchers.IO) {
        var connection: HttpsURLConnection? = null
        var tokenConnection: HttpsURLConnection? = null
        var modelPostAttempted = false
        try {
            val endpoint = when (providerId) {
                ProviderId.OPENAI -> CompatibleEndpoint("https://api.openai.com/v1/chat/completions")
                ProviderId.DEEPSEEK -> CompatibleEndpoint("https://api.deepseek.com/chat/completions")
                ProviderId.GROK -> CompatibleEndpoint("https://api.x.ai/v1/chat/completions")
                ProviderId.GEMINI -> CompatibleEndpoint("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
                ProviderId.OPENROUTER -> CompatibleEndpoint("https://openrouter.ai/api/v1/chat/completions")
                ProviderId.GIGACHAT -> CompatibleEndpoint("https://api.giga.chat/v1/chat/completions")
                ProviderId.YANDEX -> error("Unsupported provider")
            }
            var bearer = apiKey
            val socketFactory = if (providerId == ProviderId.GIGACHAT) {
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                context.assets.open("russian_trusted_root_ca.crt").use {
                    keyStore.setCertificateEntry("russian_ca", CertificateFactory.getInstance("X.509").generateCertificate(it))
                }
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
                SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
            } else null
            if (providerId == ProviderId.GIGACHAT) {
                val token = providerConnection("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
                tokenConnection = token
                token.sslSocketFactory = socketFactory
                token.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                token.setRequestProperty("RqUID", UUID.randomUUID().toString())
                token.setRequestProperty("Authorization", "Basic $apiKey")
                token.outputStream.use { it.write("scope=GIGACHAT_API_PERS".toByteArray(Charsets.UTF_8)) }
                val status = token.responseCode
                if (status !in 200..299) {
                    val failure = providerHttpFailure(status, token.getHeaderField("Retry-After"))
                    return@withContext failure.copy(error = failure.error.copy(outcome = AiRequestOutcome.NOT_SENT))
                }
                bearer = JSONObject(token.inputStream.use { readBoundedProviderResponse(it) }).getString("access_token")
                require(bearer.isNotBlank()) { "Empty access token" }
            }
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                history.forEach { put(JSONObject().put("role", if (it.isUser) "user" else "assistant").put("content", it.text)) }
            }
            val body = JSONObject().put("model", modelId)
                .put("messages", messages).toString().toByteArray(Charsets.UTF_8)
            val chat = providerConnection(endpoint.url)
            connection = chat
            if (socketFactory != null) chat.sslSocketFactory = socketFactory
            chat.setRequestProperty("Authorization", "Bearer $bearer")
            if (providerId == ProviderId.OPENROUTER) {
                chat.setRequestProperty("X-Title", "GLAFIRA AI HUB")
                chat.setRequestProperty("HTTP-Referer", "https://github.com/micronizationlab/glafira-ai-hub")
            }
            chat.setFixedLengthStreamingMode(body.size)
            // TLS/DNS/connect failure here cannot have submitted a model request body.
            chat.connect()
            modelPostAttempted = true
            chat.outputStream.use { it.write(body) }
            val status = chat.responseCode
            if (status !in 200..299) return@withContext providerHttpFailure(status, chat.getHeaderField("Retry-After"))
            val response = chat.inputStream.use { readBoundedProviderResponse(it) }
            val text = JSONObject(response).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            if (text.isBlank()) AiProviderResult.Failure(AiProviderError(
                AiProviderErrorCategory.INVALID_RESPONSE, "Модель вернула пустой ответ."))
            else AiProviderResult.Success(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val category = when (failure) {
                is SSLException -> AiProviderErrorCategory.TLS
                is SocketTimeoutException -> AiProviderErrorCategory.TIMEOUT
                is java.io.IOException -> AiProviderErrorCategory.NETWORK
                else -> AiProviderErrorCategory.INVALID_RESPONSE
            }
            providerTransportFailure(
                category = category,
                modelPostAttempted = modelPostAttempted,
                message = "Не удалось получить ответ ${providerId.displayName}."
            )
        } finally {
            connection?.disconnect()
            tokenConnection?.disconnect()
        }
        }
    }
    runCatching {
        ProviderObservedStatusStore(context).record(
            classifyProviderObservation(providerId, modelId, result, System.currentTimeMillis())
        )
    }
    return result
}

private fun providerConnection(url: String): HttpsURLConnection =
    (URL(url).openConnection() as HttpsURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = PROVIDER_CONNECT_TIMEOUT_MS
        readTimeout = PROVIDER_READ_TIMEOUT_MS
        instanceFollowRedirects = false
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
    }
