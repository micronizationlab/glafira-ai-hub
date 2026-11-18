package com.micronizationlab.glafira

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
fun PasswordVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<VaultSession?>(null) }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var masterPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val configured = remember { VaultSession.exists(context) }

    DisposableEffect(session) {
        val activeSession = session
        onDispose { activeSession?.lock() }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.clickable {
                session?.lock()
                session = null
                onBack()
            }.padding(end = 14.dp))
            Text("ЗАКРЫТОЕ ХРАНИЛИЩЕ", color = Color.White, fontSize = 15.sp)
        }

        Spacer(Modifier.height(20.dp))
        if (session == null) {
            Text(
                if (configured) "Введите мастер-пароль" else "Создайте мастер-пароль",
                color = Gr, fontSize = 17.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Пароль не сохраняется. Без него восстановить содержимое хранилища невозможно. Используйте уникальную фразу длиной не менее 12 символов.",
                color = Tx, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = masterPassword,
                onValueChange = { masterPassword = it; error = null },
                label = { Text("Мастер-пароль") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (!configured) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; error = null },
                    label = { Text("Повторите мастер-пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        val chars = masterPassword.toCharArray()
                        val opened = if (configured) {
                            VaultSession.unlock(context, chars)
                        } else {
                            require(masterPassword == confirmPassword) { "Пароли не совпадают" }
                            VaultSession.create(context, chars)
                        }
                        session = opened
                        entries = opened.loadEntries()
                        masterPassword = ""
                        confirmPassword = ""
                        error = null
                    } catch (e: Exception) {
                        masterPassword = ""
                        error = e.message ?: "Не удалось открыть хранилище"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (configured) "ОТКРЫТЬ ХРАНИЛИЩЕ" else "СОЗДАТЬ ХРАНИЛИЩЕ")
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = Red, fontSize = 12.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Хранилище разблокировано", color = Gr, fontSize = 16.sp)
                    Text("Локально на этом устройстве · AES-256-GCM", color = Tx, fontSize = 11.sp)
                }
                Text("ЗАБЛОКИРОВАТЬ", color = Red, fontSize = 10.sp, modifier = Modifier.clickable {
                    session?.lock()
                    session = null
                    entries = emptyList()
                }.padding(6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showAdd = !showAdd }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAdd) "ОТМЕНИТЬ ДОБАВЛЕНИЕ" else "+ ДОБАВИТЬ ЗАПИСЬ")
            }
            if (showAdd) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(title, { title = it }, label = { Text("Название (обязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(website, { website = it }, label = { Text("Сайт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val newEntry = VaultEntry(UUID.randomUUID().toString(), title.trim(), username, password, website, notes, System.currentTimeMillis())
                        try {
                            val updated = entries + newEntry
                            session?.saveEntries(updated)
                            entries = updated
                            title = ""; username = ""; password = ""; website = ""; notes = ""
                            showAdd = false
                            error = null
                        } catch (e: Exception) {
                            error = e.message ?: "Не удалось сохранить запись"
                        }
                    },
                    enabled = title.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("СОХРАНИТЬ ЗАПИСЬ") }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = Red, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            if (entries.isEmpty()) {
                Text("Записей пока нет.", color = Tx, fontSize = 13.sp)
            }
            entries.forEach { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = GrD),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.title, color = Color(0xFF202020), fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("УДАЛИТЬ", color = Red, fontSize = 10.sp, modifier = Modifier.clickable {
                                try {
                                    val updated = entries.filterNot { it.id == entry.id }
                                    session?.saveEntries(updated)
                                    entries = updated
                                } catch (e: Exception) {
                                    error = e.message ?: "Не удалось удалить запись"
                                }
                            }.padding(4.dp))
                        }
                        if (entry.username.isNotBlank()) Text("Логин: ${entry.username}", color = Tx, fontSize = 12.sp)
                        Text("Пароль: ••••••••", color = Tx, fontSize = 12.sp)
                        if (entry.website.isNotBlank()) Text(entry.website, color = Gr, fontSize = 11.sp)
                        if (entry.notes.isNotBlank()) Text(entry.notes, color = Tx, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Важно: не используйте мастер-пароль от почты или банков. Экспорт и облачная синхронизация пока не предусмотрены.",
            color = Tx, fontSize = 11.sp
        )
    }
}
