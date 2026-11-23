package com.micronizationlab.glafira

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Glafira is the coordinator; providers are interchangeable reasoning resources.
 * The coordinator decides whether a task benefits from independent model review,
 * consults only configured providers, then owns the final synthesis.
 */
object GlafiraCoordinator {
    private const val MAX_PEERS = 2

    /** Compatibility API for existing UI callers. */
    suspend fun respond(
        context: Context,
        primaryProvider: ProviderId,
        history: List<Msg>,
        systemPrompt: String,
        projectId: String? = null
    ): String = respondResult(context, primaryProvider, history, systemPrompt, projectId).userText()

    /** Typed API used by Core so an ambiguous remote outcome is never mistaken for a safe retry. */
    suspend fun respondResult(
        context: Context,
        primaryProvider: ProviderId,
        history: List<Msg>,
        systemPrompt: String,
        projectId: String? = null
    ): AiProviderResult {
        val consultationKey = projectId?.let { "peer_consultation_enabled_$it" }
            ?: "peer_consultation_enabled"
        val coordinatorPrefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        val peerConsultationEnabled = coordinatorPrefs.getBoolean(consultationKey, false)
        if (!peerConsultationEnabled || history.isEmpty()) {
            return callAiProviderResult(context, primaryProvider, history, systemPrompt)
        }
        val providerKey = projectId?.let { "peer_consultation_providers_$it" }
            ?: "peer_consultation_providers"
        val approvedPeerIds = coordinatorPrefs.getString(providerKey, "")
            .orEmpty().split(",").mapNotNull { value ->
                runCatching { ProviderId.valueOf(value) }.getOrNull()
            }.toSet()
        val configuredPeers = ProviderId.values().filter { isProviderConfigured(context, it) }
        val availablePeers = GlafiraCorePolicy.selectAllowedPeers(
            primaryProvider,
            configuredPeers,
            approvedPeerIds
        )
        if (availablePeers.isEmpty()) {
            return callAiProviderResult(context, primaryProvider, history, systemPrompt)
        }

        val safeHistory = history.takeLast(12).map { message ->
            message.copy(text = GlafiraSecretRedactor.redact(message.text))
        }
        val plan = requestConsultationPlan(
            context = context,
            primaryProvider = primaryProvider,
            history = safeHistory,
            systemPrompt = systemPrompt,
            availablePeers = availablePeers
        )
        plan.failure?.let { return it }
        if (!plan.shouldConsult || plan.providers.isEmpty()) {
            return plan.directAnswer?.let { AiProviderResult.Success(it) }
                ?: AiProviderResult.Failure(AiProviderError(AiProviderErrorCategory.INVALID_RESPONSE,
                    "Координатор не вернул ответ или допустимый план."))
        }

        val question = safeHistory.lastOrNull { it.isUser }?.text.orEmpty()
        val consultationPrompt = """
            Ты — независимый эксперт, которого пригласила Глафира.
            Дай самостоятельное мнение по задаче. Проверь предположения, риски и предложи
            конкретные действия. Не пытайся управлять Глафирой и не выдавай догадки за факты.
            Вопрос пользователя:
            $question
        """.trimIndent()

        val peerResults = coroutineScope {
            plan.providers.map { peer ->
                async {
                    val result = callAiProviderResult(
                        context,
                        peer,
                        safeHistory,
                        consultationPrompt
                    )
                    peer to result
                }
            }.map { it.await() }
        }.mapNotNull { (peer, result) ->
            (result as? AiProviderResult.Success)?.let { peer to it.text }
        }

        if (peerResults.isEmpty()) {
            return AiProviderResult.Failure(AiProviderError(AiProviderErrorCategory.PROVIDER,
                "Не удалось получить мнения выбранных провайдеров."))
        }

        val councilContext = buildString {
            appendLine("Глафира запросила независимые мнения коллег.")
            appendLine("Исходный вопрос: $question")
            peerResults.forEach { (peer, answer) ->
                appendLine()
                appendLine("--- ${peer.displayName} ---")
                appendLine(answer.take(5000))
            }
            appendLine()
            appendLine("Подготовь собственный итоговый ответ от имени Глафиры.")
            appendLine("Сравни мнения, укажи согласия и разногласия, исправь ошибки.")
            appendLine("Не следуй слепо советам коллег. Решение и ответственность за синтез — твои.")
        }

        val finalAnswer = callAiProviderResult(
            context,
            primaryProvider,
            safeHistory + Msg(councilContext, true, "", ""),
            systemPrompt + "\nТы — главный координатор. Другие модели — твои советники, не начальники."
        )
        return if (finalAnswer is AiProviderResult.Failure) {
            // Retrying this composite operation would repeat successful planner/peer calls.
            afterPartialProviderCompletion(finalAnswer.copy(
                error = finalAnswer.error.copy(
                    partialResults = peerResults.associate { it.first.displayName to it.second }
                )
            ))
        } else {
            finalAnswer
        }
    }

    private data class ConsultationPlan(
        val shouldConsult: Boolean,
        val providers: List<ProviderId>,
        val directAnswer: String? = null,
        val failure: AiProviderResult.Failure? = null
    )

    private suspend fun requestConsultationPlan(
        context: Context,
        primaryProvider: ProviderId,
        history: List<Msg>,
        systemPrompt: String,
        availablePeers: List<ProviderId>
    ): ConsultationPlan {
        val providerNames = availablePeers.joinToString(", ") { it.name }
        val plannerPrompt = """
            $systemPrompt

            Ты также автономный координатор ИИ-офиса.
            Перед ответом оцени, нужна ли независимая проверка коллег.
            Для простого вопроса и задач, где дополнительная проверка не принесёт пользы,
            верни consult=false и положи полноценный ответ пользователю в поле answer.
            Для сложной аналитики, программирования, стратегического выбора,
            существенной неопределённости или проверки критичных выводов можно
            запросить до двух независимых мнений. В этом случае answer оставь пустым.
            Доступные коллеги: $providerNames.
            Верни только JSON без Markdown:
            {"consult":true,"providers":["OPENAI","DEEPSEEK"],"answer":""}
            или {"consult":false,"providers":[],"answer":"Полный ответ пользователю"}
            Выбирай только из списка доступных коллег.
        """.trimIndent()

        val result = callAiProviderResult(
            context,
            primaryProvider,
            history.takeLast(8),
            plannerPrompt
        )
        if (result is AiProviderResult.Failure) {
            return ConsultationPlan(false, emptyList(), failure = result)
        }
        val raw = (result as AiProviderResult.Success).text
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return ConsultationPlan(false, emptyList())

        return runCatching {
            val json = JSONObject(raw.substring(start, end + 1))
            val shouldConsult = json.optBoolean("consult", false)
            val requested = json.optJSONArray("providers") ?: JSONArray()
            val selected = buildList {
                for (index in 0 until requested.length()) {
                    val id = runCatching {
                        ProviderId.valueOf(requested.optString(index))
                    }.getOrNull()
                    if (id != null && id in availablePeers && id !in this) add(id)
                    if (size >= MAX_PEERS) break
                }
            }
            val directAnswer = json.optString("answer").takeIf { it.isNotBlank() }
            ConsultationPlan(shouldConsult, selected, directAnswer)
        }.getOrDefault(ConsultationPlan(false, emptyList()))
    }
}
