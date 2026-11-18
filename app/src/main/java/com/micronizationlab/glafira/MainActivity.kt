package com.micronizationlab.glafira

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

val Bg = Color(0xFF000000)
val Gr = Color(0xFF7EC850)
val GrD = Color(0xFF151515)
val Tx = Color(0xFF888888)

data class A(val n: String, val d: String, val on: Boolean, val emoji: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    var currentAgent by remember { mutableStateOf<A?>(null) }
    Surface(Modifier.fillMaxSize(), color = Bg) {
        if (currentAgent == null) ContactList { currentAgent = it }
        else ChatScreen(agent = currentAgent!!, onBack = { currentAgent = null })
    }
}

@Composable
fun ContactList(onAgentClick: (A) -> Unit) {
    val list = listOf(
        A("ГЛАФИРА", "Главный координатор", true, "🤖"),
        A("АНАЛИТИК", "Исследование и анализ", true, "📊"),
        A("ПРОГРАММИСТ", "Код и технические задачи", false, "💻")
    )
    Column {
        Row(Modifier.fillMaxWidth().background(GrD).padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("GLAFIRA AI HUB", color = Gr, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("⚙️", fontSize = 20.sp, modifier = Modifier.clickable {})
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
            Text("СЕТЬ: ONLINE | ПРОЕКТОВ: 1", color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun ChatScreen(agent: A, onBack: () -> Unit) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    // Загружаем историю при старте
    var messages by remember { mutableStateOf(loadHistory(context, agent.n)) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val authKey = "MDFhMWVjMGQtMmY5ZS03MzYxLTkxYmItZGQ1NjkyMzE0OTNkOmMyZTJlMzU4LWY0MTktNGRjMS05MWE0LTUyNjNlMjJhNzYyNA=="

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(GrD).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Gr, fontSize = 24.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
            Text(agent.emoji, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(agent.n, color = Gr, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(if (agent.on) "Online" else "Offline", color = Tx, fontSize = 10.sp)
            }
        }

        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { (text, isUser) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (isUser) GrD else Gr.copy(alpha = 0.15f)).border(1.dp, if (isUser) Color.DarkGray else Gr.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(12.dp)) {
                        Text(text, color = if (isUser) Color.White else Gr, fontSize = 14.sp)
                    }
                }
            }
            if (isLoading) {
                item {
                    Text("Глафира печатает...", color = Tx, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Row(Modifier.fillMaxWidth().background(GrD).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Введите сообщение...", color = Tx, fontSize = 14.sp) },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Bg, unfocusedContainerColor = Bg, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedIndicatorColor = Gr, unfocusedIndicatorColor = Color.DarkGray)
            )
            Button(
                onClick = {
                    if (inputText.isNotBlank() && !isLoading) {
                        val userMsg = inputText
                        val updatedMessages = messages + (userMsg to true)
                        messages = updatedMessages
                        saveHistory(context, agent.n, updatedMessages) // Сохраняем на телефон
                        inputText = ""
                        isLoading = true
                        
                        scope.launch {
                            // Отправляем всю историю, чтобы ИИ помнил контекст
                            val reply = callGigaChatWithTrustedCert(context, authKey, updatedMessages)
                            val finalMessages = updatedMessages + (reply to false)
                            messages = finalMessages
                            saveHistory(context, agent.n, finalMessages) // Сохраняем ответ
                            isLoading = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Bg)
            ) {
                Text("➤", fontSize = 18.sp)
            }
        }
    }
}

// --- ФУНКЦИИ ДЛЯ СОХРАНЕНИЯ ИСТОРИИ В ФАЙЛ ---
fun saveHistory(context: Context, agentName: String, history: List<Pair<String, Boolean>>) {
    val file = File(context.filesDir, "chat_$agentName.json")
    val jsonArray = JSONArray()
    history.forEach { 
        jsonArray.put(JSONObject().apply { 
            put("text", it.first)
            put("isUser", it.second) 
        }) 
    }
    file.writeText(jsonArray.toString())
}

fun loadHistory(context: Context, agentName: String): List<Pair<String, Boolean>> {
    val file = File(context.filesDir, "chat_$agentName.json")
    if (!file.exists()) return emptyList()
    val list = mutableListOf<Pair<String, Boolean>>()
    try {
        val jsonArray = JSONArray(file.readText())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(obj.getString("text") to obj.getBoolean("isUser"))
        }
    } catch (e: Exception) { }
    return list
}

// --- ФУНКЦИЯ ЗАПРОСА К GIGACHAT С КОНТЕКСТОМ ---
suspend fun callGigaChatWithTrustedCert(context: Context, authKey: String, history: List<Pair<String, Boolean>>): String = withContext(Dispatchers.IO) {
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

        val tokenBody = "scope=GIGACHAT_API_PERS"
        tokenConn.outputStream.use { it.write(tokenBody.toByteArray()) }

        if (tokenConn.responseCode != 200) {
            return@withContext "Ошибка токена: ${tokenConn.responseCode}"
        }

        val tokenResponse = tokenConn.inputStream.bufferedReader().readText()
        val accessToken = JSONObject(tokenResponse).getString("access_token")

        val chatUrl = URL("https://api.giga.chat/v1/chat/completions")
        val chatConn = chatUrl.openConnection() as HttpsURLConnection
        chatConn.sslSocketFactory = socketFactory
        chatConn.requestMethod = "POST"
        chatConn.setRequestProperty("Content-Type", "application/json")
        chatConn.setRequestProperty("Accept", "application/json")
        chatConn.setRequestProperty("Authorization", "Bearer $accessToken")
        chatConn.doOutput = true

        // Формируем массив сообщений для GigaChat
        val messagesArray = JSONArray()
        history.forEach { (text, isUser) ->
            messagesArray.put(JSONObject().apply { 
                put("role", if (isUser) "user" else "assistant") 
                put("content", text) 
            })
        }

        val chatBody = JSONObject().apply {
            put("model", "GigaChat-2")
            put("messages", messagesArray)
        }.toString()

        chatConn.outputStream.use { it.write(chatBody.toByteArray()) }

        if (chatConn.responseCode == 200) {
            val response = chatConn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } else {
            "Ошибка API: ${chatConn.responseCode}"
        }
    } catch (e: Exception) {
        "Ошибка сети: ${e.message}"
    }
}
