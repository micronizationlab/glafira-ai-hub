package com.micronizationlab.glafira

import android.content.Context

/**
 * Production planner adapter over GLAFIRA's configured AI provider.
 * The model only returns the existing validated plan JSON; it receives no credentials,
 * Bridge secrets, owner grants or tool execution channel.
 */
class GlafiraAiProviderTaskPlanDecomposer(
    context: Context,
    private val providerId: ProviderId = loadGlafiraPrimaryProvider(context)
) : GlafiraCoreTaskPlanDecomposer {
    private val appContext = context.applicationContext

    override suspend fun decompose(request: GlafiraCoreTaskPlanRequest): GlafiraCoreTaskPlan {
        require(isProviderConfigured(appContext, providerId)) {
            "TASK_PLANNER_PROVIDER_NOT_CONFIGURED"
        }
        val prompt = buildString {
            appendLine("Ты — планировщик GLAFIRA. Разбей поручение на проверяемые независимые подзадачи.")
            appendLine("Верни только JSON без Markdown и пояснений.")
            appendLine("Формат: {\"goal\":\"...\",\"tasks\":[{\"id\":\"task_1\",\"title\":\"...\",\"instruction\":\"...\",\"executor\":\"GLAFIRA|ANALYST|PROGRAMMER|RESEARCHER\",\"dependsOn\":[]}]}.")
            appendLine("Не более ${minOf(request.maxNodes, GlafiraTaskPlanner.MAX_TASKS)} подзадач.")
            appendLine("Зависимости указывай только по id других подзадач; независимые задачи не связывай.")
            appendLine("Не включай пароли, ключи, токены, персональные секреты или выдуманные результаты.")
            appendLine("Не пытайся выполнять инструменты, писать код для немедленного запуска или менять режим безопасности.")
            appendLine("Используй исполнителя RESEARCHER для исследования, ANALYST для анализа, PROGRAMMER для разработки, GLAFIRA для координации.")
            appendLine("Каждая инструкция должна быть самодостаточной, конкретной и проверяемой.")
        }
        val response = callAiProvider(
            context = appContext,
            providerId = providerId,
            history = listOf(Msg(
                text = request.instruction,
                isUser = true,
                time = "",
                date = ""
            )),
            systemPrompt = prompt
        )
        require(response.isNotBlank() && !response.startsWith("Ошибка:") &&
            !response.startsWith("Ошибка API") && !response.startsWith("Ошибка сети")
        ) { "TASK_PLANNER_PROVIDER_REQUEST_FAILED" }
        return GlafiraJsonTaskPlanDecoder.decode(request, response)
    }
}
