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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CouncilAgent(
    val name: String,
    val emoji: String,
    val prompt: String
)

@Composable
fun CouncilScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var answers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var summary by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val agents = listOf(
        CouncilAgent(
            "ГЛАФИРА", "🤖",
            "Ты — Глафира, главный координатор. Смотри на вопрос стратегически, выделяй главное, предлагай план действий. Отвечай чётко и по делу."
        ),
        CouncilAgent(
            "АНАЛИТИК", "📊",
            "Ты — Аналитик. Анализируй факты, риски, цифры. Отвечай объективно, без эмоций. Будь точен."
        ),
        CouncilAgent(
            "ПРОГРАММИСТ", "💻",
            "Ты — Программист. Оценивай техническую реализуемость, предлагай конкретные решения. Отвечай кодом и кратким объяснением."
        )
    )

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFDCE8F4)).border(1.dp, Line).padding(horizontal = 8.dp, vertical = 6.dp),
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
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Что обсудить?", color = Tx, fontSize = 12.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = GrD, unfocusedContainerColor = GrD,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Gr
                ),
                maxLines = 4
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (question.isBlank() || isLoading) return@Button
                    isLoading = true
                    answers = emptyMap()
                    summary = ""
                    scope.launch {
                        val results = coroutineScope {
                            agents.map { agent ->
                                async {
                                    val history = listOf(Msg(question, true, "", ""))
                                    val reply = callGigaChatWithTrustedCert(context, history, agent.prompt)
                                    agent.name to reply
                                }
                            }.map { it.await() }.toMap()
                        }
                        answers = results
                        val councilInput = buildString {
                            appendLine("Вопрос: $question")
                            appendLine()
                            results.forEach { (name, answer) ->
                                appendLine("--- $name ---")
                                appendLine(answer.take(1000))
                                appendLine()
                            }
                            appendLine("Сформируй итоговое решение на основе мнений совета. Выдели главное, предложи конкретные шаги.")
                        }
                        val finalAnswer = callGigaChatWithTrustedCert(
                            context,
                            listOf(Msg(councilInput, true, "", "")),
                            "Ты — Глафира, председатель совета. Собери мнения специалистов в единое решение. Будь краток и конкретен."
                        )
                        summary = finalAnswer
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White),
                enabled = question.isNotBlank() && !isLoading
            ) {
                Text(if (isLoading) "СОВЕТ РАБОТАЕТ..." else "СОЗВАТЬ СОВЕТ", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))

            agents.forEach { agent ->
                val answer = answers[agent.name]
                if (answer != null) {
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
                            Text(answer, color = Color(0xFF202020), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                        }
                    }
                } else if (isLoading) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GrD),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(agent.emoji, fontSize = 16.sp)
                            Spacer(Modifier.width(6.dp))
                            Text("${agent.name} думает...", color = Tx, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
