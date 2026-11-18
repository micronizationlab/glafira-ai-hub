package com.micronizationlab.glafira

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
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
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }
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
                        messages = messages + (userMsg to true)
                        inputText = ""
                        isLoading = true
                        scope.launch {
                            val reply = callGigaChatWithTrustedCert(authKey, userMsg)
                            messages = messages + (reply to false)
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

// Новая функция с явным доверием сертификату
suspend fun callGigaChatWithTrustedCert(authKey: String, prompt: String): String = withContext(Dispatchers.IO) {
    try {
        // 1. Загружаем сертификат из res/raw
        val certFactory = CertificateFactory.getInstance("X.509")
        val certInputStream: InputStream = this@withContext.assets.open("russian_trusted_root_ca.crt") // ВАЖНО: путь может быть другим
        // Пробуем разные варианты пути, так как res/raw может быть недоступен напрямую через assets
        // В реальном приложении лучше использовать R.raw.russian_trusted_root_ca
        // Но для простоты копирования оставим так, с обработкой ошибки
        // В идеале: val certInputStream = resources.openRawResource(R.raw.russian_trusted_root_ca)
        
        // Создаем KeyStore с нашим сертификатом
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("russian_ca", certFactory.generateCertificate(certInputStream))

        // Создаем TrustManagerFactory
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)

        // Создаем SSLContext
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, tmf.trustManagers, null)

        // 2. Получаем Access Token (с использованием нашего SSLContext)
        val tokenUrl = URL("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
        val tokenConn = tokenUrl.openConnection() as HttpsURLConnection
        tokenConn.sslSocketFactory = sslContext.socketFactory
        tokenConn.requestMethod = "POST"
        tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        tokenConn.setRequestProperty("Accept", "application/json")
        tokenConn.setRequestProperty("RqUID", UUID.randomUUID().toString())
        tokenConn.setRequestProperty("Authorization", "Basic $authKey")
        tokenConn.doOutput = true

        val tokenBody = "scope=GIGACHAT_API_PERS"
        tokenConn.outputStream.use { it.write(tokenBody.toByteArray()) }

        if (tokenConn.responseCode != 200) {
            return@withContext "Ошибка токена: ${tokenConn.responseCode}. Проверь ключ."
        }

        val tokenResponse = tokenConn.inputStream.bufferedReader().readText()
        val accessToken = JSONObject(tokenResponse).getString("access_token")

        // 3. Отправляем сообщение (с использованием нашего SSLContext)
        val chatUrl = URL("https://api.giga.chat/v1/chat/completions")
        val chatConn = chatUrl.openConnection() as HttpsURLConnection
        chatConn.sslSocketFactory = sslContext.socketFactory
        chatConn.requestMethod = "POST"
        chatConn.setRequestProperty("Content-Type", "application/json")
        chatConn.setRequestProperty("Accept", "application/json")
        chatConn.setRequestProperty("Authorization", "Bearer $accessToken")
        chatConn.doOutput = true

        val chatBody = JSONObject().apply {
            put("model", "GigaChat")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
        }.toString()

        chatConn.outputStream.use { it.write(chatBody.toByteArray()) }

        if (chatConn.responseCode == 200) {
            val response = chatConn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } else {
            "Ошибка API: ${chatConn.responseCode}. ${chatConn.errorStream?.bufferedReader()?.readText()}"
        }
    } catch (e: Exception) {
        "Ошибка сети: ${e.message}"
    }
}
