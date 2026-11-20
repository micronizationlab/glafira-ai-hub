package com.micronizationlab.glafira

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class CouncilAgent(
    val name: String,
    val emoji: String,
    val prompt: String,
    val providerId: ProviderId
)

@Composable
fun CouncilScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var activeAgents by remember { mutableStateOf<List<CouncilAgent>>(emptyList()) }
    var answers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var summary by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFDCE8F4)).border(1.dp, Line)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Gr, fontSize = 18.sp, modifier = Modifier.clickable { onBack() }.padding(end = 10.dp))
            Text("СОВЕТ ДИРЕКТОРОВ", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("ВОПРОС", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            TextField(
                value = question,
                onValueChange = { question = it; notice = null },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Что обсудить?") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedTextColor = Color(0xFF202020), unfocusedTextColor = Color(0xFF202020),
                    focusedIndicatorColor = Gr
                ),
                maxLines = 4
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (isLoading) return@Button
                    if (question.isBlank()) {
                        notice = "Сначала напиши вопрос для совета. Кнопка доступна — поле вопроса пока пустое."
                        return@Button
                    }
                    val configured = configuredGlafiraProviders(context)
                    val primary = loadGlafiraPrimaryProvider(context).takeIf { it in configured }
                        ?: configured.firstOrNull()
                    if (primary == null) {
                        notice = "Не подключена ни одна ИИ-модель. Открой Настройки и сохрани хотя бы один API-ключ."
                        return@Button
                    }

                    val advisors = buildList {
                        add(CouncilAgent(
                            "ГЛАФИРА · председатель", "🤖",
                            "Ты — Глафира, председатель совета директоров. Оцени вопрос стратегически, выдели риски и предложи конкретное решение.",
                            primary
                        ))
                        configured.filter { it != primary }.forEach { provider ->
                            add(CouncilAgent(
                                provider.displayName, "🧠",
                                "Ты — независимый советник совета директоров. Дай самостоятельную оценку вопроса, факты, риски, альтернативы и конкретную рекомендацию. Не повторяй чужие мнения.",
                                provider
                            ))
                        }
                    }

                    activeAgents = advisors
                    isLoading = true
                    answers = emptyMap()
                    summary = ""
                    notice = if (advisors.size == 1) {
                        "Подключена одна модель. Совет проведёт анализ одним участником; подключи дополнительные модели для независимых мнений."
                    } else {
                        "Совет запущен: участников ${advisors.size}."
                    }
                    val submittedQuestion = question.trim()
                    scope.launch {
                        try {
                            val results = coroutineScope {
                                advisors.map { agent ->
                                    async {
                                        val reply = runCatching {
                                            callAiProvider(
                                                context,
                                                agent.providerId,
                                                listOf(Msg(submittedQuestion, true, "", "")),
                                                agent.prompt
                                            )
                                        }.getOrElse { "Ошибка ${agent.providerId.displayName}: ${it.message ?: "не удалось получить ответ"}" }
                                        agent.name to reply
                                    }
                                }.map { it.await() }.toMap()
                            }
                            answers = results
                            val successful = results.filterValues {
                                !it.startsWith("Ошибка") && !it.startsWith("Не удалось")
                            }
                            if (successful.isEmpty()) {
                                summary = "Совет не получил ни одного ответа. Проверь подключение моделей и повтори попытку."
                                notice = "Все участники завершились с ошибкой. Подробности показаны в карточках ниже."
                            } else {
                                val synthesisInput = buildString {
                                    appendLine("Вопрос: $submittedQuestion")
                                    appendLine()
                                    successful.forEach { (name, answer) ->
                                        appendLine("--- $name ---")
                                        appendLine(answer.take(1500))
                                        appendLine()
                                    }
                                    appendLine("Сформируй итоговое решение. Отдели подтверждённые факты от предположений, обозначь разногласия и предложи следующие шаги.")
                                }
                                summary = runCatching {
                                    callAiProvider(
                                        context,
                                        primary,
                                        listOf(Msg(synthesisInput, true, "", "")),
                                        "Ты — Глафира, председатель совета директоров. Синтезируй мнения советников в одно взвешенное решение."
                                    )
                                }.getOrElse { "Не удалось собрать итог: ${it.message ?: "ошибка синтеза"}" }
                                val failedCount = advisors.size - successful.size
                                notice = if (failedCount == 0) "Все участники ответили."
                                    else "Получены частичные результаты: ошибок участников — $failedCount."
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
            ) {
                Text(if (isLoading) "СОВЕТ РАБОТАЕТ..." else "СОБРАТЬ СОВЕТ", fontWeight = FontWeight.Bold)
            }

            notice?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Tx, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))
            activeAgents.forEach { agent ->
                val answer = answers[agent.name]
                if (answer != null || isLoading) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GrD),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(agent.emoji, fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(agent.name, color = Gr, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                answer ?: "Ожидаем ответ…",
                                color = Color(0xFF202020), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            if (summary.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("ИТОГ", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Gr.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Gr.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(summary, color = Gr, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
