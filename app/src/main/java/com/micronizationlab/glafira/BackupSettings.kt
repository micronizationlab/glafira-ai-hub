package com.micronizationlab.glafira

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
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
    val prefs = remember { context.getSharedPreferences(GlafiraBackupWorker.BACKUP_PREFS, android.content.Context.MODE_PRIVATE) }
    var passphrase by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(prefs.getString(GlafiraBackupWorker.KEY_LAST_STATUS, "Резервная копия ещё не создавалась.") ?: "")
    }
    var busy by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var folderUri by remember { mutableStateOf(prefs.getString(GlafiraBackupWorker.KEY_TREE_URI, null)) }
    var autoEnabled by remember { mutableStateOf(prefs.getBoolean("backup_auto_enabled", false)) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("backup_wifi_only", true)) }
    var lastSuccess by remember { mutableStateOf(prefs.getLong(GlafiraBackupWorker.KEY_LAST_SUCCESS, 0L)) }
    var folderError by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                folderUri = uri.toString()
                prefs.edit().putString(GlafiraBackupWorker.KEY_TREE_URI, folderUri).apply()
                folderError = null
                status = "Папка облачного хранилища подключена."
            } catch (error: Exception) {
                folderError = "Не удалось сохранить доступ к папке: ${error.message ?: "ошибка разрешения"}"
            }
        }
    }

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
                    "Архив шифруется на устройстве. API-ключи, OAuth-токены и секреты Android Keystore не включаются.",
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
                Text("Сохрани пароль отдельно. Для автоматического задания он хранится на этом телефоне в зашифрованном виде, а для нового устройства потребуется ввести его снова.", color = Tx, fontSize = 11.sp)

                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (folderUri == null) "Выбрать папку Google Drive / Яндекс Диска" else "Изменить папку облачной копии") }

                val folderName = folderUri?.let { value ->
                    runCatching { DocumentFile.fromTreeUri(context, Uri.parse(value))?.name }.getOrNull()
                }
                Text(
                    folderName?.let { "Хранилище: $it" } ?: "Папка не выбрана. В системном окне выбери Google Drive или Яндекс Диск, если он доступен на устройстве.",
                    color = if (folderUri == null) Tx else Gr, fontSize = 11.sp
                )
                folderError?.let { Text(it, color = Red, fontSize = 11.sp) }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ежедневное резервирование", color = Color(0xFF202020), fontSize = 14.sp)
                        Text(if (autoEnabled) "Включено (выполняется при доступных условиях Android)" else "Выключено", color = Tx, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoEnabled,
                        enabled = !busy,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (folderUri == null) {
                                    status = "Сначала выбери папку облачного хранилища."
                                } else if (passphrase.length < 12) {
                                    status = "Введи пароль резервной копии длиной не менее 12 символов."
                                } else if (!EncryptedApiKeyStore.save(context, GlafiraBackupWorker.BACKUP_PASSPHRASE_KEY, passphrase)) {
                                    status = "Не удалось сохранить пароль в Android Keystore."
                                } else {
                                    prefs.edit().putBoolean("backup_auto_enabled", true).putBoolean("backup_wifi_only", wifiOnly).apply()
                                    autoEnabled = true
                                    BackupScheduler.scheduleDaily(context, wifiOnly)
                                    status = "Ежедневное резервирование включено."
                                }
                            } else {
                                BackupScheduler.cancel(context)
                                autoEnabled = false
                                prefs.edit().putBoolean("backup_auto_enabled", false).apply()
                                EncryptedApiKeyStore.save(context, GlafiraBackupWorker.BACKUP_PASSPHRASE_KEY, "")
                                status = "Ежедневное резервирование выключено."
                            }
                        }
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Только Wi-Fi / безлимитная сеть", color = Color(0xFF202020), fontSize = 13.sp)
                        Text("Android может перенести запуск до появления подходящей сети и зарядного состояния.", color = Tx, fontSize = 10.sp)
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { enabled ->
                            wifiOnly = enabled
                            prefs.edit().putBoolean("backup_wifi_only", enabled).apply()
                            if (autoEnabled) BackupScheduler.scheduleDaily(context, enabled)
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (EncryptedApiKeyStore.save(context, GlafiraBackupWorker.BACKUP_PASSPHRASE_KEY, passphrase)) {
                                BackupScheduler.runNow(context, wifiOnly)
                                status = "Запрос на внеплановую резервную копию отправлен."
                            } else {
                                status = "Не удалось сохранить пароль резервной копии."
                            }
                        },
                        enabled = !busy && folderUri != null && passphrase.length >= 12,
                        modifier = Modifier.weight(1f)
                    ) { Text("Сохранить сейчас") }
                    OutlinedButton(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            exportLauncher.launch("GLAFIRA-backup-$stamp.glb")
                        },
                        enabled = !busy && passphrase.length >= 12,
                        modifier = Modifier.weight(1f)
                    ) { Text("Экспорт файла") }
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                    enabled = !busy && passphrase.length >= 12,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Восстановить из файла") }

                if (lastSuccess > 0L) {
                    Text("Последняя успешная копия: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(lastSuccess))}", color = Gr, fontSize = 11.sp)
                }
                val lastError = prefs.getString(GlafiraBackupWorker.KEY_LAST_ERROR, null)
                if (!lastError.isNullOrBlank()) Text("Последняя ошибка: $lastError", color = Red, fontSize = 11.sp)
                Text(if (busy) "Выполняется операция…" else status, color = if (status.startsWith("Ошибка")) Red else Tx, fontSize = 11.sp)
            }
        }
    }

    if (pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Восстановить резервную копию?") },
            text = { Text("Архив будет проверен до восстановления. Одноимённые файлы приложения будут заменены. Создай отдельную свежую копию перед восстановлением, если в телефоне есть более новые данные.") },
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
