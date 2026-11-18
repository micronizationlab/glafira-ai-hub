package com.micronizationlab.glafira

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.io.File

// Функции для безопасного сохранения ключа
fun saveApiKey(context: Context, key: String) {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("gigachat_api_key", key).apply()
}

fun loadApiKey(context: Context): String {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    return prefs.getString("gigachat_api_key", "") ?: ""
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf(loadApiKey(context)) }
    var isSaved by remember { mutableStateOf(false) }
    
    // Для темы (пока просто визуальная заглушка, так как тема у нас одна - темная)
    var isDarkTheme by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        // Шапка
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Gr, fontSize = 24.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
            Text("НАСТРОЙКИ", color = Gr, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Блок с ключом
        Text("Ключ авторизации GigaChat (Authorization Key):", color = Tx, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        
        TextField(
            value = keyInput,
            onValueChange = { 
                keyInput = it 
                isSaved = false
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Вставьте ключ сюда...", color = Tx) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = GrD, unfocusedContainerColor = GrD,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedIndicatorColor = Gr
            )
        )
        
        Spacer(Modifier.height(16.dp))
        
        Button(
            onClick = {
                saveApiKey(context, keyInput.trim())
                isSaved = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Bg)
        ) {
            Text("СОХРАНИТЬ", fontWeight = FontWeight.Bold)
        }
        
        if (isSaved) {
            Spacer(Modifier.height(8.dp))
            Text("Ключ успешно сохранен!", color = Gr, fontSize = 12.sp)
        }

        Spacer(Modifier.height(32.dp))
        
        // Блок с настройками темы (заглушка)
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Тёмная тема", color = Color.White, fontSize = 16.sp)
                    Text("Стиль классической ICQ", color = Tx, fontSize = 12.sp)
                }
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = { isDarkTheme = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Gr, checkedTrackColor = GrD)
                )
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Блок с информацией о версии
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("ВЕРСИЯ ПРИЛОЖЕНИЯ", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("GLAFIRA AI HUB v0.5", color = Color.White, fontSize = 14.sp)
                Text("Sprint 5.0 — Память, Контекст, Проекты, Экспорт", color = Tx, fontSize = 12.sp)
            }
        }
        
        Spacer(Modifier.weight(1f))
        
        // Кнопка очистки всех чатов (опасная зона)
        Text(
            text = "Очистить все данные приложения",
            color = Color.Red.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    // Удаляем все файлы чатов
                    val files = context.filesDir.listFiles()
                    files?.forEach { file ->
                        if (file.name.startsWith("chat_") || file.name == "projects.json") {
                            file.delete()
                        }
                    }
                    // TODO: здесь можно добавить перезапуск приложения или просто показать тост
                }
                .padding(16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
