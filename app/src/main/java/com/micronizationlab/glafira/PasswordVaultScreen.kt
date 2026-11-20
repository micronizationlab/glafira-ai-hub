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
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay

@Composable
fun PasswordVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        var current: Context = view.context
        var activity: Activity? = null
        while (current is ContextWrapper) {
            if (current is Activity) {
                activity = current
                break
            }
            current = current.baseContext
        }
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
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
    var importStatus by remember { mutableStateOf<String?>(null) }
    fun lockVault() {
        session?.lock()
        session = null
        entries = emptyList()
        masterPassword = ""
        confirmPassword = ""
        title = ""; username = ""; password = ""; website = ""; notes = ""
        showAdd = false
        importStatus = null
        error = null
    }
    DisposableEffect(context) {
        var host: Context = context
        while (host is ContextWrapper && host !is Activity) host = host.baseContext
        val lifecycle = (host as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lockVault()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(session) {
        if (session != null) {
            delay(5 * 60_000L)
            lockVault()
        }
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val activeSession = session ?: error("Сначала открой хранилище.")
                val result = context.contentResolver.openInputStream(uri)?.use { input ->
                    GooglePasswordCsvImporter.parse(input)
                } ?: error("Не удалось прочитать выбранный CSV-файл.")
                val knownKeys = entries.map {
                    it.website.trim().lowercase() + "|" + it.username.trim().lowercase()
                }.toMutableSet()
                val newEntries = result.entries.filter { entry ->
                    knownKeys.add(entry.website.trim().lowercase() + "|" + entry.username.trim().lowercase())
                }
                activeSession.saveEntries(entries + newEntries)
                entries = entries + newEntries
                importStatus = "Импортировано: ${newEntries.size}; пропущено пустых строк: ${result.skippedRows}; дубликатов: ${result.entries.size - newEntries.size}."
                error = null
            } catch (e: Exception) {
                importStatus = "Импорт не выполнен: ${e.message ?: "ошибка CSV"}"
            }
        }
    }
    var configured by remember { mutableStateOf(VaultSession.exists(context)) }

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
                        if (!configured) {
                            require(masterPassword == confirmPassword) { "Пароли не совпадают" }
                        }
                        val chars = masterPassword.toCharArray()
                        val opened = if (configured) {
                            VaultSession.unlock(context, chars)
                        } else {
                            VaultSession.create(context, chars)
                        }
                        entries = opened.loadEntries()
                        configured = true
                        session = opened
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
                    lockVault()
                }.padding(6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("ИМПОРТ CSV ИЗ GOOGLE PASSWORD MANAGER")
            }
            importStatus?.let { Text(it, color = if (it.startsWith("Импорт не")) Red else Gr, fontSize = 11.sp) }
            Text("CSV читается непосредственно из выбранного файла и не сохраняется приложением. После успешного импорта удали исходный CSV из загрузок и корзины файлов.", color = Tx, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
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
                        var passwordVisible by remember(entry.id) { mutableStateOf(false) }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (passwordVisible) "Пароль: ${entry.password}" else "Пароль: ••••••••", color = Tx, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(if (passwordVisible) "СКРЫТЬ" else "ПОКАЗАТЬ", color = Gr, fontSize = 9.sp, modifier = Modifier.clickable { passwordVisible = !passwordVisible }.padding(4.dp))
                        }
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
