package com.micronizationlab.glafira

import android.content.Context

enum class ProviderObservedAvailability {
    AVAILABLE,
    NOT_CONFIGURED,
    UNSUPPORTED,
    AUTHENTICATION_FAILED,
    MODEL_UNAVAILABLE,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    PROVIDER_ERROR
}

enum class ProviderObservedRequestOutcome { SUCCEEDED, NOT_SENT, REJECTED, UNKNOWN }

/**
 * Result of an actual provider request for one exact provider/model pair.
 * This is diagnostic metadata only: it contains no credential, prompt, response, quota or price.
 */
data class ProviderObservedStatus(
    val providerId: ProviderId,
    val modelId: String,
    val availability: ProviderObservedAvailability,
    val requestOutcome: ProviderObservedRequestOutcome,
    val checkedAtMillis: Long,
    val httpStatus: Int? = null
)

internal fun classifyProviderObservation(
    providerId: ProviderId,
    modelId: String,
    result: AiProviderResult,
    checkedAtMillis: Long
): ProviderObservedStatus {
    require(checkedAtMillis > 0L)
    require(modelId.length <= 160 && modelId.none { it.isISOControl() })
    return when (result) {
        is AiProviderResult.Success -> ProviderObservedStatus(
            providerId, modelId, ProviderObservedAvailability.AVAILABLE,
            ProviderObservedRequestOutcome.SUCCEEDED, checkedAtMillis
        )
        is AiProviderResult.Failure -> ProviderObservedStatus(
            providerId = providerId,
            modelId = modelId,
            availability = when (result.error.category) {
                AiProviderErrorCategory.NOT_CONFIGURED -> ProviderObservedAvailability.NOT_CONFIGURED
                AiProviderErrorCategory.UNSUPPORTED -> ProviderObservedAvailability.UNSUPPORTED
                AiProviderErrorCategory.AUTHENTICATION -> ProviderObservedAvailability.AUTHENTICATION_FAILED
                AiProviderErrorCategory.MODEL_UNAVAILABLE -> ProviderObservedAvailability.MODEL_UNAVAILABLE
                AiProviderErrorCategory.RATE_LIMIT -> ProviderObservedAvailability.RATE_LIMITED
                AiProviderErrorCategory.NETWORK,
                AiProviderErrorCategory.TIMEOUT,
                AiProviderErrorCategory.TLS -> ProviderObservedAvailability.NETWORK_UNAVAILABLE
                AiProviderErrorCategory.PROVIDER,
                AiProviderErrorCategory.INVALID_RESPONSE,
                AiProviderErrorCategory.BUDGET -> ProviderObservedAvailability.PROVIDER_ERROR
            },
            requestOutcome = when (result.error.outcome) {
                AiRequestOutcome.NOT_SENT -> ProviderObservedRequestOutcome.NOT_SENT
                AiRequestOutcome.REJECTED -> ProviderObservedRequestOutcome.REJECTED
                AiRequestOutcome.UNKNOWN -> ProviderObservedRequestOutcome.UNKNOWN
            },
            checkedAtMillis = checkedAtMillis,
            httpStatus = result.error.httpStatus
        )
    }
}

internal fun providerObservedStatusLabel(
    status: ProviderObservedStatus,
    nowMillis: Long = System.currentTimeMillis()
): String {
    if (nowMillis < status.checkedAtMillis ||
        nowMillis - status.checkedAtMillis > MAX_PROVIDER_OBSERVATION_AGE_MILLIS
    ) return "Статус устарел; требуется новый фактический запрос к выбранной модели."
    return when (status.availability) {
        ProviderObservedAvailability.AVAILABLE -> "Выбранная модель ответила успешно."
        ProviderObservedAvailability.NOT_CONFIGURED -> "API-ключ не настроен; запрос не отправлялся."
        ProviderObservedAvailability.UNSUPPORTED -> "Этот провайдер пока не поддерживается."
        ProviderObservedAvailability.AUTHENTICATION_FAILED -> "Провайдер отклонил авторизацию."
        ProviderObservedAvailability.MODEL_UNAVAILABLE -> "ID модели неверен или модель недоступна."
        ProviderObservedAvailability.RATE_LIMITED -> "Провайдер ограничил частоту запросов."
        ProviderObservedAvailability.NETWORK_UNAVAILABLE -> "Последняя проверка завершилась сетевой, TLS- или timeout-ошибкой."
        ProviderObservedAvailability.PROVIDER_ERROR -> "Провайдер вернул ошибку или некорректный ответ."
    }
}

internal const val MAX_PROVIDER_OBSERVATION_AGE_MILLIS = 5L * 60L * 1000L

class ProviderObservedStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES, Context.MODE_PRIVATE
    )

    fun record(status: ProviderObservedStatus): Boolean {
        require(status.checkedAtMillis > 0L)
        require(status.modelId.length <= 160 && status.modelId.none { it.isISOControl() })
        val prefix = prefix(status.providerId)
        return preferences.edit()
            .putString(prefix + "model", status.modelId)
            .putString(prefix + "availability", status.availability.name)
            .putString(prefix + "outcome", status.requestOutcome.name)
            .putLong(prefix + "checked_at", status.checkedAtMillis)
            .putInt(prefix + "http_status", status.httpStatus ?: NO_HTTP_STATUS)
            .commit()
    }

    /** A status for a different model ID is never reused as evidence for the selected model. */
    fun load(providerId: ProviderId, modelId: String): ProviderObservedStatus? {
        val prefix = prefix(providerId)
        if (preferences.getString(prefix + "model", null) != modelId) return null
        val availabilityName = preferences.getString(prefix + "availability", null) ?: return null
        val availability = runCatching { ProviderObservedAvailability.valueOf(availabilityName) }
            .getOrNull() ?: return null
        val outcomeName = preferences.getString(prefix + "outcome", null) ?: return null
        val outcome = runCatching { ProviderObservedRequestOutcome.valueOf(outcomeName) }
            .getOrNull() ?: return null
        val checkedAt = preferences.getLong(prefix + "checked_at", 0L)
        if (checkedAt <= 0L) return null
        val httpStatus = preferences.getInt(prefix + "http_status", NO_HTTP_STATUS)
            .takeUnless { it == NO_HTTP_STATUS }
        if (httpStatus != null && httpStatus !in 100..599) return null
        return ProviderObservedStatus(
            providerId, modelId, availability, outcome, checkedAt, httpStatus
        )
    }

    private fun prefix(providerId: ProviderId) = "${providerId.name.lowercase()}_"

    private companion object {
        const val PREFERENCES = "glafira_provider_observed_status"
        const val NO_HTTP_STATUS = -1
    }
}
