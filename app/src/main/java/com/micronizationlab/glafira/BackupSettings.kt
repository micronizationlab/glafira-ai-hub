package com.micronizationlab.glafira

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Облачная синхронизация и ежедневное расписание будут подключены следующим этапом.") }
    var busy by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            busy = true
            status = "Создаю и проверяю зашифрованный архив…"
            val recoveryPassphrase = passphrase.toCharArray()
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val archive = BackupArchive.create(context, recoveryPassphrase)
                        try {
                            val destination = context.contentResolver.openOutputStream(uri)
                                ?: error("Не удалось открыть файл назначения.")
                            destination.use { output -> archive.inputStream().use { input -> input.copyTo(output) } }
                            val verification = BackupArchive.verify(archive, recoveryPassphrase)
                            "Архив проверен: ${verification.fileCount} файлов, ${formatBackupSize(verification.totalBytes)}."
                        } finally {
                            archive.delete()
                            recoveryPassphrase.fill('\u0000')
                        }
                    }
                }
                status = result.getOrElse { "Ошибка резервного копирования: ${it.message ?: "неизвестная ошибка"}" }
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("РЕЗЕРВНОЕ КОПИРОВАНИЕ", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Card(colors = CardDefaults.cardColors(containerColor = GrD), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Зашифрованная копия данных GLAFIRA", color = Color(0xFF202020), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Сейчас доступны ручное создание архива и восстановление через системный выбор файла. Ключи API, OAuth-токены и секреты Android Keystore в архив не включаются.",
                    color = Tx, fontSize = 12.sp
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пароль резервной копии") },
                    placeholder = { Text("Не менее 12 символов") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text("Запомни пароль отдельно: без него зашифрованный архив восстановить невозможно.", color = Tx, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            exportLauncher.launch("GLAFIRA-backup-$stamp.glb")
                        },
                        enabled = !busy && passphrase.length >= 12,
                        modifier = Modifier.weight(1f)
                    ) { Text("Создать копию") }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                        enabled = !busy && passphrase.length >= 12,
                        modifier = Modifier.weight(1f)
                    ) { Text("Восстановить") }
                }
                Text(if (busy) "Выполняется операция…" else status, color = if (status.startsWith("Ошибка")) Red else Tx, fontSize = 11.sp)
            }
        }
    }

    if (pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Восстановить резервную копию?") },
            text = { Text("Файлы из архива будут записаны поверх одноимённых текущих файлов. Перед продолжением рекомендуется создать свежую копию текущего состояния.") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingRestoreUri
                    pendingRestoreUri = null
                    if (uri != null) {
                        busy = true
                        status = "Проверяю архив перед восстановлением…"
                        val recoveryPassphrase = passphrase.toCharArray()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val temp = File(context.cacheDir, "restore-selected-${System.currentTimeMillis()}.glb")
                                    try {
                                        context.contentResolver.openInputStream(uri)?.use { input ->
                                            temp.outputStream().use { output -> input.copyTo(output) }
                                        } ?: error("Не удалось прочитать выбранный файл.")
                                        val verification = BackupArchive.restore(context, temp, recoveryPassphrase)
                                        "Восстановлено: ${verification.fileCount} файлов из копии от ${verification.createdAt}."
                                    } finally {
                                        temp.delete()
                                        recoveryPassphrase.fill('\u0000')
                                    }
                                }
                            }
                            status = result.getOrElse { "Ошибка восстановления: ${it.message ?: "неизвестная ошибка"}" }
                            busy = false
                        }
                    }
                }) { Text("ВОССТАНОВИТЬ") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("ОТМЕНА") }
            }
        )
    }
}

private fun formatBackupSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024.0 * 1024.0))
}
