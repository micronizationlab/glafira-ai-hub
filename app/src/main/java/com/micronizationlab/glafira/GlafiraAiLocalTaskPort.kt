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
        if (!isProviderConfigured(appContext, providerId)) {
            return GlafiraDistributedTaskResult(
                taskId = task.taskId,
                node = GlafiraExecutionNode.ANDROID,
                succeeded = false,
                errorCode = "LOCAL_AI_PROVIDER_NOT_CONFIGURED"
            )
        }
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
        val response = callAiProvider(
            context = appContext,
            providerId = providerId,
            history = listOf(Msg(
                text = task.instruction,
                isUser = true,
                time = "",
                date = ""
            )),
            systemPrompt = systemPrompt
        )
        val failed = response.isBlank() || response.startsWith("Ошибка:") ||
            response.startsWith("Ошибка API") || response.startsWith("Ошибка сети")
        return GlafiraDistributedTaskResult(
            taskId = task.taskId,
            node = GlafiraExecutionNode.ANDROID,
            succeeded = !failed,
            output = response.takeIf { !failed }?.take(12_000),
            errorCode = if (failed) "LOCAL_AI_PROVIDER_REQUEST_FAILED" else null
        )
    }
}
