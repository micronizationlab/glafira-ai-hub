package com.micronizationlab.glafira

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

fun saveApiKey(context: Context, key: String) {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("gigachat_api_key", key).commit()
}

fun loadApiKey(context: Context): String {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    return prefs.getString("gigachat_api_key", "") ?: ""
}

@Composable
fun SettingsScreen(onBack: () -> Unit, currentProject: Project? = null) {
    val context = LocalContext.current
    var gigachatKey by remember { mutableStateOf(loadApiKey(context)) }
    var openaiKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.OPENAI)) }
    var deepseekKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.DEEPSEEK)) }
    var grokKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.GROK)) }
    var yandexKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.YANDEX)) }
    var isSaved by remember { mutableStateOf(false) }
    var workingExpanded by remember { mutableStateOf(true) }
    var pendingExpanded by remember { mutableStateOf(true) }
    var showDeleteProjectConfirm by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var biometricEnabled by remember { mutableStateOf(isBiometricEnabled(context)) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Gr, fontSize = 24.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
            Text("НАСТРОЙКИ", color = Gr, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(24.dp))

        Row(
            Modifier.fillMaxWidth().clickable { workingExpanded = !workingExpanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (workingExpanded) "▼" else "▶", color = Gr, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            Text("РАБОТАЮЩИЕ", color = Gr, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("1", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (workingExpanded) {
            ProviderKeyField(
                label = "GigaChat (Authorization Key)",
                value = gigachatKey,
                onValueChange = { gigachatKey = it; isSaved = false },
                configured = gigachatKey.isNotBlank()
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().clickable { pendingExpanded = !pendingExpanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (pendingExpanded) "▼" else "▶", color = Gr, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            Text("ТРЕБУЮТ КЛЮЧА", color = Tx, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("4", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (pendingExpanded) {
            ProviderKeyField(
                label = "ChatGPT (OpenAI API Key, sk-...)",
                value = openaiKey,
                onValueChange = { openaiKey = it; isSaved = false },
                configured = openaiKey.isNotBlank()
            )
            ProviderKeyField(
                label = "DeepSeek API Key",
                value = deepseekKey,
                onValueChange = { deepseekKey = it; isSaved = false },
                configured = deepseekKey.isNotBlank()
            )
            ProviderKeyField(
                label = "Grok (xAI API Key)",
                value = grokKey,
                onValueChange = { grokKey = it; isSaved = false },
                configured = grokKey.isNotBlank()
            )
            ProviderKeyField(
                label = "Алиса (Yandex Cloud API Key)",
                value = yandexKey,
                onValueChange = { yandexKey = it; isSaved = false },
                configured = yandexKey.isNotBlank()
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                saveApiKey(context, gigachatKey.trim())
                saveProviderKey(context, ProviderId.OPENAI, openaiKey)
                saveProviderKey(context, ProviderId.DEEPSEEK, deepseekKey)
                saveProviderKey(context, ProviderId.GROK, grokKey)
                saveProviderKey(context, ProviderId.YANDEX, yandexKey)
                isSaved = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
        ) {
            Text("СОХРАНИТЬ ВСЕ КЛЮЧИ", fontWeight = FontWeight.Bold)
        }

        if (isSaved) {
            Spacer(Modifier.height(8.dp))
            Text("Ключи успешно сохранены!", color = Gr, fontSize = 12.sp)
        }

        Spacer(Modifier.height(32.dp))

        Text("БЕЗОПАСНОСТЬ", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Вход по отпечатку", color = Color(0xFF202020), fontSize = 16.sp)
                    Text(
                        if (!canUseBiometric(context)) "Биометрия недоступна на устройстве"
                        else if (biometricEnabled) "Включено" else "Выключено",
                        color = Tx, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = biometricEnabled,
                    onCheckedChange = { checked ->
                        if (checked && !canUseBiometric(context)) {
                            ToastHelper.short(context, "Биометрия недоступна")
                        } else {
                            biometricEnabled = checked
                            setBiometricEnabled(context, checked)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Gr,
                        checkedTrackColor = Gr.copy(alpha = 0.5f)
                    )
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        if (currentProject != null) {
            Text("ПРОЕКТ: ${currentProject.name}", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GrD),
                modifier = Modifier.fillMaxWidth().clickable {
                    val agentNames = listOf("ГЛАФИРА", "АНАЛИТИК", "ПРОГРАММИСТ", "GigaChat")
                    val file = exportProjectToFile(context, currentProject, agentNames)
                    exportResult = file.absolutePath
                }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📦", fontSize = 24.sp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Экспортировать проект", color = Color(0xFF202020), fontSize = 16.sp)
                        Text("Все чаты всех агентов в один файл", color = Tx, fontSize = 12.sp)
                    }
                }
            }

            if (exportResult != null) {
                Spacer(Modifier.height(8.dp))
                Text("Сохранено: $exportResult", color = Gr, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GrD),
                modifier = Modifier.fillMaxWidth().clickable { showDeleteProjectConfirm = true }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🗑", fontSize = 24.sp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Удалить историю проекта", color = Red, fontSize = 16.sp)
                        Text("Все чаты и закрепления этого проекта", color = Tx, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth().clickable {
                val files = context.filesDir.listFiles { file -> file.name.startsWith("chat_") }
                if (!files.isNullOrEmpty()) {
                    val latestFile = files.maxByOrNull { it.lastModified() }
                    if (latestFile != null) {
                        val key = latestFile.name.removePrefix("chat_").removeSuffix(".json")
                        val messages = loadHistory(context, key)
                        val parts = key.split("_")
                        val agentName = if (parts.size > 1) parts[1] else "Агент"
                        copyFullHistory(context, "Проект", agentName, messages)
                    }
                }
            }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📋", fontSize = 24.sp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Скопировать историю", color = Color(0xFF202020), fontSize = 16.sp)
                    Text("Буфер обмена (последний изменённый чат)", color = Tx, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("ВЕРСИЯ ПРИЛОЖЕНИЯ", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("GLAFIRA AI HUB v1.0", color = Color(0xFF202020), fontSize = 14.sp)
                Text("Sprint 10.0 — Биометрия", color = Tx, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Очистить все данные приложения",
            color = Color.Red.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val files = context.filesDir.listFiles()
                    files?.forEach { file ->
                        if (file.name.startsWith("chat_") || file.name == "projects.json" || file.name == "pending_queue.json" || file.name.startsWith("pinned_") || file.name.startsWith("export_") || file.name.startsWith("project_")) {
                            file.delete()
                        }
                    }
                }
                .padding(16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }

    if (showDeleteProjectConfirm && currentProject != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProjectConfirm = false },
            title = { Text("Удалить историю проекта?", color = Bg) },
            text = { Text("Все чаты и закрепления проекта «${currentProject.name}» будут удалены безвозвратно. Сам проект останется.", color = Tx, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deleteProjectHistory(context, currentProject.id)
                    showDeleteProjectConfirm = false
                }) { Text("УДАЛИТЬ", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProjectConfirm = false }) { Text("ОТМЕНА", color = Tx) }
            }
        )
    }
}

@Composable
fun ProviderKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    configured: Boolean
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Tx, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (configured) "✓ настроен" else "✗ не настроен",
                color = if (configured) Gr else Color.Gray,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Вставьте ключ...", color = Tx, fontSize = 12.sp) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedTextColor = Color(0xFF202020), unfocusedTextColor = Color(0xFF202020),
                focusedIndicatorColor = Gr
            )
        )
    }
}
