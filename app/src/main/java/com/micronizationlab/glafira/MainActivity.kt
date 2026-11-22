package com.micronizationlab.glafira

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Classic ICQ / Windows XP light palette
val Bg = Color(0xFFF4F4F4)
val Gr = Color(0xFF1767A8)
val GrD = Color(0xFFE2E2E2)
val Tx = Color(0xFF626262)
val Line = Color(0xFFB8B8B8)
val Red = Color(0xFFC62828)
val XpBlue = Color(0xFF2F73B7)


enum class AgentGroup(val title: String) {
    IA("ИНТЕЛЛЕКТУАЛЬНЫЕ АГЕНТЫ"),
    AI("МОДЕЛИ ИИ")
}

data class A(
    val n: String, val d: String, val on: Boolean, val emoji: String,
    val prompt: String, val fullDesc: String,
    val providerId: ProviderId = ProviderId.GIGACHAT,
    val group: AgentGroup = AgentGroup.IA
)
data class Msg(val text: String, val isUser: Boolean, val time: String, val date: String = "", val operationId: String = "")

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    var needsRecovery by remember { mutableStateOf(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) }
    if (needsRecovery) {
        Column(Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("Восстановление было прервано", color = Red, fontSize = 20.sp)
            Text("Введите пароль прерванного восстановления и выполните откат ниже. Рабочие данные и отправка задач заблокированы до завершения отката.", color = Tx)
            BackupSettingsSection()
            Button(onClick = { needsRecovery = GlafiraCoreBackupCoordinator.hasInterruptedRestore(context) }) {
                Text("ПРОВЕРИТЬ И ПРОДОЛЖИТЬ")
            }
        }
    } else WorkspaceApp()
}

@Composable
private fun WorkspaceApp() {
    var currentAgent by remember { mutableStateOf<A?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCouncil by remember { mutableStateOf(false) }
    var showTaskQueue by remember { mutableStateOf(false) }
    var statusRefresh by remember { mutableStateOf(0) }
    val context = LocalContext.current
    var projects by remember { mutableStateOf(loadProjects(context)) }
    var currentProject by remember { mutableStateOf(projects.firstOrNull() ?: defaultProjects().first()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(context) { SoundHelper.init(context) }

    var unlocked by remember {
        mutableStateOf(!isBiometricEnabled(context))
    }

    if (!unlocked) {
        val activity = context as? androidx.fragment.app.FragmentActivity
        LaunchedEffect(Unit) {
            if (activity != null) {
                showBiometricPrompt(
                    activity = activity,
                    onSuccess = { unlocked = true },
                    onError = { }
                )
            }
        }
        Column(
            Modifier.fillMaxSize().background(Bg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("GLAFIRA", color = Gr, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            Text("ЗАБЛОКИРОВАНО", color = Tx, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (activity != null) {
                        showBiometricPrompt(
                            activity = activity,
                            onSuccess = { unlocked = true },
                            onError = { }
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Bg)
            ) {
                Text("РАЗБЛОКИРОВАТЬ", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    LaunchedEffect(Unit) {
        // Recover interrupted calls once, then retry only outcomes known to be safe.
        while (true) {
            try {
                PendingDeliveryRuntime.replay(context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordStorageDiagnostic(context.filesDir, "pending_queue.json", "replay_${error.javaClass.simpleName}")
            }
            delay(5_000)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        when {
            showSettings -> SettingsScreen(
                onBack = {
                    showSettings = false
                    projects = loadProjects(context)
                    currentProject = projects.firstOrNull { it.id == currentProject.id } ?: projects.first()
                    statusRefresh++
                },
                currentProject = currentProject
            )
            showCouncil -> CouncilScreen(onBack = { showCouncil = false })
            showTaskQueue -> TaskQueueScreen(currentProject = currentProject, onBack = { showTaskQueue = false })
            currentAgent == null -> ContactList(
                projects = projects,
                currentProject = currentProject,
                refreshKey = statusRefresh,
                onProjectSelect = { currentProject = it },
                onProjectAdd = { name ->
                    val newProj = Project(UUID.randomUUID().toString(), name)
                    projects = updateProjects(context) { it + newProj }
                    currentProject = newProj
                },
                onProjectDelete = { project ->
                    scope.launch {
                        try {
                            projects = withContext(Dispatchers.IO) { deleteProject(context, project) }
                            if (currentProject.id == project.id) {
                                currentProject = projects.firstOrNull() ?: defaultProjects().first()
                            }
                        } catch (error: Exception) {
                            ToastHelper.short(context, error.message ?: "Не удалось удалить проект.")
                        }
                    }
                },
                onAgentClick = { currentAgent = it },
                onSettingsClick = { showSettings = true },
                onCouncilClick = { showCouncil = true },
                onTaskQueueClick = { showTaskQueue = true }
            )
            else -> ChatScreen(
                agent = currentAgent!!,
                project = currentProject,
                onBack = { currentAgent = null }
            )
        }
    }
}

@Composable
fun StatusFlower(isOnline: Boolean, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val c = if (isOnline) Color(0xFF55B84A) else Color(0xFF9A9A9A)
        val r = size.toPx() / 4f
        repeat(5) { i ->
            val angle = (i * 72f - 90f) * PI / 180
            drawCircle(
                color = c,
                radius = r,
                center = Offset(
                    x = center.x + cos(angle).toFloat() * r,
                    y = center.y + sin(angle).toFloat() * r
                )
            )
        }
        drawCircle(Color(0xFFFFFFAA), radius = size.toPx() / 6f, center = center)
    }
}

@Composable
fun GroupHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(GrD).clickable { onToggle() }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (expanded) "▼" else "▶", color = Gr, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        Text(title, color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        Text("$count", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun AgentRow(a: A, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .semantics { contentDescription = "Открыть чат ${a.n}" }
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusFlower(a.on, 14.dp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(a.emoji, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(a.n, color = if (a.on) Color(0xFF202020) else Tx, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                if (a.on) "${a.providerId.displayName} · готов" else "Не настроен",
                color = Tx, fontSize = 10.sp
            )
        }
        StatusFlower(a.on, 12.dp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
fun ContactList(
    projects: List<Project>,
    currentProject: Project,
    refreshKey: Int,
    onProjectSelect: (Project) -> Unit,
    onProjectAdd: (String) -> Unit,
    onProjectDelete: (Project) -> Unit,
    onAgentClick: (A) -> Unit,
    onSettingsClick: () -> Unit,
    onCouncilClick: () -> Unit,
    onTaskQueueClick: () -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var notConfiguredAgent by remember { mutableStateOf<A?>(null) }
    var iaExpanded by remember { mutableStateOf(true) }
    var aiExpanded by remember { mutableStateOf(true) }

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    var isOnline by remember { mutableStateOf(cm.activeNetwork != null) }
    var previousOnline by remember { mutableStateOf(isOnline) }
    LaunchedEffect(Unit) {
        while (true) {
            isOnline = cm.activeNetwork != null
            if (isOnline && !previousOnline) SoundHelper.playOnline()
            previousOnline = isOnline
            delay(2000)
        }
    }

    val list = remember(context, refreshKey) {
        listOf(
            A("ГЛАФИРА", "Главный координатор", hasAnyGlafiraProvider(context), "🤖",
                "Ты — Глафира, главный координатор проектов. Ты помогаешь пользователю структурировать задачи, планировать и управлять проектами. Отвечай четко и по делу.",
                "Главный управляющий интеллект. Она не просто отвечает на вопросы, а помогает доводить дела до результата. Умеет разбивать сложные задачи на шаги, распределять их между другими агентами и следить за выполнением.",
                loadGlafiraPrimaryProvider(context), AgentGroup.IA),
            A("АНАЛИТИК", "Исследование и анализ", isProviderConfigured(context, ProviderId.GIGACHAT), "📊",
                "Ты — Аналитик. Твоя задача — глубоко анализировать данные, искать закономерности, строить прогнозы и давать объективные выводы. Будь точен.",
                "Специалист по данным. Занимается исследованием рынков, поиском закономерностей, анализом конкурентов и финансовых показателей. Отвечает за факты и цифры.",
                ProviderId.GIGACHAT, AgentGroup.IA),
            A("ПРОГРАММИСТ", "Код и технические задачи", isProviderConfigured(context, ProviderId.GIGACHAT), "💻",
                "Ты — Программист. Ты пишешь код, находишь баги и предлагаешь архитектурные решения. Твой ответ должен содержать код и краткое объяснение.",
                "Технический специалист. Пишет код на разных языках, проверяет его на ошибки, предлагает архитектуру и помогает с техническими решениями. Отвечает за IT-часть проекта.",
                ProviderId.GIGACHAT, AgentGroup.IA),
            A("GigaChat", "GigaChat · модель", isProviderConfigured(context, ProviderId.GIGACHAT), "🟣",
                "Ты — GigaChat. Отвечай по делу, структурированно, на русском языке.",
                "Прямой доступ к модели GigaChat без роли агента. Универсальный помощник.",
                ProviderId.GIGACHAT, AgentGroup.AI),
            A("ChatGPT", "OpenAI · не настроен", isProviderConfigured(context, ProviderId.OPENAI), "🟢",
                "Ты — ChatGPT от OpenAI. Отвечай чётко, структурированно, с примерами.",
                "Модель OpenAI. Универсальный помощник для анализа, текстов и программирования. Для работы нужен API-ключ OpenAI (платный).",
                ProviderId.OPENAI, AgentGroup.AI),
            A("DeepSeek", "DeepSeek · не настроен", isProviderConfigured(context, ProviderId.DEEPSEEK), "🔵",
                "Ты — DeepSeek. Специализируешься на логике, коде и аналитике.",
                "Модель DeepSeek. Логические задачи, программирование, аналитика. Для работы нужен API-ключ DeepSeek (платный, но дешёвый).",
                ProviderId.DEEPSEEK, AgentGroup.AI),
            A("Grok", "xAI · не настроен", isProviderConfigured(context, ProviderId.GROK), "⚫",
                "Ты — Grok от xAI. Отвечай с лёгкой иронией, но по делу.",
                "Модель xAI. Универсальный помощник. Для работы нужен API-ключ xAI (платный).",
                ProviderId.GROK, AgentGroup.AI),
            A("Алиса", "Yandex · адаптер в разработке", isProviderConfigured(context, ProviderId.YANDEX), "🟡",
                "Ты — Алиса от Яндекс. Отвечай на русском, дружелюбно и по делу.",
                "YandexGPT через Yandex Cloud. Русскоязычные задачи. Для работы нужен API-ключ Yandex Cloud и folder ID.",
                ProviderId.YANDEX, AgentGroup.AI),
            A("Gemini", "Google · бесплатный тариф", isProviderConfigured(context, ProviderId.GEMINI), "✨",
                "Ты — Gemini от Google. Анализируй, объясняй и помогай решать задачи.",
                "Google Gemini API. Для некоторых моделей есть бесплатный тариф с ограничениями; Google может использовать данные бесплатного уровня для улучшения продуктов.",
                ProviderId.GEMINI, AgentGroup.AI),
            A("OpenRouter Free", "Бесплатные модели · лимиты", isProviderConfigured(context, ProviderId.OPENROUTER), "🆓",
                "Ты — ИИ-помощник через OpenRouter. Отвечай точно и структурированно.",
                "Маршрутизатор бесплатных моделей разных разработчиков. Доступность моделей и лимиты меняются; политики обработки данных зависят от выбранного провайдера.",
                ProviderId.OPENROUTER, AgentGroup.AI)
        )
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✿", color = Color.White, fontSize = 17.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("GLAFIRA AI HUB", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("СЕТЬ АГЕНТОВ · 2003", color = Color.White.copy(alpha = 0.88f), fontSize = 9.sp)
            }
            Text("—  □  ×", color = Color.White, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().background(GrD).border(1.dp, Line).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Проект: ", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Box {
                Row(modifier = Modifier.clickable { showProjectMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(currentProject.name, color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(" ▼", color = Gr, fontSize = 8.sp)
                }
                DropdownMenu(expanded = showProjectMenu, onDismissRequest = { showProjectMenu = false }) {
                    projects.forEach { proj ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DropdownMenuItem(
                                text = { Text(proj.name, color = if (proj.id == currentProject.id) Gr else Bg) },
                                onClick = { onProjectSelect(proj); showProjectMenu = false },
                                modifier = Modifier.weight(1f)
                            )
                            if (projects.size > 1) {
                                Text("✕", color = Red, modifier = Modifier.clickable {
                                    if (proj.id == currentProject.id) showDeleteConfirm = true
                                }.padding(8.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(28.dp).border(1.dp, Gr, RoundedCornerShape(2.dp))
                    .clickable { onTaskQueueClick() }
                    .semantics { contentDescription = "Открыть очередь задач" },
                contentAlignment = Alignment.Center
            ) {
                Text("☷", color = Gr, fontSize = 16.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(24.dp).border(1.dp, Gr, RoundedCornerShape(2.dp))
                    .clickable { onCouncilClick() }
                    .semantics { contentDescription = "Открыть совет директоров" },
                contentAlignment = Alignment.Center
            ) {
                Text("🏛", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(24.dp).border(1.dp, Gr, RoundedCornerShape(2.dp)).clickable { showAddDialog = true }, contentAlignment = Alignment.Center) {
                Text("+", color = Gr, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(24.dp).border(1.dp, Gr, RoundedCornerShape(2.dp))
                    .clickable { onSettingsClick() }
                    .semantics { contentDescription = "Открыть настройки" },
                contentAlignment = Alignment.Center
            ) {
                Text("⚙", fontSize = 12.sp)
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Удалить проект?", color = Color(0xFF202020)) },
                text = { Text("Все чаты в этом проекте будут безвозвратно удалены.") },
                confirmButton = {
                    TextButton(onClick = { onProjectDelete(currentProject); showDeleteConfirm = false; showProjectMenu = false }) {
                        Text("Удалить", color = Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена", color = Tx) }
                }
            )
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Новый проект", color = Color(0xFF202020)) },
                text = { TextField(value = newProjectName, onValueChange = { newProjectName = it }, placeholder = { Text("Название...") }) },
                confirmButton = {
                    TextButton(onClick = {
                        if (newProjectName.isNotBlank()) { onProjectAdd(newProjectName); newProjectName = ""; showAddDialog = false }
                    }) { Text("Создать", color = Gr) }
                }
            )
        }

        if (notConfiguredAgent != null) {
            val agent = notConfiguredAgent!!
            AlertDialog(
                onDismissRequest = { notConfiguredAgent = null },
                title = { Text("${agent.n} не настроен", color = Color(0xFF202020)) },
                text = {
                    Column {
                        Text("Провайдер ${agent.providerId.displayName} пока не подключён.")
                        Spacer(Modifier.height(8.dp))
                        Text("Добавьте API-ключ в Настройках, чтобы активировать этот чат.", color = Tx, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { notConfiguredAgent = null; onSettingsClick() }) {
                        Text("В НАСТРОЙКИ", color = Gr)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { notConfiguredAgent = null }) {
                        Text("ЗАКРЫТЬ", color = Tx)
                    }
                }
            )
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.fillMaxWidth().background(Color.White).border(1.dp, Line)) {
                item {
                    GroupHeader(
                        title = AgentGroup.IA.title,
                        count = list.count { it.group == AgentGroup.IA },
                        expanded = iaExpanded,
                        onToggle = { iaExpanded = !iaExpanded }
                    )
                }
                if (iaExpanded) {
                    val iaList = list.filter { it.group == AgentGroup.IA }
                    itemsIndexed(
                        items = iaList,
                        key = { _, a -> a.n }
                    ) { _, a ->
                        AgentRow(a) { if (!a.on) notConfiguredAgent = a else onAgentClick(a) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    GroupHeader(
                        title = AgentGroup.AI.title,
                        count = list.count { it.group == AgentGroup.AI },
                        expanded = aiExpanded,
                        onToggle = { aiExpanded = !aiExpanded }
                    )
                }
                if (aiExpanded) {
                    val aiList = list.filter { it.group == AgentGroup.AI }
                    itemsIndexed(
                        items = aiList,
                        key = { _, a -> a.n }
                    ) { _, a ->
                        AgentRow(a) { if (!a.on) notConfiguredAgent = a else onAgentClick(a) }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().background(GrD).border(1.dp, Line).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusFlower(isOnline, 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (isOnline) "ONLINE | ${currentProject.name}" else "OFFLINE | ${currentProject.name}",
                color = Tx, fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(agent: A, project: Project, onBack: () -> Unit) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf(loadDraft(context, project.id, agent.n)) }
    val historyKey = "${project.id}_${agent.n}"
    var selectedPhotoUri by remember(historyKey) { mutableStateOf<Uri?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> selectedPhotoUri = uri }
    var messages by remember(historyKey) { mutableStateOf(loadHistory(context, historyKey)) }
    var pendingMessages by remember(historyKey) { mutableStateOf(emptyList<PendingMessage>()) }
    var historyInitialized by remember(historyKey) { mutableStateOf(false) }
    var lastObservedMessage by remember(historyKey) { mutableStateOf<Msg?>(messages.lastOrNull()) }
    var retryConfirmation by remember { mutableStateOf<PendingMessage?>(null) }
    LaunchedEffect(historyKey) {
        while (true) {
            withContext(Dispatchers.IO) {
                loadHistory(context, historyKey) to loadPendingQueue(context).filter {
                    it.projectId == project.id && it.agentName == agent.n && it.state != PendingDeliveryState.COMPLETED && it.state != PendingDeliveryState.CANCELLED
                }
            }.let { (history, pending) ->
                messages = history
                pendingMessages = pending
                if (!historyInitialized) {
                    lastObservedMessage = history.lastOrNull()
                    historyInitialized = true
                }
            }
            delay(1_000)
        }
    }
    val isLoading = pendingMessages.any {
        it.state in setOf(PendingDeliveryState.QUEUED, PendingDeliveryState.IN_FLIGHT, PendingDeliveryState.RESULT_READY)
    }
    var showProfile by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showTransferDialog by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(loadPinned(context, historyKey)) }
    var showPinnedPanel by remember { mutableStateOf(false) }
    var messageActionIndex by remember { mutableStateOf<Int?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var expandedMessages by remember { mutableStateOf(setOf<Int>()) }
    var isListening by remember { mutableStateOf(false) }
    var speakingIndex by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val knowledgeRepository = remember(context.applicationContext) {
        GlafiraProjectKnowledgeRuntime.repository(context)
    }
    val knowledgeImporter = remember(context.applicationContext, knowledgeRepository) {
        AndroidProjectKnowledgeImporter(context, knowledgeRepository)
    }
    val knowledgePreviewService = remember(knowledgeRepository) {
        KnowledgeContextPreviewService(knowledgeRepository)
    }
    val chatContextAssembler = remember(context.filesDir) {
        ProjectChatContextAssembler(context.filesDir) { key -> loadHistory(context, key) }
    }
    val contextDispatchPlanner = remember(knowledgePreviewService, chatContextAssembler) {
        OwnerApprovedContextDispatchPlanner(knowledgePreviewService, chatContextAssembler)
    }
    val citationManifestStore = remember(context.applicationContext) {
        GlafiraContextCitationManifestRuntime.store(context)
    }
    var showKnowledgeImportDialog by remember { mutableStateOf(false) }
    var showKnowledgePreview by remember { mutableStateOf(false) }
    var knowledgePreview by remember { mutableStateOf<KnowledgeContextPreview?>(null) }
    var chatContextPreview by remember { mutableStateOf<ProjectChatContextPreview?>(null) }
    var knowledgePreviewError by remember { mutableStateOf<String?>(null) }
    var knowledgePreviewLoading by remember { mutableStateOf(false) }
    var importClassification by remember { mutableStateOf(CoreDataClassification.INTERNAL) }
    var excludedKnowledgeSources by remember(project.id) { mutableStateOf(emptySet<KnowledgeSourceRef>()) }
    var excludedChatSources by remember(project.id) { mutableStateOf(emptySet<ChatContextSourceRef>()) }
    var approvedContextQuery by remember(project.id) { mutableStateOf<String?>(null) }
    var approvedKnowledgeSources by remember(project.id) { mutableStateOf(emptySet<KnowledgeSourceRef>()) }
    var approvedChatSources by remember(project.id) { mutableStateOf(emptySet<ChatContextSourceRef>()) }
    var visibleCitationManifest by remember { mutableStateOf<ContextCitationManifest?>(null) }
    var visibleKnowledgeDocument by remember(project.id) { mutableStateOf<KnowledgeDocumentSnapshot?>(null) }

    val recipientName = if (agent.n == "ГЛАФИРА") {
        loadGlafiraPrimaryProvider(context).displayName
    } else {
        agent.providerId.displayName
    }
    val knowledgeAccess = KnowledgeAccessContext(
        actorId = "local-owner",
        projectId = ProjectIdentifier(project.id),
        maximumClassification = CoreDataClassification.RESTRICTED
    )
    val citationAccess = ContextCitationAccess(ProjectIdentifier(project.id), "local-owner")

    fun openCitationSource(citation: DocumentCitationReference) {
        val source = runCatching {
            KnowledgeSourceRef(KnowledgeSourceIdentifier(citation.sourceId), citation.version)
        }.getOrNull()
        val snapshot = source?.let { knowledgeRepository.readDocument(it, knowledgeAccess) }
        if (snapshot == null || snapshot.title != citation.title ||
            snapshot.sourceUri != citation.sourceUri ||
            snapshot.classification != citation.classification
        ) {
            ToastHelper.short(context, "Зафиксированная версия источника недоступна в этом проекте")
            return
        }
        visibleKnowledgeDocument = snapshot
    }

    fun loadKnowledgePreview() {
        val query = inputText.trim()
        if (query.isBlank()) {
            ToastHelper.short(context, "Сначала введите вопрос для подбора контекста")
            return
        }
        showKnowledgePreview = true
        knowledgePreviewLoading = true
        knowledgePreviewError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    knowledgePreviewService.preview(
                        query = query,
                        recipient = recipientName,
                        access = knowledgeAccess
                    ) to chatContextAssembler.preview(
                        projectId = ProjectIdentifier(project.id),
                        currentAgentName = agent.n,
                        recipient = recipientName
                    )
                }
            }
            knowledgePreviewLoading = false
            result.onSuccess { (documentPreview, chatPreview) ->
                knowledgePreview = documentPreview
                chatContextPreview = chatPreview
                excludedKnowledgeSources = excludedKnowledgeSources.intersect(
                    documentPreview.citations.map { it.source }.toSet()
                )
                excludedChatSources = excludedChatSources.intersect(
                    chatPreview.citations.map { it.source }.toSet()
                )
            }.onFailure {
                knowledgePreview = null
                chatContextPreview = null
                knowledgePreviewError = "Контекст недоступен. Сообщение не будет использовать документы."
            }
        }
    }

    LaunchedEffect(Unit) {
        TtsHelper.init(context)
    }

    LaunchedEffect(historyInitialized, messages.lastOrNull()) {
        if (!historyInitialized) return@LaunchedEffect
        val latest = messages.lastOrNull() ?: return@LaunchedEffect
        val previousMessage = lastObservedMessage
        if (latest != previousMessage) {
            lastObservedMessage = latest
            val autoSpeak = context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
                .getBoolean("auto_speak_responses", false)
            if (shouldAutoSpeakAgentReply(previousMessage, latest, autoSpeak)) {
                TtsHelper.speak(latest.text) { speakingIndex = null }
                speakingIndex = messages.lastIndex
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = spoken?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                inputText = (inputText + " " + text).trim()
                saveDraft(context, project.id, agent.n, inputText)
            }
        }
    }

    fun launchSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            ToastHelper.short(context, "Распознавание речи недоступно")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите...")
        }
        isListening = true
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            isListening = false
            ToastHelper.short(context, "Не удалось запустить распознавание")
        }
    }

    // Microphone access is requested only after the owner explicitly taps the voice control.
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSpeechRecognition()
        } else {
            isListening = false
            ToastHelper.short(context, "Без разрешения микрофона голосовой ввод недоступен")
        }
    }

    val knowledgeDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val receipt = withContext(Dispatchers.IO) {
                knowledgeImporter.importDocument(
                    uri = uri,
                    projectId = ProjectIdentifier(project.id),
                    classification = importClassification
                )
            }
            when (val result = receipt.result) {
                is KnowledgeImportResult.Stored -> {
                    approvedContextQuery = null
                    approvedKnowledgeSources = emptySet()
                    approvedChatSources = emptySet()
                    ToastHelper.short(context, "Документ добавлен: ${receipt.displayName}")
                }
                is KnowledgeImportResult.Rejected -> ToastHelper.short(
                    context,
                    when (result.reasonCode) {
                        "KNOWLEDGE_IMPORT_TOO_LARGE" -> "Документ больше 512 КБ"
                        "KNOWLEDGE_MEDIA_TYPE_NOT_SUPPORTED" -> "Поддерживаются только TXT и Markdown"
                        "KNOWLEDGE_TEXT_INVALID_UTF8" -> "Документ должен быть в UTF-8"
                        "KNOWLEDGE_VERSION_ALREADY_EXISTS" -> "Эта версия документа уже добавлена"
                        else -> "Не удалось безопасно добавить документ"
                    }
                )
            }
        }
    }

    fun startVoiceInput() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchSpeechRecognition()
    }

    val displayedMessages = if (searchQuery.isBlank()) messages else messages.filter { it.text.contains(searchQuery, ignoreCase = true) }

    LaunchedEffect(messages.size) {
        if (!isSearching && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size + pendingMessages.size - 1)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading) return
        if (isPendingDispatchBlocked(context)) {
            ToastHelper.short(context, "Сначала завершите восстановление резервной копии в настройках.")
            return
        }
        val now = Date()
        val dispatch = if (
            approvedContextQuery == text.trim() &&
            (approvedKnowledgeSources.isNotEmpty() || approvedChatSources.isNotEmpty())
        ) {
            runCatching {
                contextDispatchPlanner.prepare(
                    selection = OwnerApprovedContextSelection(
                        query = text.trim(),
                        documentSources = approvedKnowledgeSources,
                        chatSources = approvedChatSources
                    ),
                    recipient = recipientName,
                    currentAgentName = agent.n,
                    access = knowledgeAccess
                )
            }.getOrElse {
                ToastHelper.short(context, "Выбранный контекст изменился. Проверьте источники заново.")
                return
            }
        } else null
        val fullPrompt = dispatch?.primarySystemPrompt(agent.prompt) ?: agent.prompt
        val pending = PendingMessage(
            project.id, agent.n, text,
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            if (agent.n == "ГЛАФИРА") loadGlafiraPrimaryProvider(context).name else agent.providerId.name,
            date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now),
            systemPrompt = fullPrompt
        )
        val citationManifest = dispatch?.manifest(pending.id, Instant.ofEpochMilli(now.time))
        try {
            // Source provenance must be durable before the provider operation can begin.
            citationManifest?.let { citationManifestStore.put(it, citationAccess) }
            // Outbox precedes the call; a process death cannot erase the operation.
            try {
                addToPendingQueue(context, pending)
            } catch (error: Exception) {
                citationManifest?.let { citationManifestStore.delete(pending.id, citationAccess) }
                throw error
            }
            messages = appendPendingUserMessage(context, pending)
            if (inputText == text) {
                clearDraft(context, project.id, agent.n)
                inputText = ""
            }
            approvedContextQuery = null
            approvedKnowledgeSources = emptySet()
            approvedChatSources = emptySet()
        } catch (error: Exception) {
            ToastHelper.short(context, "Не удалось сохранить сообщение и его источники. Черновик оставлен.")
            return
        }
        pendingMessages = pendingMessages + pending
        SoundHelper.playSent()
        PendingDeliveryRuntime.dispatch(context, pending, fullPrompt)
    }

    if (showKnowledgeImportDialog) {
        AlertDialog(
            onDismissRequest = { showKnowledgeImportDialog = false },
            title = { Text("Добавить документ в проект") },
            text = {
                Column {
                    Text("Файл будет зашифрован и доступен только в проекте «${project.name}».")
                    Spacer(Modifier.height(8.dp))
                    Text("Классификация", fontWeight = FontWeight.Bold)
                    CoreDataClassification.values().forEach { classification ->
                        Row(
                            Modifier.fillMaxWidth().clickable { importClassification = classification },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = importClassification == classification,
                                onClick = { importClassification = classification }
                            )
                            Text(
                                when (classification) {
                                    CoreDataClassification.PUBLIC -> "Публичный"
                                    CoreDataClassification.INTERNAL -> "Внутренний"
                                    CoreDataClassification.CONFIDENTIAL -> "Конфиденциальный"
                                    CoreDataClassification.RESTRICTED -> "Ограниченный"
                                }
                            )
                        }
                    }
                    Text("Поддерживаются TXT и Markdown до 512 КБ.", color = Tx, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showKnowledgeImportDialog = false
                    knowledgeDocumentPicker.launch(arrayOf("text/plain", "text/markdown"))
                }) { Text("ВЫБРАТЬ ФАЙЛ") }
            },
            dismissButton = {
                TextButton(onClick = { showKnowledgeImportDialog = false }) { Text("ОТМЕНА") }
            }
        )
    }

    if (showKnowledgePreview) {
        AlertDialog(
            onDismissRequest = { showKnowledgePreview = false },
            title = { Text("Контекст перед отправкой") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 500.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    Text("Проект: ${project.name}", fontWeight = FontWeight.Bold)
                    Text("Получатель: $recipientName")
                    Text("Документы: до 4 000 символов/5 источников. Чаты: до 4 000/10.", color = Tx, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    when {
                        knowledgePreviewLoading -> CircularProgressIndicator(Modifier.size(24.dp))
                        knowledgePreviewError != null -> Text(knowledgePreviewError.orEmpty(), color = Red)
                        else -> {
                            if (
                                knowledgePreview?.citations.isNullOrEmpty() &&
                                chatContextPreview?.citations.isNullOrEmpty()
                            ) {
                                Text("Подходящих источников проекта не найдено.")
                            }
                            if (!knowledgePreview?.citations.isNullOrEmpty()) {
                                Text("ДОКУМЕНТЫ", color = Gr, fontWeight = FontWeight.Bold)
                                knowledgePreview!!.citations.forEach { citation ->
                                    val included = citation.source !in excludedKnowledgeSources
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            excludedKnowledgeSources = if (included) {
                                                excludedKnowledgeSources + citation.source
                                            } else {
                                                excludedKnowledgeSources - citation.source
                                            }
                                        },
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Checkbox(
                                            checked = included,
                                            onCheckedChange = { checked ->
                                                excludedKnowledgeSources = if (checked) {
                                                    excludedKnowledgeSources - citation.source
                                                } else {
                                                    excludedKnowledgeSources + citation.source
                                                }
                                            }
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(citation.title, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${citation.source.sourceId.value} · v${citation.source.version} · ${citation.classification}",
                                                color = Tx,
                                                fontSize = 10.sp
                                            )
                                            Text(citation.excerpt, fontSize = 11.sp)
                                            Text(citation.sourceUri, color = Gr, fontSize = 9.sp)
                                        }
                                    }
                                    Divider(Modifier.padding(vertical = 6.dp))
                                }
                            }
                            if (!chatContextPreview?.citations.isNullOrEmpty()) {
                                Text("ДРУГИЕ ЧАТЫ ПРОЕКТА", color = Gr, fontWeight = FontWeight.Bold)
                                chatContextPreview!!.citations.forEach { citation ->
                                    val included = citation.source !in excludedChatSources
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            excludedChatSources = if (included) {
                                                excludedChatSources + citation.source
                                            } else {
                                                excludedChatSources - citation.source
                                            }
                                        },
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Checkbox(
                                            checked = included,
                                            onCheckedChange = { checked ->
                                                excludedChatSources = if (checked) {
                                                    excludedChatSources - citation.source
                                                } else {
                                                    excludedChatSources + citation.source
                                                }
                                            }
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text("Чат с ${citation.agentName}", fontWeight = FontWeight.Bold)
                                            Text(
                                                "${citation.source.sourceId} · v${citation.source.contentVersion.take(12)} · ${citation.classification}",
                                                color = Tx,
                                                fontSize = 10.sp
                                            )
                                            Text(citation.excerpt, fontSize = 11.sp)
                                        }
                                    }
                                    Divider(Modifier.padding(vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !knowledgePreviewLoading && knowledgePreviewError == null,
                    onClick = {
                        val selectedDocuments = knowledgePreview?.citations.orEmpty()
                            .map { it.source }
                            .filterNot { it in excludedKnowledgeSources }
                            .toSet()
                        val selectedChats = chatContextPreview?.citations.orEmpty()
                            .map { it.source }
                            .filterNot { it in excludedChatSources }
                            .toSet()
                        approvedContextQuery = inputText.trim()
                        approvedKnowledgeSources = selectedDocuments
                        approvedChatSources = selectedChats
                        showKnowledgePreview = false
                        val total = selectedDocuments.size + selectedChats.size
                        ToastHelper.short(
                            context,
                            if (total == 0) "Проектный контекст не будет добавлен"
                            else "Разрешено источников: $total"
                        )
                    }
                ) { Text("ИСПОЛЬЗОВАТЬ ВЫБРАННОЕ") }
            },
            dismissButton = {
                TextButton(onClick = { showKnowledgePreview = false }) { Text("НАЗАД") }
            }
        )
    }

    visibleCitationManifest?.let { manifest ->
        AlertDialog(
            onDismissRequest = { visibleCitationManifest = null },
            title = { Text("Источники ответа") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("Получатель: ${manifest.recipient}", fontSize = 11.sp, color = Tx)
                    Text("Операция: ${manifest.operationId}", fontSize = 9.sp, color = Tx)
                    Text(
                        "Документы ниже подтверждают использованные факты. Остальной текст ответа может быть выводом модели.",
                        fontSize = 10.sp,
                        color = Tx
                    )
                    if (manifest.documents.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Документы", fontWeight = FontWeight.Bold)
                        manifest.documents.forEach { citation ->
                            Text(
                                "${citation.title} · ${citation.sourceId} · v${citation.version} · ${citation.classification}",
                                fontSize = 11.sp,
                                color = Gr,
                                modifier = Modifier
                                    .semantics { contentDescription = "Открыть источник ${citation.title}" }
                                    .clickable { openCitationSource(citation) }
                                    .padding(vertical = 5.dp)
                            )
                        }
                    }
                    if (manifest.chats.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Чаты проекта", fontWeight = FontWeight.Bold)
                        manifest.chats.forEach { citation ->
                            Text(
                                "${citation.agentName} · ${citation.sourceId} · ${citation.contentVersion.take(12)} · ${citation.classification}",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Сохранены только идентификаторы и версии источников; выдержки и текст запроса сюда не записываются.", fontSize = 10.sp, color = Tx)
                }
            },
            confirmButton = {
                TextButton(onClick = { visibleCitationManifest = null }) { Text("ЗАКРЫТЬ") }
            }
        )
    }

    visibleKnowledgeDocument?.let { document ->
        AlertDialog(
            onDismissRequest = { visibleKnowledgeDocument = null },
            title = { Text(document.title) },
            text = {
                Column(
                    Modifier.heightIn(max = 520.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    Text(
                        "ИСТОЧНИК · ${document.source.sourceId.value} · v${document.source.version}",
                        fontSize = 10.sp,
                        color = Gr,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${document.classification} · импорт ${document.importedAt}",
                        fontSize = 9.sp,
                        color = Tx
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(document.text, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Показана сохранённая зашифрованная версия, использованная в ответе; внешний файл мог измениться.",
                        fontSize = 10.sp,
                        color = Tx
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { visibleKnowledgeDocument = null }) { Text("ЗАКРЫТЬ") }
            }
        )
    }

    retryConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { retryConfirmation = null },
            title = { Text("Повторить запрос?") },
            text = { Text("Предыдущий запрос мог уже выполниться у провайдера. Повтор создаст новый вызов и может повторно израсходовать квоту.") },
            confirmButton = { TextButton(onClick = {
                pendingMessageStore(context).retry(pending.id)
                retryConfirmation = null
            }) { Text("Повторить") } },
            dismissButton = { TextButton(onClick = { retryConfirmation = null }) { Text("Назад") } }
        )
    }

    if (showProfile) {
        ProfileScreen(agent = agent, onBack = { showProfile = false })
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFFDCE8F4)).border(1.dp, Line).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isSearching) {
                    TextField(
                        value = searchQuery, onValueChange = { searchQuery = it },
                        placeholder = { Text("Поиск...", color = Tx, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedTextColor = Color(0xFF202020), unfocusedTextColor = Color(0xFF202020), focusedIndicatorColor = Gr)
                    )
                    Text("✕", color = Gr, fontSize = 16.sp, modifier = Modifier.clickable { isSearching = false; searchQuery = "" }.padding(start = 8.dp))
                } else {
                    Text("←", color = Gr, fontSize = 18.sp, modifier = Modifier.clickable { onBack() }.padding(end = 10.dp))
                    Row(Modifier.weight(1f).clickable { showProfile = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text(agent.emoji, fontSize = 16.sp)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(agent.n, color = Gr, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(
                                if (agent.on) "Online · ${agent.providerId.displayName} · ${messages.size} сообщ." else "Offline",
                                color = Tx, fontSize = 8.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text("⬆", fontSize = 14.sp, color = Gr, modifier = Modifier.clickable { scope.launch { listState.animateScrollToItem(0) } }.padding(end = 8.dp))
                    Text("📌", fontSize = 14.sp, modifier = Modifier.clickable { showPinnedPanel = !showPinnedPanel }.padding(end = 8.dp))
                    Text(
                        "📎",
                        fontSize = 14.sp,
                        modifier = Modifier
                            .semantics { contentDescription = "Добавить документ в проект" }
                            .clickable { showKnowledgeImportDialog = true }
                            .padding(end = 8.dp)
                    )
                    Text(
                        if (
                            approvedContextQuery == inputText.trim() &&
                            (approvedKnowledgeSources.isNotEmpty() || approvedChatSources.isNotEmpty())
                        ) {
                            "📚${approvedKnowledgeSources.size + approvedChatSources.size}"
                        } else "📚",
                        fontSize = 14.sp,
                        color = if (approvedKnowledgeSources.isNotEmpty() || approvedChatSources.isNotEmpty()) Gr else Tx,
                        modifier = Modifier
                            .semantics { contentDescription = "Просмотреть контекст перед отправкой" }
                            .clickable { loadKnowledgePreview() }
                            .padding(end = 8.dp)
                    )
                    Text("🔍", fontSize = 14.sp, modifier = Modifier.clickable { isSearching = true }.padding(end = 8.dp))
                    Text("↗", fontSize = 16.sp, color = Gr, modifier = Modifier.clickable { showTransferDialog = true }.padding(end = 8.dp))
                    Text("🔇", fontSize = 14.sp, modifier = Modifier.clickable {
                        TtsHelper.stop()
                        speakingIndex = null
                    }.padding(end = 8.dp))
                    Text("📋", fontSize = 14.sp, modifier = Modifier.clickable {
                        copyToClipboard(context, messages.joinToString("\n") { m -> val who = if (m.isUser) "Вы" else agent.n; "[${m.date} ${m.time}] $who: ${m.text}" })
                    }.padding(end = 8.dp))
                    Text("📤", fontSize = 14.sp, modifier = Modifier.clickable { exportChatToFile(context, project.name, agent.n, messages) }.padding(end = 8.dp))
                    Text("🗑", fontSize = 14.sp, modifier = Modifier.clickable { showClearConfirm = true })
                }
            }

            if (showPinnedPanel && pinned.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GrD),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("📌 ЗАКРЕПЛЁННЫЕ", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        pinned.sorted().forEach { idx ->
                            if (idx < messages.size) {
                                val m = messages[idx]
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        m.text.take(80) + if (m.text.length > 80) "…" else "",
                                        color = Color(0xFF202020), fontSize = 10.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("✕", color = Red, fontSize = 12.sp, modifier = Modifier.clickable {
                                        pinned = pinned - idx
                                        savePinned(context, historyKey, pinned)
                                    }.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(pendingMessages) { _, pending ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                        Text(
                            when (pending.state) {
                                PendingDeliveryState.QUEUED -> "⏳ В очереди: ${pending.text.take(60)}. Попытка ${pending.attempts}/${PendingMessageStore.MAX_ATTEMPTS}"
                                PendingDeliveryState.IN_FLIGHT -> "Запрос выполняется: ${pending.text.take(60)}"
                                PendingDeliveryState.RESULT_READY -> "Результат сохранён; восстанавливаем историю."
                                else -> pending.error ?: "Запрос требует проверки."
                            }, color = Tx, fontSize = 11.sp
                        )
                        Row {
                            if (pending.state == PendingDeliveryState.UNCERTAIN || pending.state == PendingDeliveryState.FAILED) {
                                TextButton(onClick = { retryConfirmation = pending }) { Text("Повторить…") }
                            }
                            if (pending.state != PendingDeliveryState.IN_FLIGHT && pending.state != PendingDeliveryState.RESULT_READY) {
                                TextButton(onClick = { pendingMessageStore(context).cancel(pending.id) }) { Text("Отменить") }
                            }
                        }
                    }
                }

                if (searchQuery.isBlank() && messages.isNotEmpty()) {
                    item {
                        Text("— ${dateLabel(messages.first().date)} —", color = Tx, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                items(displayedMessages.size) { displayIdx ->
                    val msg = displayedMessages[displayIdx]
                    val realIdx = messages.indexOf(msg)
                    val isPinned = realIdx in pinned
                    val isExpanded = realIdx in expandedMessages
                    val isLong = isLongMessage(msg.text)
                    val prevDate = if (realIdx > 0) messages[realIdx - 1].date else ""
                    val showDateDivider = searchQuery.isBlank() && realIdx > 0 && prevDate != msg.date
                    if (showDateDivider) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Text("— ${dateLabel(msg.date)} —", color = Tx, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start) {
                        Column(horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start) {
                            val shape = if (msg.isUser) {
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 0.dp)
                            } else {
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 0.dp, bottomEnd = 6.dp)
                            }
                            Box(
                                Modifier.clip(shape)
                                    .background(if (msg.isUser) Color(0xFFE5F0FA) else Color.White)
                                    .border(1.dp, if (msg.isUser) Color(0xFF8BAFD0) else Color(0xFFB8C9D8), shape)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { messageActionIndex = realIdx }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Column {
                                    Text(
                                        if (isLong && !isExpanded) collapsedText(msg.text) else msg.text,
                                        color = Color(0xFF202020),
                                        fontSize = 13.sp
                                    )
                                    if (isLong) {
                                        Text(
                                            if (isExpanded) "Свернуть" else "Показать полностью",
                                            color = Tx, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.clickable {
                                                expandedMessages = if (isExpanded) expandedMessages - realIdx else expandedMessages + realIdx
                                            }.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPinned) Text("📌 ", fontSize = 8.sp)
                                Text(msg.time, color = Tx, fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 2.dp))
                                if (!msg.isUser) {
                                    Spacer(Modifier.width(6.dp))
                                    if (msg.operationId.isNotBlank() && !msg.operationId.endsWith(":partial")) {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    val manifest = withContext(Dispatchers.IO) {
                                                        citationManifestStore.get(msg.operationId, citationAccess)
                                                    }
                                                    if (manifest == null) {
                                                        ToastHelper.short(context, "Для этого ответа источники проекта не использовались")
                                                    } else {
                                                        visibleCitationManifest = manifest
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .semantics(mergeDescendants = true) {
                                                    contentDescription = "Показать источники ответа"
                                                }
                                        ) {
                                            Text("📚", fontSize = 12.sp)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        if (speakingIndex == realIdx) "⏹" else "🔊",
                                        fontSize = 10.sp,
                                        modifier = Modifier.clickable {
                                            if (speakingIndex == realIdx) {
                                                TtsHelper.stop()
                                                speakingIndex = null
                                            } else {
                                                TtsHelper.speak(msg.text) { speakingIndex = null }
                                                speakingIndex = realIdx
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                if (isLoading) { item { TypingIndicator(agentName = agent.n) } }
                if (isSearching && displayedMessages.isEmpty()) { item { Text("Ничего не найдено", color = Tx, modifier = Modifier.padding(12.dp), fontSize = 12.sp) } }
                if (!isLoading && displayedMessages.isNotEmpty() && !displayedMessages.last().isUser && searchQuery.isBlank()) {
                    item { QuickActionsRow(onAction = { sendMessage(it) }) }
                }
            }

            if (!isSearching) {
                Column(Modifier.fillMaxWidth().background(GrD)) {
                    selectedPhotoUri?.let {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "ФОТО ВЫБРАНО · обработка изображения ещё не подключена",
                                color = Tx,
                                fontSize = 10.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "✕",
                                color = Red,
                                modifier = Modifier.clickable { selectedPhotoUri = null }.padding(horizontal = 8.dp)
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).border(1.dp, if (isListening) Red else Gr, RoundedCornerShape(2.dp)).clickable { startVoiceInput() },
                        contentAlignment = Alignment.Center
                    ) { Text(if (isListening) "🔴" else "🎤", fontSize = 16.sp) }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.size(36.dp).border(1.dp, Gr, RoundedCornerShape(2.dp)).clickable {
                            photoPickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        contentAlignment = Alignment.Center
                    ) { Text("🖼️", fontSize = 16.sp) }
                    Spacer(Modifier.width(6.dp))
                    TextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            saveDraft(context, project.id, agent.n, it)
                        },
                        placeholder = { Text("Введите сообщение...", color = Tx, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedTextColor = Color(0xFF202020), unfocusedTextColor = Color(0xFF202020), focusedIndicatorColor = Gr, unfocusedIndicatorColor = Line)
                    )
                    Box(
                        Modifier.size(36.dp).border(1.dp, Gr, RoundedCornerShape(2.dp)).clickable {
                            if (inputText.isNotBlank()) {
                                sendMessage(inputText)
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) { Text("➤", color = Gr, fontSize = 16.sp) }
                    }
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("Очистить чат?", color = Color(0xFF202020)) },
                text = { Text("Все сообщения и закрепления в этом чате будут удалены безвозвратно.") },
                confirmButton = {
                    TextButton(onClick = {
                        clearChat(context, historyKey)
                        messages = emptyList()
                        pinned = emptySet()
                        showClearConfirm = false
                    }) { Text("УДАЛИТЬ", color = Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("ОТМЕНА", color = Tx) }
                }
            )
        }

        if (messageActionIndex != null) {
            val idx = messageActionIndex!!
            val msg = if (idx < messages.size) messages[idx] else null
            AlertDialog(
                onDismissRequest = { messageActionIndex = null },
                title = { Text("Действие", color = Color(0xFF202020)) },
                text = {
                    Column {
                        if (msg != null) {
                            Text(msg.text.take(120) + if (msg.text.length > 120) "…" else "", color = Tx, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Копировать", color = Gr, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable {
                                copyToClipboard(context, msg.text)
                                messageActionIndex = null
                            }.padding(vertical = 6.dp))
                            val isPinned = idx in pinned
                            Text(if (isPinned) "Открепить" else "Закрепить", color = Gr, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable {
                                pinned = if (isPinned) pinned - idx else pinned + idx
                                savePinned(context, historyKey, pinned)
                                messageActionIndex = null
                            }.padding(vertical = 6.dp))
                            if (!msg.isUser) {
                                Text("Озвучить", color = Gr, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable {
                                    TtsHelper.speak(msg.text) { speakingIndex = null }
                                    speakingIndex = idx
                                    messageActionIndex = null
                                }.padding(vertical = 6.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { messageActionIndex = null }) { Text("ЗАКРЫТЬ", color = Tx) }
                }
            )
        }

        if (showTransferDialog) {
            val allAgents = listOf(
                "ГЛАФИРА" to "Ты — Глафира, главный координатор проектов.",
                "АНАЛИТИК" to "Ты — Аналитик.",
                "ПРОГРАММИСТ" to "Ты — Программист.",
                "GigaChat" to "Ты — GigaChat."
            ).filter { it.first != agent.n }
            AlertDialog(
                onDismissRequest = { showTransferDialog = false },
                title = { Text("Передать агенту", color = Color(0xFF202020)) },
                text = {
                    Column {
                        Text("Последние 5 сообщений будут переданы выбранному агенту в этот же проект.", color = Tx, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        allAgents.forEach { (name, _) ->
                            Text(
                                "→ $name",
                                color = Gr,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val tail = messages.takeLast(5)
                                    val transferText = tail.joinToString("\n") { m ->
                                        val who = if (m.isUser) "Пользователь" else agent.n
                                        "$who: ${m.text}"
                                    }
                                    val targetKey = "${project.id}_$name"
                                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                    val sysMsg = Msg("[Передано от ${agent.n}]:\n$transferText", false, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), today)
                                    appendHistory(context, targetKey, listOf(sysMsg))
                                    showTransferDialog = false
                                    copyToClipboard(context, "Передано агенту $name")
                                }.padding(vertical = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTransferDialog = false }) {
                        Text("ОТМЕНА", color = Tx)
                    }
                }
            )
        }
    }
}

@Composable
fun QuickActionsRow(onAction: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val actions = listOf(
            "Продолжить" to "Продолжи.",
            "Подробнее" to "Раскрой подробнее.",
            "Пример" to "Дай пример.",
            "Короче" to "Сократи ответ.",
            "Другой" to "Дай другой вариант."
        )
        actions.forEach { (label, prompt) ->
            Box(
                Modifier.border(1.dp, Gr.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    .clickable { onAction(prompt) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(label, color = Gr, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun TypingIndicator(agentName: String = "Глафира") {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha1 by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse))
    val alpha2 by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = 200, easing = LinearEasing), RepeatMode.Reverse))
    val alpha3 by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = 400, easing = LinearEasing), RepeatMode.Reverse))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        Text("$agentName печатает", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(2.dp))
        Text(".", color = Gr.copy(alpha = alpha1), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(".", color = Gr.copy(alpha = alpha2), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(".", color = Gr.copy(alpha = alpha3), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ProfileScreen(agent: A, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { onBack() }.padding(end = 10.dp))
            Text("ПРОФИЛЬ АГЕНТА", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(80.dp).clip(CircleShape).background(Gr.copy(alpha = 0.2f)).border(2.dp, Gr, CircleShape), contentAlignment = Alignment.Center) {
                Text(agent.emoji, fontSize = 40.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(agent.n, color = Gr, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(agent.d, color = Tx, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusFlower(agent.on, 10.dp)
                Spacer(Modifier.width(4.dp))
                Text(if (agent.on) "В сети" else "Не в сети", color = if (agent.on) Gr else Red, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GrD), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("ОПИСАНИЕ", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text(agent.fullDesc, color = Color(0xFF202020), fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GrD), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("СПЕЦИФИКА РАБОТЫ", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text(agent.prompt, color = Color(0xFF202020), fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

object ToastHelper {
    fun short(context: Context, text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}

suspend fun callGigaChatWithTrustedCert(context: Context, history: List<Msg>, systemPrompt: String): String =
    callAiProvider(context, ProviderId.GIGACHAT, history, systemPrompt)
