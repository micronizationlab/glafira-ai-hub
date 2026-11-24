package com.micronizationlab.glafira

import android.content.Context

/**
 * Android-side agent executor for the existing distributed orchestrator.
 * It performs model reasoning only; no shell, Bridge write, credentials or external tool calls.
 */
internal class GlafiraAiLocalTaskPort(
    context: Context,
    private val providerId: ProviderId = loadGlafiraPrimaryProvider(context)
) : GlafiraLocalTaskPort {
    private val appContext = context.applicationContext

    override suspend fun execute(task: GlafiraDistributedTask): GlafiraDistributedTaskResult {
        val systemPrompt = when (task.executor) {
            GlafiraTaskExecutor.GLAFIRA ->
                "Ты — локальный координатор GLAFIRA. Выполни только аналитическую координацию; не вызывай инструменты."
            GlafiraTaskExecutor.ANALYST ->
                "Ты — аналитик GLAFIRA. Отделяй факты, допущения и выводы; указывай пробелы в данных."
            GlafiraTaskExecutor.PROGRAMMER ->
                "Ты — программист GLAFIRA. Предлагай проверяемые изменения; не заявляй о запуске тестов без запуска."
            GlafiraTaskExecutor.RESEARCHER ->
                "Ты — исследователь GLAFIRA. Не выдумывай источники и явно отмечай отсутствие веб-поиска."
        }
        if (!isProviderConfigured(appContext, providerId)) {
            return GlafiraDistributedTaskResult(
                taskId = task.taskId,
                node = GlafiraExecutionNode.ANDROID,
                succeeded = false,
                errorCode = "LOCAL_AI_PROVIDER_REQUEST_FAILED",
                providerOutcome = AiRequestOutcome.NOT_SENT,
                providerErrorCategory = AiProviderErrorCategory.NOT_CONFIGURED
            )
        }
        return callAiProviderResult(
            context = appContext,
            providerId = providerId,
            history = listOf(Msg(
                text = task.instruction,
                isUser = true,
                time = "",
                date = ""
            )),
            systemPrompt = systemPrompt
        ).toDistributedTaskResult(task.taskId)
    }
}

/** Keeps the provider's dispatch certainty attached to the distributed result; never retries here. */
internal fun AiProviderResult.toDistributedTaskResult(taskId: String): GlafiraDistributedTaskResult =
    when (this) {
        is AiProviderResult.Success -> GlafiraDistributedTaskResult(
            taskId = taskId,
            node = GlafiraExecutionNode.ANDROID,
            succeeded = text.isNotBlank(),
            output = text.takeIf { it.isNotBlank() }?.take(12_000),
            errorCode = if (text.isBlank()) "LOCAL_AI_PROVIDER_EMPTY_RESPONSE" else null,
            providerOutcome = if (text.isBlank()) AiRequestOutcome.UNKNOWN else null,
            providerErrorCategory = if (text.isBlank()) AiProviderErrorCategory.INVALID_RESPONSE else null
        )
        is AiProviderResult.Failure -> GlafiraDistributedTaskResult(
            taskId = taskId,
            node = GlafiraExecutionNode.ANDROID,
            succeeded = false,
            errorCode = "LOCAL_AI_PROVIDER_REQUEST_FAILED",
            providerOutcome = error.outcome,
            providerErrorCategory = error.category,
            providerRetryAfterMillis = error.retryAfterMillis
        )
    }
