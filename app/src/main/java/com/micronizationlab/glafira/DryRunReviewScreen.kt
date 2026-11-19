package com.micronizationlab.glafira

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun DryRunReviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { GlafiraCoreRuntimeOwner.get(context) }
    var previews by remember { mutableStateOf(runtime.dryRunPreviewRecords()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("← НАЗАД", color = Color.White) }
            Text("ПЛАНЫ DRY RUN", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Просмотр безопасных планов Core. Подтверждение ниже фиксирует только факт ознакомления и НЕ запускает задание.",
            color = Tx, fontSize = 11.sp
        )
        message?.let { Text(it, color = Gr, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        if (previews.isEmpty()) {
            Text("Сохранённых планов пока нет.", color = Tx, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(previews, key = { it.taskId }) { record ->
                    val json = remember(record.planJson) { JSONObject(record.planJson) }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(record.toolId, color = Gr, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Задание: ${record.taskId}", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("Проект: ${record.projectId}", color = Tx, fontSize = 10.sp)
                            Text("Риск: ${record.risk} · Симуляция: ${record.simulationStatus}", color = Tx, fontSize = 11.sp)
                            Text("Авторизация: ${json.optString("authorizationStatus")} · ${json.optString("authorizationReason", "без замечаний")}", color = Tx, fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("ОЖИДАЕМЫЕ ИЗМЕНЕНИЯ", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            PreviewArray(json.optJSONArray("expectedChanges")).forEach {
                                Text("• $it", color = Color(0xFF202020), fontSize = 11.sp)
                            }
                            PreviewArray(json.optJSONArray("requiredPermissions")).takeIf { it.isNotEmpty() }?.let { values ->
                                Text("РАЗРЕШЕНИЯ: ${values.joinToString()}", color = Tx, fontSize = 10.sp)
                            }
                            PreviewArray(json.optJSONArray("approvalPoints")).forEach {
                                Text("Контроль: $it", color = Tx, fontSize = 10.sp)
                            }
                            PreviewArray(json.optJSONArray("limitations")).forEach {
                                Text("Ограничение: $it", color = Tx, fontSize = 10.sp)
                            }
                            Text("SHA-256: ${record.planHash}", color = Tx, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            Text("Статус проверки: ${record.reviewStatus}", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            if (record.reviewStatus == GlafiraDryRunReviewStatus.PENDING) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val activity = context as? FragmentActivity
                                            if (activity == null) {
                                                message = "Для подтверждения требуется активное окно GLAFIRA."
                                            } else authenticateCoreOwner(activity, onSuccess = { session ->
                                                val saved = runtime.reviewDryRunPreview(
                                                    record.taskId, record.planHash,
                                                    GlafiraDryRunReviewStatus.ACKNOWLEDGED, session
                                                )
                                                previews = runtime.dryRunPreviewRecords()
                                                message = if (saved) "Ознакомление подтверждено. Исполнение не запускалось." else "План изменился или уже был рассмотрен."
                                            }, onError = { message = it })
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
                                    ) { Text("ОЗНАКОМЛЕН") }
                                    OutlinedButton(
                                        onClick = {
                                            val activity = context as? FragmentActivity
                                            if (activity == null) {
                                                message = "Для отклонения требуется активное окно GLAFIRA."
                                            } else authenticateCoreOwner(activity, onSuccess = { session ->
                                                val saved = runtime.reviewDryRunPreview(
                                                    record.taskId, record.planHash,
                                                    GlafiraDryRunReviewStatus.REJECTED, session
                                                )
                                                previews = runtime.dryRunPreviewRecords()
                                                message = if (saved) "План отклонён. Исполнение не запускалось." else "План изменился или уже был рассмотрен."
                                            }, onError = { message = it })
                                        }
                                    ) { Text("ОТКЛОНИТЬ") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PreviewArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}
