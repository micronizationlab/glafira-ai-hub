package com.micronizationlab.glafira

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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

val Bg = Color(0xFF000000)
val Gr = Color(0xFF7EC850)
val GrD = Color(0xFF151515)
val Tx = Color(0xFF888888)

data class A(
    val n: String, val d: String, val on: Boolean, val emoji: String, 
    val prompt: String, val fullDesc: String
)
data class Msg(val text: String, val isUser: Boolean, val time: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    var currentAgent by remember { mutableStateOf<A?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var projects by remember { mutableStateOf(loadProjects(context)) }
    var currentProject by remember { mutableStateOf(projects.first()) }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        when {
            showSettings -> SettingsScreen(onBack = { showSettings = false })
            currentAgent == null -> ContactList(
                projects = projects,
                currentProject = currentProject,
                onProjectSelect = { currentProject = it },
                onProjectAdd = { name ->
                    val newProj = Project(UUID.randomUUID().toString(), name)
                    projects = projects + newProj
                    saveProjects(context, projects)
                    currentProject = newProj
                },
                onAgentClick = { currentAgent = it },
                onSettingsClick = { showSettings = true }
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
fun ContactList(
    projects: List<Project>,
    currentProject: Project,
    onProjectSelect: (Project) -> Unit,
    onProjectAdd: (String) -> Unit,
    onAgentClick: (A) -> Unit,
    onSettingsClick: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    val list = listOf(
        A("ГЛАФИРА", "Главный координатор", true, "🤖", 
            "Ты — Глафира, главный координатор проектов. Ты помогаешь пользователю структурировать задачи, планировать и управлять проектами. Отвечай четко и по делу.",
            "Главный управляющий интеллект. Она не просто отвечает на вопросы, а помогает доводить дела до результата. Умеет разбивать сложные задачи на шаги, распределять их между другими агентами и следить за выполнением."),
        A("АНАЛИТИК", "Исследование и анализ", true, "📊", 
            "Ты — Аналитик. Твоя задача — глубоко анализировать данные, искать закономерности, строить прогнозы и давать объективные выводы. Будь точен.",
            "Специалист по данным. Занимается исследованием рынков, поиском закономерностей, анализом конкурентов и финансовых показателей. Отвечает за факты и цифры."),
        A("ПРОГРАММИСТ", "Код и технические задачи", false, "💻", 
            "Ты — Программист. Ты пишешь код, находишь баги и предлагаешь архитектурные решения. Твой ответ должен содержать код и краткое объяснение.",
            "Технический специалист. Пишет код на разных языках, проверяет его на ошибки, предлагает архитектуру и помогает с техническими решениями. Отвечает за IT-часть проекта.")
    )

    Column {
        Row(Modifier.fillMaxWidth().background(GrD).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Проект: ", color = Tx, fontSize = 12.sp)
            Box {
                Row(modifier = Modifier.clickable { showProjectMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(currentProject.name, color = Gr, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(" ▼", color = Gr, fontSize = 12.sp)
                }
                DropdownMenu(expanded = showProjectMenu, onDismissRequest = { showProjectMenu = false }) {
                    projects.forEach { proj ->
                        DropdownMenuItem(
                            text = { Text(proj.name, color = if (proj.id == currentProject.id) Gr else Bg) },
                            onClick = { onProjectSelect(proj); showProjectMenu = false }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("+", color = Gr, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { showAddDialog = true })
            Spacer(Modifier.width(16.dp))
            Text("⚙️", fontSize = 20.sp, modifier = Modifier.clickable { onSettingsClick() })
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Новый проект", color = Bg) },
                text = { TextField(value = newProjectName, onValueChange = { newProjectName = it }, placeholder = { Text("Название...") }) },
                confirmButton = {
                    TextButton(onClick = {
                        if (newProjectName.isNotBlank()) { onProjectAdd(newProjectName); newProjectName = ""; showAddDialog = false }
                    }) { Text("Создать", color = Gr) }
                }
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(list) { a ->
                Row(Modifier.fillMaxWidth().clickable { onAgentClick(a) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (a.on) Gr.copy(alpha = 0.2f) else Color.DarkGray.copy(alpha = 0.3f)).border(1.dp, if (a.on) Gr else Color.DarkGray, CircleShape), contentAlignment = Alignment.Center) {
                        Text(a.emoji, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.n, color = if (a.on) Gr else Tx, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(a.d, color = Tx, fontSize = 12.sp)
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (a.on) Color.Green else Color.Gray))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(GrD))
            }
        }
        Row(Modifier.fillMaxWidth().background(GrD).padding(8.dp), Arrangement.Center) {
            Text("СЕТЬ: ONLINE | ПРОЕКТ: ${currentProject.name}", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(agent: A, project: Project, onBack: () -> Unit) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val historyKey = "${project.id}_${agent.n}"
    var messages by remember { mutableStateOf(loadHistory(context, historyKey)) }
    var isLoading by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    
    // СОСТОЯНИЯ ДЛЯ ПОИСКА
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    // ДЛЯ АВТОСКРОЛЛА
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Фильтрация сообщений при поиске
    val displayedMessages = if (searchQuery.isBlank()) messages else messages.filter { it.text.contains(searchQuery, ignoreCase = true) }

    // Эффект автоскролла при изменении количества сообщений
    LaunchedEffect(messages.size) {
        if (!isSearching && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showProfile) {
        ProfileScreen(agent = agent, onBack = { showProfile = false })
    } else {
        Column(Modifier.fillMaxSize()) {
            // Шапка чата
            Row(Modifier.fillMaxWidth().background(GrD).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isSearching) {
                    // Режим поиска
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Поиск...", color = Tx, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = GrD, unfocusedContainerColor = GrD, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedIndicatorColor = Gr)
                    )
                    Text("✕", color = Gr, fontSize = 20.sp, modifier = Modifier.clickable { isSearching = false; searchQuery = "" }.padding(start = 8.dp))
                } else {
                    // Обычная шапка
                    Text("←", color = Gr, fontSize = 24.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
                    Row(Modifier.weight(1f).clickable { showProfile = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text(agent.emoji, fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(agent.n, color = Gr, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(if (agent.on) "Online" else "Offline", color = Tx, fontSize = 10.sp)
                        }
                    }
                    Text("🔍", fontSize = 18.sp, modifier = Modifier.clickable { isSearching = true }.padding(end = 16.dp))
                    Text("📤", fontSize = 18.sp, modifier = Modifier.clickable { exportChatToFile(context, project.name, agent.n, messages) }.padding(end = 16.dp))
                    Text("🗑️", fontSize = 18.sp, modifier = Modifier.clickable {
                        messages = emptyList(); saveHistory(context, historyKey, emptyList())
                    })
                }
            }

            // Список сообщений
            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(displayedMessages) { msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start) {
                        Column(horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start) {
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(if (msg.isUser) GrD else Gr.copy(alpha = 0.15f)).border(1.dp, if (msg.isUser) Color.DarkGray else Gr.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).combinedClickable(
                                    onClick = {}, onLongClick = { copyToClipboard(context, msg.text) }
                                ).padding(12.dp)
                            ) {
                                Text(msg.text, color = if (msg.isUser) Color.White else Gr, fontSize = 14.sp)
                            }
                            Text(msg.time, color = Tx, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (isLoading) { item { TypingIndicator() } }
                if (isSearching && displayedMessages.isEmpty()) { item { Text("Ничего не найдено", color = Tx, modifier = Modifier.padding(16.dp)) } }
            }

            // Поле ввода
            if (!isSearching) {
                Row(Modifier.fillMaxWidth().background(GrD).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = inputText, onValueChange = { inputText = it },
                        placeholder = { Text("Введите сообщение...", color = Tx, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Bg, unfocusedContainerColor = Bg, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedIndicatorColor = Gr, unfocusedIndicatorColor = Color.DarkGray)
                    )
                    Button(
                        onClick = {
                            if (inputText.isNotBlank() && !isLoading) {
                                val userMsg = inputText
                                val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                val updatedMessages = messages + Msg(userMsg, true, timeNow)
                                messages = updatedMessages
                                saveHistory(context, historyKey, updatedMessages)
                                inputText = ""
                                isLoading = true
                                scope.launch {
                                    val reply = callGigaChatWithTrustedCert(context, updatedMessages, agent.prompt)
                                    val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    val finalMessages = updatedMessages + Msg(reply, false, replyTime)
                                    messages = finalMessages
                                    saveHistory(context, historyKey, finalMessages)
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Bg)
                    ) { Text("➤", fontSize = 18.sp) }
                }
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha1 by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse))
    val alpha2 by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = 200, easing = LinearEasing), RepeatMode.Reverse))
    val alpha3 by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = 400, easing = LinearEasing), RepeatMode.Reverse))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        Text("Глафира печатает", color = Tx, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(4.dp))
        Text(".", color = Gr.copy(alpha = alpha1), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(".", color = Gr.copy(alpha = alpha2), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(".", color = Gr.copy(alpha = alpha3), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ProfileScreen(agent: A, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(GrD).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Gr, fontSize = 24.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
            Text("Профиль агента", color = Gr, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(100.dp).clip(CircleShape).background(Gr.copy(alpha = 0.2f)).border(2.dp, Gr, CircleShape), contentAlignment = Alignment.Center) {
                Text(agent.emoji, fontSize = 50.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(agent.n, color = Gr, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(agent.d, color = Tx, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (agent.on) Color.Green else Color.Gray))
                Spacer(Modifier.width(4.dp))
                Text(if (agent.on) "В сети" else "Не в сети", color = if (agent.on) Color.Green else Tx, fontSize = 12.sp)
            }
            Spacer(Modifier.height(32.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GrD), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("ОПИСАНИЕ", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(agent.fullDesc, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GrD), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("СПЕЦИФИКА РАБОТЫ", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(agent.prompt, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

// --- ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ (остались без изменений) ---
fun saveHistory(context: Context, key: String, history: List<Msg>) {
    val file = File(context.filesDir, "chat_$key.json")
    val jsonArray = JSONArray()
    history.forEach { jsonArray.put(JSONObject().apply { put("text", it.text); put("isUser", it.isUser); put("time", it.time) }) }
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
            list.add(Msg(obj.getString("text"), obj.getBoolean("isUser"), obj.optString("time", "")))
        }
    } catch (e: Exception) { }
    return list
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
