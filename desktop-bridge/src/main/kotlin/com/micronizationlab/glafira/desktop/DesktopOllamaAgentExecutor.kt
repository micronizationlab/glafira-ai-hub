package com.micronizationlab.glafira.desktop

import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONObject

/**
 * Optional local Ollama adapter. It is deliberately pinned to loopback; remote hosts,
 * arbitrary URLs, tools and shell execution are not supported by this adapter.
 */
internal class DesktopOllamaAgentExecutor(
    private val model: String,
    private val systemInstruction: String,
    private val endpoint: URI = URI("http://127.0.0.1:11434/api/generate"),
    private val timeout: Duration = Duration.ofMinutes(5)
) : DesktopAgentExecutor {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        require(model.matches(Regex("[A-Za-z0-9._:-]{1,100}")))
        require(endpoint.scheme == "http" && endpoint.port == 11434 &&
            endpoint.host == "127.0.0.1" && endpoint.rawUserInfo == null &&
            endpoint.rawQuery == null && endpoint.rawFragment == null &&
            endpoint.rawPath == "/api/generate")
        require(InetAddress.getByName(endpoint.host).isLoopbackAddress)
        require(systemInstruction.isNotBlank() && systemInstruction.length <= 2_000)
        require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofMinutes(10))
    }

    override fun execute(task: DesktopTaskEnvelope): String {
        val prompt = buildString {
            append(systemInstruction)
            append("\n\nЗадача: ")
            append(task.instruction)
        }
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("stream", false)
            put("options", JSONObject().put("num_ctx", 8192))
        }.toString()
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Local model inference was interrupted.", error)
        }
        if (response.statusCode() != 200) {
            throw IOException("Local model server returned HTTP ${response.statusCode()}.")
        }
        require(response.body().length <= MAX_RESPONSE_CHARS)
        val output = JSONObject(response.body()).optString("response").trim()
        require(output.isNotBlank() && output.length <= MAX_OUTPUT_CHARS) {
            "Local model returned an empty or oversized result."
        }
        return output
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 256_000
        private const val MAX_OUTPUT_CHARS = 12_000
    }
}

internal object DesktopLocalAgentRegistry {
    fun ollama(model: String): Map<String, DesktopAgentExecutor> = mapOf(
        "core.orchestration" to DesktopOllamaAgentExecutor(
            model, "Ты — координатор GLAFIRA. Составь краткий, проверяемый результат по заданию."
        ),
        "analysis.document" to DesktopOllamaAgentExecutor(
            model, "Ты — аналитик GLAFIRA. Анализируй факты, отделяй выводы от предположений."
        ),
        "code.build" to DesktopOllamaAgentExecutor(
            model, "Ты — программист GLAFIRA. Предлагай безопасные технические решения; не выполняй команды и не заявляй о тестах без их запуска."
        ),
        "web.research" to DesktopOllamaAgentExecutor(
            model, "Ты — исследователь GLAFIRA. Если источники не предоставлены, явно укажи, что веб-поиск не выполнялся."
        )
    )
}
