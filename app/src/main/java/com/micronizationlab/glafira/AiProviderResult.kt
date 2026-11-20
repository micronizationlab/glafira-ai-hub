package com.micronizationlab.glafira

/** Whether a provider request definitely did not run, was explicitly rejected, or may have run. */
enum class AiRequestOutcome { NOT_SENT, REJECTED, UNKNOWN }

enum class AiProviderErrorCategory {
    NOT_CONFIGURED, UNSUPPORTED, AUTHENTICATION, MODEL_UNAVAILABLE, RATE_LIMIT,
    NETWORK, TIMEOUT, TLS, PROVIDER, INVALID_RESPONSE
}

data class AiProviderError(
    val category: AiProviderErrorCategory,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    val outcome: AiRequestOutcome = AiRequestOutcome.UNKNOWN,
    val partialResults: Map<String, String> = emptyMap()
) {
    /** Unknown outcomes are never retried automatically; long/invalid Retry-After also fails closed. */
    val canRetryAutomatically: Boolean
        get() = outcome != AiRequestOutcome.UNKNOWN &&
            (retryAfterMillis == null || retryAfterMillis <= MAX_AUTOMATIC_RETRY_DELAY_MILLIS) &&
            category in setOf(AiProviderErrorCategory.NETWORK, AiProviderErrorCategory.TIMEOUT,
                AiProviderErrorCategory.RATE_LIMIT)

    companion object {
        const val MAX_AUTOMATIC_RETRY_DELAY_MILLIS = 15 * 60_000L
    }
}

sealed class AiProviderResult {
    data class Success(val text: String) : AiProviderResult()
    data class Failure(val error: AiProviderError) : AiProviderResult()
}

fun AiProviderResult.userText(): String = when (this) {
    is AiProviderResult.Success -> text
    is AiProviderResult.Failure -> buildString {
        append("Ошибка: ")
        append(error.message)
        if (error.outcome == AiRequestOutcome.UNKNOWN) {
            append(" Исход запроса неизвестен. Автоматический повтор отключён.")
        }
        if (error.partialResults.isNotEmpty()) {
            append("\n\nПолученные мнения (синтез не завершён):\n")
            append(error.partialResults.entries.joinToString("\n\n") { (provider, text) ->
                "--- $provider ---\n$text"
            })
        }
    }
}

internal fun providerHttpFailure(status: Int, retryAfter: String? = null,
    nowMillis: Long = System.currentTimeMillis()): AiProviderResult.Failure {
    val category = when (status) {
        401, 403 -> AiProviderErrorCategory.AUTHENTICATION
        404 -> AiProviderErrorCategory.MODEL_UNAVAILABLE
        429 -> AiProviderErrorCategory.RATE_LIMIT
        else -> AiProviderErrorCategory.PROVIDER
    }
    val outcome = if (status in 400..499 && status != 408)
        AiRequestOutcome.REJECTED else AiRequestOutcome.UNKNOWN
    return AiProviderResult.Failure(AiProviderError(
        category = category,
        message = "Провайдер вернул HTTP $status.",
        httpStatus = status,
        retryAfterMillis = parseProviderRetryAfter(retryAfter, nowMillis),
        outcome = outcome
    ))
}

/** A composite consultation cannot safely replay completed planner/peer calls without durable checkpoints. */
internal fun afterPartialProviderCompletion(result: AiProviderResult): AiProviderResult = when (result) {
    is AiProviderResult.Success -> result
    is AiProviderResult.Failure -> result.copy(error = result.error.copy(outcome = AiRequestOutcome.UNKNOWN))
}

internal fun parseProviderRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (value == null) return null
    val seconds = value.trim().toLongOrNull()
    if (seconds != null) return if (seconds < 0) Long.MAX_VALUE
        else seconds.coerceAtMost(Long.MAX_VALUE / 1000).coerceAtLeast(1) * 1000
    return runCatching {
        val deadline = java.time.ZonedDateTime.parse(value.trim(),
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (deadline - nowMillis).coerceAtLeast(1000)
    }.getOrDefault(Long.MAX_VALUE)
}

/** Conservative adapter for the legacy String provider API: network failures are UNKNOWN, never NOT_SENT. */
internal fun classifyLegacyProviderResponse(response: String): AiProviderResult {
    if (!response.startsWith("Ошибка")) return AiProviderResult.Success(response)
    if (response.contains("API-ключ") || response.contains("пока не реализовано") ||
        response.contains("не задан")) {
        return AiProviderResult.Failure(AiProviderError(
            category = if (response.contains("API-ключ") || response.contains("не задан"))
                AiProviderErrorCategory.NOT_CONFIGURED else AiProviderErrorCategory.UNSUPPORTED,
            message = response.removePrefix("Ошибка: ").take(500),
            outcome = AiRequestOutcome.NOT_SENT
        ))
    }
    val status = Regex("HTTP\\s+(\\d{3})").find(response)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (status != null) return providerHttpFailure(status).copy(
        error = providerHttpFailure(status).error.copy(message = response.take(500))
    )
    val category = when {
        response.contains("TLS", ignoreCase = true) || response.contains("сертификат", ignoreCase = true) -> AiProviderErrorCategory.TLS
        response.contains("timeout", ignoreCase = true) || response.contains("таймаут", ignoreCase = true) -> AiProviderErrorCategory.TIMEOUT
        response.contains("сеть", ignoreCase = true) || response.contains("соединен", ignoreCase = true) -> AiProviderErrorCategory.NETWORK
        else -> AiProviderErrorCategory.PROVIDER
    }
    return AiProviderResult.Failure(AiProviderError(
        category = category,
        message = response.removePrefix("Ошибка: ").take(500),
        outcome = AiRequestOutcome.UNKNOWN
    ))
}
