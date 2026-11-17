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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

// Добавили поле emoji для аватарки
data class A(val n: String, val d: String, val on: Boolean, val emoji: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UI() }
    }
}

@Composable
fun UI() {
    val list = listOf(
        A("ГЛАФИРА", "Главный координатор", true, "🤖"),
        A("АНАЛИТИК", "Исследование и анализ", true, "📊"),
        A("ПРОГРАММИСТ", "Код и технические задачи", false, "💻")
    )
    
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column {
            Row(Modifier.fillMaxWidth().background(GrD).padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("GLAFIRA AI HUB", color = Gr, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("⚙️", fontSize = 20.sp, modifier = Modifier.clickable {})
            }
            
            LazyColumn(Modifier.weight(1f)) {
                items(list) { a ->
                    Row(Modifier.fillMaxWidth().clickable {}.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        
                        // Аватарка с эмодзи в кружочке
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (a.on) Gr.copy(alpha = 0.2f) else Color.DarkGray.copy(alpha = 0.3f))
                                .border(1.dp, if (a.on) Gr else Color.DarkGray, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(a.emoji, fontSize = 24.sp)
                        }
                        
                        Spacer(Modifier.width(16.dp))
                        
                        Column(Modifier.weight(1f)) {
                            Text(a.n, color = if (a.on) Gr else Tx, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(a.d, color = Tx, fontSize = 12.sp)
                        }
                        
                        // Точка онлайна справа
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
}
