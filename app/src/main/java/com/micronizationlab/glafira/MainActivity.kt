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
    // Состояние: какой экран открыт (null = список, иначе = имя агента)
    var currentAgent by remember { mutableStateOf<A?>(null) }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        if (currentAgent == null) {
            ContactList(onAgentClick = { currentAgent = it })
        } else {
            ChatScreen(agent = currentAgent!!, onBack = { currentAgent = null })
        }
    }
}

// ---------------- ЭКРАН СПИСКА КОНТАКТОВ ----------------
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

// ---------------- ЭКРАН ЧАТА ----------------
@Composable
fun ChatScreen(agent: A, onBack: () -> Unit) {
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) } // Pair(Текст, ОтКого(юзер/бот))

    Column(Modifier.fillMaxSize()) {
        // Верхняя панель чата
        Row(Modifier.fillMaxWidth().background(GrD).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Gr, fontSize = 24.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
            Text(agent.emoji, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(agent.n, color = Gr, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(if (agent.on) "Online" else "Offline", color = Tx, fontSize = 10.sp)
            }
        }

        // Область сообщений
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { (text, isUser) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isUser) GrD else Gr.copy(alpha = 0.15f))
                            .border(1.dp, if (isUser) Color.DarkGray else Gr.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(text, color = if (isUser) Color.White else Gr, fontSize = 14.sp)
                    }
                }
            }
        }

        // Поле ввода
        Row(Modifier.fillMaxWidth().background(GrD).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Введите сообщение...", color = Tx, fontSize = 14.sp) },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Bg,
                    unfocusedContainerColor = Bg,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Gr,
                    unfocusedIndicatorColor = Color.DarkGray
                )
            )
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        // Добавляем сообщение пользователя
                        messages = messages + (inputText to true)
                        // Простая заглушка ответа от бота (эхо)
                        messages = messages + ("Я пока заглушка. Скоро научусь думать! (Вы сказали: $inputText)" to false)
                        inputText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Bg)
            ) {
                Text("➤", fontSize = 18.sp)
            }
        }
    }
}
