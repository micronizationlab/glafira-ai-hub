package com.micronizationlab.glafira

/** Whether retrying can repeat a remote operation that may already have completed. */
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
    val canRetryAutomatically: Boolean get() = outcome != AiRequestOutcome.UNKNOWN &&
        (retryAfterMillis == null || retryAfterMillis <= 900_000L) &&
        category in setOf(AiProviderErrorCategory.NETWORK, AiProviderErrorCategory.TIMEOUT,
            AiProviderErrorCategory.RATE_LIMIT)
}

sealed class AiProviderResult {
    data class Success(val text: String) : AiProviderResult()
    data class Failure(val error: AiProviderError) : AiProviderResult()
}

fun AiProviderResult.userText(): String = when (this) {
    is AiProviderResult.Success -> text
    is AiProviderResult.Failure -> "Ошибка: ${error.message}" +
        if (error.outcome == AiRequestOutcome.UNKNOWN)
            " Исход запроса неизвестен. Автоматический повтор отключён." else ""
}.let { base ->
    if (this is AiProviderResult.Failure && error.partialResults.isNotEmpty()) base +
        "\n\nПолученные мнения (синтез не завершён):\n" +
        error.partialResults.entries.joinToString("\n\n") { (provider, text) -> "--- $provider ---\n$text" }
    else base
}

internal fun providerHttpFailure(status: Int, retryAfter: String? = null): AiProviderResult.Failure {
    val category = when (status) {
        401, 403 -> AiProviderErrorCategory.AUTHENTICATION
        404 -> AiProviderErrorCategory.MODEL_UNAVAILABLE
        429 -> AiProviderErrorCategory.RATE_LIMIT
        else -> AiProviderErrorCategory.PROVIDER
    }
    // 408/5xx may be generated after an upstream model has already processed the POST.
    val outcome = if (status in 400..499 && status != 408)
        AiRequestOutcome.REJECTED else AiRequestOutcome.UNKNOWN
    return AiProviderResult.Failure(AiProviderError(category, "Провайдер вернул HTTP $status.",
        status, parseProviderRetryAfter(retryAfter), outcome))
}

/** Without a durable stage checkpoint, retrying a composite operation repeats successful steps. */
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
