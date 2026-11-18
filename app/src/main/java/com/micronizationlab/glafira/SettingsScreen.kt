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

fun saveApiKey(context: Context, key: String): Boolean =
    EncryptedApiKeyStore.save(context, "gigachat_api_key", key)

fun loadApiKey(context: Context): String =
    EncryptedApiKeyStore.load(context, "gigachat_api_key")

@Composable
fun SettingsScreen(onBack: () -> Unit, currentProject: Project? = null) {
    val context = LocalContext.current
    val peerConsultationKey = currentProject?.id?.let { "peer_consultation_enabled_$it" } ?: "peer_consultation_enabled"
    val peerProvidersKey = currentProject?.id?.let { "peer_consultation_providers_$it" } ?: "peer_consultation_providers"
    var gigachatKey by remember { mutableStateOf(loadApiKey(context)) }
    var openaiKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.OPENAI)) }
    var deepseekKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.DEEPSEEK)) }
    var grokKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.GROK)) }
    var yandexKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.YANDEX)) }
    var geminiKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.GEMINI)) }
    var openRouterKey by remember { mutableStateOf(loadProviderKey(context, ProviderId.OPENROUTER)) }
    var isSaved by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var workingExpanded by remember { mutableStateOf(true) }
    var pendingExpanded by remember { mutableStateOf(true) }
    var showDeleteProjectConfirm by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var biometricEnabled by remember { mutableStateOf(isBiometricEnabled(context)) }
    var showPasswordVault by remember { mutableStateOf(false) }
    var showBridgeApprovals by remember { mutableStateOf(false) }
    var primaryProvider by remember { mutableStateOf(loadGlafiraPrimaryProvider(context)) }
    var primaryProviderMenu by remember { mutableStateOf(false) }
    var peerConsultationEnabled by remember(peerConsultationKey) {
        mutableStateOf(context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
            .getBoolean(peerConsultationKey, false))
    }
    var selectedPeerProviders by remember(peerProvidersKey) {
        mutableStateOf(
            context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                .getString(peerProvidersKey, "").orEmpty().split(",")
                .mapNotNull { value -> runCatching { ProviderId.valueOf(value) }.getOrNull() }.toSet()
        )
    }

    if (showPasswordVault) {
        PasswordVaultScreen(onBack = { showPasswordVault = false })
        return
    }
    if (showBridgeApprovals) {
        BridgePairingApprovalScreen(onBack = { showBridgeApprovals = false })
        return
    }

    LaunchedEffect(isSaved) {
        if (isSaved) primaryProvider = loadGlafiraPrimaryProvider(context)
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.clickable { onBack() }.padding(end = 14.dp))
            Text("НАСТРОЙКИ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                onValueChange = { gigachatKey = it; isSaved = false; saveError = false },
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
            Text("6", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (pendingExpanded) {
            ProviderKeyField(
                label = "ChatGPT (OpenAI API Key, sk-...)",
                value = openaiKey,
                onValueChange = { openaiKey = it; isSaved = false; saveError = false },
                configured = openaiKey.isNotBlank()
            )
            ProviderKeyField(
                label = "DeepSeek API Key",
                value = deepseekKey,
                onValueChange = { deepseekKey = it; isSaved = false; saveError = false },
                configured = deepseekKey.isNotBlank()
            )
            ProviderKeyField(
                label = "Grok (xAI API Key)",
                value = grokKey,
                onValueChange = { grokKey = it; isSaved = false; saveError = false },
                configured = grokKey.isNotBlank()
            )
            ProviderKeyField(
                label = "Алиса (Yandex Cloud API Key)",
                value = yandexKey,
                onValueChange = { yandexKey = it; isSaved = false; saveError = false },
                configured = yandexKey.isNotBlank()
            )
            ProviderKeyField(
                label = "Google Gemini API Key (есть бесплатный тариф)",
                value = geminiKey,
                onValueChange = { geminiKey = it; isSaved = false; saveError = false },
                configured = geminiKey.isNotBlank()
            )
            ProviderKeyField(
                label = "OpenRouter API Key (бесплатные модели)",
                value = openRouterKey,
                onValueChange = { openRouterKey = it; isSaved = false; saveError = false },
                configured = openRouterKey.isNotBlank()
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val saveResults = listOf(
                    saveApiKey(context, gigachatKey.trim()),
                    saveProviderKey(context, ProviderId.OPENAI, openaiKey),
                    saveProviderKey(context, ProviderId.DEEPSEEK, deepseekKey),
                    saveProviderKey(context, ProviderId.GROK, grokKey),
                    saveProviderKey(context, ProviderId.YANDEX, yandexKey),
                    saveProviderKey(context, ProviderId.GEMINI, geminiKey),
                    saveProviderKey(context, ProviderId.OPENROUTER, openRouterKey)
                )
                val saved = saveResults.all { it }
                isSaved = saved
                saveError = !saved
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
        ) {
            Text("СОХРАНИТЬ ВСЕ КЛЮЧИ", fontWeight = FontWeight.Bold)
        }

        if (isSaved || saveError) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (isSaved) "Ключи зашифрованы и сохранены." else "Не удалось безопасно сохранить ключи. Повторите попытку.",
                color = if (isSaved) Gr else Red,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("ГЛАВНЫЙ МОЗГ ГЛАФИРЫ", color = Gr, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Это основная модель-координатор. Глафира может самостоятельно советоваться с другими подключёнными моделями.",
                    color = Tx, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                val configuredProviders = configuredGlafiraProviders(context)
                Box {
                    OutlinedButton(
                        onClick = { primaryProviderMenu = true },
                        enabled = configuredProviders.isNotEmpty()
                    ) {
                        Text(primaryProvider.displayName)
                        Text("  ▾")
                    }
                    DropdownMenu(
                        expanded = primaryProviderMenu,
                        onDismissRequest = { primaryProviderMenu = false }
                    ) {
                        configuredProviders.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    if (saveGlafiraPrimaryProvider(context, provider)) {
                                        primaryProvider = provider
                                    }
                                    primaryProviderMenu = false
                                }
                            )
                        }
                    }
                }
                if (configuredProviders.isEmpty()) {
                    Text("Сначала подключи хотя бы одну модель выше.", color = Tx, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (currentProject == null) "НЕЗАВИСИМЫЕ МОДЕЛИ-СОВЕТНИКИ" else "СОВЕТНИКИ ДЛЯ ЭТОГО ПРОЕКТА", color = Color(0xFF202020), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (currentProject == null) "Если включено, Глафира может передавать до 12 последних сообщений другим подключённым моделям. По умолчанию выключено." else "Если включено, Глафира может передавать до 12 последних сообщений этого проекта другим подключённым моделям. Типичные API-токены, пароли, email и телефоны маскируются, но это не гарантирует удаление всех персональных данных. По умолчанию выключено для каждого проекта.",
                        color = Tx, fontSize = 11.sp
                    )
                }
                Switch(
                    checked = peerConsultationEnabled,
                    onCheckedChange = { enabled ->
                        peerConsultationEnabled = enabled
                        context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean(peerConsultationKey, enabled).apply()
                    }
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("КАКИМ МОДЕЛЯМ РАЗРЕШЕНО СОВЕТОВАТЬСЯ", color = Color(0xFF202020), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Выбирай только те провайдеры, которым разрешено получать контекст этого проекта. Главная модель-координатор исключается автоматически.", color = Tx, fontSize = 11.sp)
                val peerCandidates = configuredGlafiraProviders(context).filter { it != primaryProvider }
                if (peerCandidates.isEmpty()) {
                    Text("Нет других подключённых моделей.", color = Tx, fontSize = 11.sp)
                } else {
                    peerCandidates.forEach { provider ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = provider in selectedPeerProviders,
                                onCheckedChange = { checked ->
                                    selectedPeerProviders = if (checked) selectedPeerProviders + provider else selectedPeerProviders - provider
                                    context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                                        .edit().putString(peerProvidersKey, selectedPeerProviders.joinToString(",") { it.name }).apply()
                                }
                            )
                            Text(provider.displayName, color = Color(0xFF202020), fontSize = 12.sp)
                        }
                    }
                }
                if (selectedPeerProviders.isEmpty()) {
                    Text("Пока ни одной модели не разрешено получать контекст.", color = Tx, fontSize = 10.sp)
                }
            }
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

        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth().clickable { showBridgeApprovals = true }
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔗", fontSize = 24.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Сопряжение устройств GLAFIRA", color = Color(0xFF202020), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Очередь запросов · выбор прав · выдача ключей владельцем", color = Tx, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        BackupSettingsSection()

        if (currentProject == null) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = GrD),
                modifier = Modifier.fillMaxWidth().clickable { showPasswordVault = true }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔐", fontSize = 24.sp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Закрытое хранилище паролей", color = Color(0xFF202020), fontSize = 16.sp)
                        Text(
                            if (VaultSession.exists(context)) "Зашифровано · требуется мастер-пароль" else "Создать личный сейф паролей",
                            color = Tx, fontSize = 11.sp
                        )
                    }
                }
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
            title = { Text("Удалить историю проекта?", color = Color(0xFF202020)) },
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
