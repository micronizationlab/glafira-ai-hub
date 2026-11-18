package com.micronizationlab.glafira

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/**
 * First safe Web Access runtime: bounded, read-only public-page retrieval.
 *
 * Search providers and interactive browser automation are separate capabilities;
 * this class deliberately does not pretend that fetching one URL is web search.
 */
data class WebPage(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val text: String,
    val contentType: String
)

sealed class WebAccessResult {
    data class Success(val page: WebPage) : WebAccessResult()
    data class Failure(val reason: String) : WebAccessResult()
}

object GlafiraWebAccessPolicy {
    const val MAX_REDIRECTS = 5
    const val MAX_RESPONSE_BYTES = 1_000_000
    const val MAX_EXTRACTED_CHARS = 40_000
    private val allowedPorts = setOf(80, 443)

    fun validatePublicHttpUrl(raw: String): URI {
        require(raw.length <= 2_048) { "URL слишком длинный." }
        val uri = URI(raw.trim()).normalize()
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "Разрешены только публичные HTTP/HTTPS-ссылки."
        }
        require(uri.userInfo == null) { "URL с логином или паролем запрещён." }
        require(uri.host != null) { "В ссылке отсутствует корректный адрес сайта." }
        require(uri.port == -1 || uri.port in allowedPorts) { "Нестандартный порт запрещён." }
        require(uri.rawFragment == null || uri.rawFragment.length < 512) { "Некорректная ссылка." }
        val host = IDN.toASCII(uri.host).lowercase()
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "Локальные адреса недоступны."
        }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
            "Сайт разрешается в локальный или специальный IP-адрес; запрос заблокирован."
        }
        return URI(uri.scheme.lowercase(), null, host, uri.port, uri.rawPath.ifBlank { "/" },
            uri.rawQuery, null)
    }

    private fun isPublicAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress &&
            !address.isMulticastAddress

    fun extractTitle(html: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")
            ?.decodeBasicEntities()?.replace(Regex("\\s+"), " ")?.trim()
            ?.take(300).orEmpty()

    fun extractReadableText(source: String): String {
        val withoutNoise = source
            .replace(Regex("(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .decodeBasicEntities()
            .replace("\u00a0", " ")
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return withoutNoise.take(MAX_EXTRACTED_CHARS)
    }

    private fun String.decodeBasicEntities(): String = this
        .replace("&nbsp;", " ", true)
        .replace("&amp;", "&", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
}

object GlafiraWebPageReader {
    fun open(rawUrl: String): WebAccessResult {
        var current = rawUrl
        return try {
            repeat(GlafiraWebAccessPolicy.MAX_REDIRECTS + 1) { redirectCount ->
                val safeUri = GlafiraWebAccessPolicy.validatePublicHttpUrl(current)
                val connection = (URL(safeUri.toString()).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "GLAFIRA-AI-HUB-WebAccess/1.0")
                    setRequestProperty("Accept", "text/html,text/plain,application/xhtml+xml")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        require(redirectCount < GlafiraWebAccessPolicy.MAX_REDIRECTS) {
                            "Слишком много перенаправлений."
                        }
                        val location = connection.getHeaderField("Location")
                            ?: error("Сайт вернул перенаправление без адреса.")
                        current = safeUri.resolve(location).toString()
                        return@repeat
                    }
                    require(status in 200..299) { "Сайт ответил HTTP $status." }
                    val type = connection.contentType.orEmpty()
                    require(type.contains("text/html", true) ||
                        type.contains("text/plain", true) ||
                        type.contains("application/xhtml+xml", true)) {
                        "Поддерживаются только HTML- и текстовые страницы."
                    }
                    val length = connection.contentLengthLong
                    require(length < 0 || length <= GlafiraWebAccessPolicy.MAX_RESPONSE_BYTES) {
                        "Страница превышает допустимый размер."
                    }
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= GlafiraWebAccessPolicy.MAX_RESPONSE_BYTES) {
                                "Ответ сайта превышает лимит 1 МБ."
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val charsetName = Regex("(?i)charset=([A-Za-z0-9._-]+)")
                        .find(type)?.groupValues?.get(1)
                    val charset = runCatching { Charset.forName(charsetName ?: "UTF-8") }
                        .getOrDefault(Charsets.UTF_8)
                    val body = String(bytes, charset)
                    val html = type.contains("html", true)
                    return WebAccessResult.Success(
                        WebPage(
                            requestedUrl = rawUrl,
                            finalUrl = safeUri.toString(),
                            title = if (html) GlafiraWebAccessPolicy.extractTitle(body) else "",
                            text = if (html) GlafiraWebAccessPolicy.extractReadableText(body)
                                else body.take(GlafiraWebAccessPolicy.MAX_EXTRACTED_CHARS),
                            contentType = type
                        )
                    )
                } finally {
                    connection.disconnect()
                }
            }
            WebAccessResult.Failure("Не удалось завершить переход по ссылке.")
        } catch (error: Exception) {
            WebAccessResult.Failure(error.message ?: "Неизвестная ошибка чтения сайта.")
        }
    }
}
