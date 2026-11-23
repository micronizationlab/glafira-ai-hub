package com.micronizationlab.glafira

import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface GlafiraProviderBudgetOutcome<out T> {
    data class Completed<T>(val value: T) : GlafiraProviderBudgetOutcome<T>
    data class Rejected(val reason: String) : GlafiraProviderBudgetOutcome<Nothing>
}

/**
 * Applies the owner-controlled Core budget to direct provider calls as well.
 *
 * Monetary limits fail closed until a trusted adapter can report an actual cost. No token price
 * or provider tariff is inferred here. Provider and Core work share one concurrency limiter.
 */
internal suspend fun <T> executeWithProviderRuntimeBudget(
    budget: GlafiraRuntimeBudget,
    limiter: GlafiraCoreExecutionLimiter,
    timeoutMillis: Long = budget.maxWallClockMillis,
    block: suspend () -> T
): GlafiraProviderBudgetOutcome<T> {
    require(timeoutMillis > 0L) { "Provider timeout must be positive." }
    if (budget.maxCostMicros != null) {
        return GlafiraProviderBudgetOutcome.Rejected("RUNTIME_COST_UNMETERED")
    }
    return limiter.withPermit(budget.maxConcurrentTasks) {
        withTimeoutOrNull(timeoutMillis) {
            GlafiraProviderBudgetOutcome.Completed(block())
        } ?: GlafiraProviderBudgetOutcome.Rejected("RUNTIME_TIME_LIMIT_EXCEEDED")
    }
}

internal fun providerBudgetFailure(reason: String): AiProviderResult.Failure = when (reason) {
    "RUNTIME_COST_UNMETERED" -> AiProviderResult.Failure(AiProviderError(
        category = AiProviderErrorCategory.BUDGET,
        message = "Лимит расходов включён, но провайдер не сообщил проверяемую стоимость. Запрос не отправлен.",
        outcome = AiRequestOutcome.NOT_SENT
    ))
    "RUNTIME_TIME_LIMIT_EXCEEDED" -> AiProviderResult.Failure(AiProviderError(
        category = AiProviderErrorCategory.TIMEOUT,
        message = "Запрос остановлен: исчерпан установленный владельцем лимит времени.",
        outcome = AiRequestOutcome.UNKNOWN
    ))
    else -> AiProviderResult.Failure(AiProviderError(
        category = AiProviderErrorCategory.BUDGET,
        message = "Запрос остановлен политикой бюджета: $reason.",
        outcome = AiRequestOutcome.NOT_SENT
    ))
}
