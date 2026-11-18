package com.micronizationlab.glafira

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
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
data class Msg(val text: String, val isUser: Boolean, val time: String, val date: String = "", val attachmentPath: String = "", val attachmentName: String = "", val attachmentMime: String = "", val attachmentSize: Long = 0L)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

@Composable
fun App() {
    var currentAgent by remember { mutableStateOf<A?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCouncil by remember { mutableStateOf(false) }
    var statusRefresh by remember { mutableStateOf(0) }
    val context = LocalContext.current
    var projects by remember { mutableStateOf(loadProjects(context)) }
    var currentProject by remember { mutableStateOf(projects.first()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(context) {
        SoundHelper.init(context)
        val activity = context as? Activity
        val incomingIntent = activity?.intent
        if (incomingIntent?.action == Intent.ACTION_SEND) {
            val received = withContext(Dispatchers.IO) { IncomingFileHelper.importSharedFile(context, incomingIntent) }
            if (received != null) {
                val defaultProject = loadProjects(context).first()
                val key = "${defaultProject.id}_ГЛАФИРА"
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                saveHistory(context, key, loadHistory(context, key) + received.copy(time = now, date = today))
                currentAgent = A("ГЛАФИРА", "Главный координатор", true, "🤖", "Ты — Глафира, главный координатор проектов. Отвечай четко и по делу.", "Главный управляющий интеллект.", ProviderId.GIGACHAT, AgentGroup.IA)
                SoundHelper.playIncomingFile()
                activity.intent = Intent(activity, MainActivity::class.java)
            }
        }
    }

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
        val queue = loadPendingQueue(context)
        if (queue.isNotEmpty()) {
            val remaining = mutableListOf<PendingMessage>()
            queue.forEach { pm ->
                val agentPrompt = when (pm.agentName) {
                    "ГЛАФИРА" -> "Ты — Глафира, главный координатор проектов. Ты помогаешь пользователю структурировать задачи, планировать и управлять проектами. Отвечай четко и по делу."
                    "АНАЛИТИК" -> "Ты — Аналитик. Твоя задача — глубоко анализировать данные, искать закономерности, строить прогнозы и давать объективные выводы. Будь точен."
                    "ПРОГРАММИСТ" -> "Ты — Программист. Ты пишешь код, находишь баги и предлагаешь архитектурные решения. Твой ответ должен содержать код и краткое объяснение."
                    "GigaChat" -> "Ты — GigaChat. Отвечай по делу, структурированно, на русском языке."
                    else -> "Ты — помощник."
                }
                val key = "${pm.projectId}_${pm.agentName}"
                val history = loadHistory(context, key)
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val reply = callGigaChatWithTrustedCert(context, history + Msg(pm.text, true, pm.time, today), agentPrompt)
                if (reply.startsWith("Ошибка сети")) {
                    remaining.add(pm)
                } else {
                    val updated = history + Msg(pm.text, true, pm.time, today) + Msg(reply, false, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), today)
                    saveHistory(context, key, updated)
                    SoundHelper.playIncoming()
                }
            }
            savePendingQueue(context, remaining)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        when {
            showSettings -> SettingsScreen(
                onBack = {
                    showSettings = false
                    statusRefresh++
                },
                currentProject = currentProject
            )
            showCouncil -> CouncilScreen(onBack = { showCouncil = false })
            currentAgent == null -> ContactList(
                projects = projects,
                currentProject = currentProject,
                refreshKey = statusRefresh,
                onProjectSelect = { currentProject = it },
                onProjectAdd = { name ->
                    val newProj = Project(UUID.randomUUID().toString(), name)
                    projects = projects + newProj
                    saveProjects(context, projects)
                    currentProject = newProj
                },
                onProjectDelete = { project ->
                    projects = deleteProject(context, project, projects)
                    if (currentProject.id == project.id) {
                        currentProject = projects.first()
                    }
                },
                onAgentClick = { currentAgent = it },
                onSettingsClick = { showSettings = true },
                onCouncilClick = { showCouncil = true }
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
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 10.dp, vertical = 5.dp),
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
    onCouncilClick: () -> Unit
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
            A("ГЛАФИРА", "Главный координатор", isProviderConfigured(context, ProviderId.GIGACHAT), "🤖",
                "Ты — Глафира, главный координатор проектов. Ты помогаешь пользователю структурировать задачи, планировать и управлять проектами. Отвечай четко и по делу.",
                "Главный управляющий интеллект. Она не просто отвечает на вопросы, а помогает доводить дела до результата. Умеет разбивать сложные задачи на шаги, распределять их между другими агентами и следить за выполнением.",
                ProviderId.GIGACHAT, AgentGroup.IA),
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
            A("Алиса", "Yandex · не настроен", isProviderConfigured(context, ProviderId.YANDEX), "🟡",
                "Ты — Алиса от Яндекс. Отвечай на русском, дружелюбно и по делу.",
                "YandexGPT через Yandex Cloud. Русскоязычные задачи. Для работы нужен API-ключ Yandex Cloud и folder ID.",
                ProviderId.YANDEX, AgentGroup.AI)
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
            Box(Modifier.size(24.dp).border(1.dp, Gr, RoundedCornerShape(2.dp)).clickable { onCouncilClick() }, contentAlignment = Alignment.Center) {
                Text("🏛", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(24.dp).border(1.dp, Gr, RoundedCornerShape(2.dp)).clickable { showAddDialog = true }, contentAlignment = Alignment.Center) {
                Text("+", color = Gr, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(24.dp).border(1.dp, Gr, RoundedCornerShape(2.dp)).clickable { onSettingsClick() }, contentAlignment = Alignment.Center) {
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
    var messages by remember { mutableStateOf(loadHistory(context, historyKey)) }
    var isLoading by remember { mutableStateOf(false) }
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

    LaunchedEffect(Unit) {
        TtsHelper.init(context)
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

    fun startVoiceInput() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            ToastHelper.short(context, "Нужно разрешение на микрофон")
            return
        }
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

    val displayedMessages = if (searchQuery.isBlank()) messages else messages.filter { it.text.contains(searchQuery, ignoreCase = true) }

    LaunchedEffect(messages.size) {
        if (!isSearching && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading) return
        val now = Date()
        val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val updatedMessages = messages + Msg(text, true, timeNow, today)
        messages = updatedMessages
        saveHistory(context, historyKey, updatedMessages)
        clearDraft(context, project.id, agent.n)
        isLoading = true
        SoundHelper.playSent()
        scope.launch {
            val projectContext = buildProjectContext(context, project.id, agent.n)
            val fullPrompt = if (projectContext.isBlank()) agent.prompt else "${agent.prompt}\n\n$projectContext"
            val reply = callGigaChatWithTrustedCert(context, updatedMessages, fullPrompt)
            val replyDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            if (reply.startsWith("Ошибка сети")) {
                addToPendingQueue(context, PendingMessage(project.id, agent.n, text, timeNow))
                val queuedMsg = Msg("⏳ Сообщение в очереди. Отправится при появлении сети.", false, replyTime, replyDate)
                val finalMessages = updatedMessages + queuedMsg
                messages = finalMessages
                saveHistory(context, historyKey, finalMessages)
            } else {
                val finalMessages = updatedMessages + Msg(reply, false, replyTime, replyDate)
                messages = finalMessages
                saveHistory(context, historyKey, finalMessages)
                SoundHelper.playIncoming()
            }
            isLoading = false
        }
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
                                    if (msg.attachmentPath.isNotBlank()) {
                                        Row(Modifier.fillMaxWidth().clickable {
                                            IncomingFileHelper.openFile(context, msg.attachmentPath, msg.attachmentMime)
                                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("📎", fontSize = 22.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(msg.attachmentName.ifBlank { "Вложенный файл" }, color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Text(IncomingFileHelper.formatSize(msg.attachmentSize), color = Tx, fontSize = 9.sp)
                                            }
                                            Text("ОТКРЫТЬ", color = Gr, fontSize = 9.sp)
                                        }
                                    }
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
                Row(Modifier.fillMaxWidth().background(GrD).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).border(1.dp, if (isListening) Red else Gr, RoundedCornerShape(2.dp)).clickable { startVoiceInput() },
                        contentAlignment = Alignment.Center
                    ) { Text(if (isListening) "🔴" else "🎤", fontSize = 16.sp) }
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
                                val t = inputText
                                inputText = ""
                                sendMessage(t)
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) { Text("➤", color = Gr, fontSize = 16.sp) }
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
                                    val targetHistory = loadHistory(context, targetKey).toMutableList()
                                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                    val sysMsg = Msg("[Передано от ${agent.n}]:\n$transferText", false, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), today)
                                    targetHistory.add(sysMsg)
                                    saveHistory(context, targetKey, targetHistory)
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

fun saveHistory(context: Context, key: String, history: List<Msg>) {
    val file = File(context.filesDir, "chat_$key.json")
    val jsonArray = JSONArray()
    history.forEach {
        jsonArray.put(JSONObject().apply {
            put("text", it.text)
            put("isUser", it.isUser)
            put("time", it.time)
            put("date", it.date)
            put("attachmentPath", it.attachmentPath)
            put("attachmentName", it.attachmentName)
            put("attachmentMime", it.attachmentMime)
            put("attachmentSize", it.attachmentSize)
        })
    }
    file.writeText(jsonArray.toString())
}

fun loadHistory(context: Context, key: String): List<Msg> {
    val file = File(context.filesDir, "chat_$key.json")
    if (!file.exists()) return emptyList()
    val list = mutableListOf<Msg>()
    try {
        val jsonArray = JSONArray(file.readText())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                Msg(
                    obj.getString("text"),
                    obj.getBoolean("isUser"),
                    obj.optString("time", ""),
                    obj.optString("date", ""),
                    obj.optString("attachmentPath", ""),
                    obj.optString("attachmentName", ""),
                    obj.optString("attachmentMime", ""),
                    obj.optLong("attachmentSize", 0L)
                )
            )
        }
    } catch (e: Exception) { }
    return list
}

fun buildProjectContext(context: Context, projectId: String, currentAgentName: String, maxMessagesPerChat: Int = 5): String {
    val files = context.filesDir.listFiles { file ->
        file.name.startsWith("chat_${projectId}_") && file.name.endsWith(".json")
    } ?: return ""
    if (files.isEmpty()) return ""
    val sb = StringBuilder()
    files.forEach { file ->
        val key = file.name.removePrefix("chat_").removeSuffix(".json")
        val parts = key.split("_")
        val agentName = if (parts.size > 1) parts[1] else "Агент"
        if (agentName == currentAgentName) return@forEach
        val history = loadHistory(context, key)
        if (history.isEmpty()) return@forEach
        val tail = history.takeLast(maxMessagesPerChat)
        sb.appendLine("--- Чат с $agentName ---")
        tail.forEach { msg ->
            val author = if (msg.isUser) "Пользователь" else agentName
            sb.appendLine("$author: ${msg.text.take(300)}")
        }
        sb.appendLine()
    }
    val result = sb.toString().trim()
    return if (result.isBlank()) "" else "Контекст других чатов в этом проекте:\n$result"
}

suspend fun callGigaChatWithTrustedCert(context: Context, history: List<Msg>, systemPrompt: String): String = withContext(Dispatchers.IO) {
    val authKey = loadApiKey(context)
    if (authKey.isBlank()) return@withContext "Ошибка: Ключ API не найден. Зайдите в настройки и вставьте ключ."
    try {
        val certFactory = CertificateFactory.getInstance("X.509")
        val certInputStream = context.assets.open("russian_trusted_root_ca.crt")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("russian_ca", certFactory.generateCertificate(certInputStream))
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, tmf.trustManagers, null)
        val socketFactory = sslContext.socketFactory

        val tokenUrl = URL("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
        val tokenConn = tokenUrl.openConnection() as HttpsURLConnection
        tokenConn.sslSocketFactory = socketFactory
        tokenConn.requestMethod = "POST"
        tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        tokenConn.setRequestProperty("Accept", "application/json")
        tokenConn.setRequestProperty("RqUID", UUID.randomUUID().toString())
        tokenConn.setRequestProperty("Authorization", "Basic $authKey")
        tokenConn.doOutput = true
        tokenConn.outputStream.use { it.write("scope=GIGACHAT_API_PERS".toByteArray()) }

        if (tokenConn.responseCode != 200) return@withContext "Ошибка токена: ${tokenConn.responseCode}"
        val accessToken = JSONObject(tokenConn.inputStream.bufferedReader().readText()).getString("access_token")

        val chatUrl = URL("https://api.giga.chat/v1/chat/completions")
        val chatConn = chatUrl.openConnection() as HttpsURLConnection
        chatConn.sslSocketFactory = socketFactory
        chatConn.requestMethod = "POST"
        chatConn.setRequestProperty("Content-Type", "application/json")
        chatConn.setRequestProperty("Accept", "application/json")
        chatConn.setRequestProperty("Authorization", "Bearer $accessToken")
        chatConn.doOutput = true

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        history.forEach { msg ->
            messagesArray.put(JSONObject().apply { put("role", if (msg.isUser) "user" else "assistant"); put("content", msg.text) })
        }

        val chatBody = JSONObject().apply { put("model", "GigaChat-2"); put("messages", messagesArray) }.toString()
        chatConn.outputStream.use { it.write(chatBody.toByteArray()) }

        if (chatConn.responseCode == 200) {
            val json = JSONObject(chatConn.inputStream.bufferedReader().readText())
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } else {
            "Ошибка API: ${chatConn.responseCode}"
        }
    } catch (e: Exception) {
        "Ошибка сети: ${e.message}"
    }
}
