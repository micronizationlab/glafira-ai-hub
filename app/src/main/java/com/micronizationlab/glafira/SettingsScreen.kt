package com.micronizationlab.glafira

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val context = androidx.compose.ui.platform.LocalContext.current
    var keyInput by remember { mutableStateOf(loadApiKey(context)) }
    var isSaved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Gr, fontSize = 24.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
            Text("НАСТРОЙКИ", color = Gr, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        
        Spacer(Modifier.height(24.dp))
        
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
    }
}
