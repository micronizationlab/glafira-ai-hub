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
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Observer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var manualBackupGuidance by remember { mutableStateOf<String?>(null) }
    var persistedStatus by remember { mutableStateOf(prefs.getString(GlafiraBackupWorker.KEY_LAST_STATUS, null)) }
    var lastError by remember { mutableStateOf(prefs.getString(GlafiraBackupWorker.KEY_LAST_ERROR, null)) }
    val workManager = remember { WorkManager.getInstance(context) }

    DisposableEffect(workManager) {
        val manualWork = workManager.getWorkInfosForUniqueWorkLiveData(BackupScheduler.MANUAL_WORK_NAME)
        val manualObserver = Observer<List<WorkInfo>> { infos ->
            val work = infos?.lastOrNull() ?: return@Observer
            status = BackupWorkStatus.message(work.state, work.runAttemptCount)
            persistedStatus = prefs.getString(GlafiraBackupWorker.KEY_LAST_STATUS, null)
            lastError = prefs.getString(GlafiraBackupWorker.KEY_LAST_ERROR, null)
            lastSuccess = prefs.getLong(GlafiraBackupWorker.KEY_LAST_SUCCESS, 0L)
        }
        val dailyWork = workManager.getWorkInfosForUniqueWorkLiveData(BackupScheduler.DAILY_WORK_NAME)
        val dailyObserver = Observer<List<WorkInfo>> { infos ->
            val work = infos?.lastOrNull() ?: return@Observer
            lastError = prefs.getString(GlafiraBackupWorker.KEY_LAST_ERROR, null)
            persistedStatus = prefs.getString(GlafiraBackupWorker.KEY_LAST_STATUS, null)
            lastSuccess = prefs.getLong(GlafiraBackupWorker.KEY_LAST_SUCCESS, 0L)
            if (work.state == WorkInfo.State.RUNNING) {
                status = BackupWorkStatus.message(work.state, work.runAttemptCount)
            }
        }
        manualWork.observeForever(manualObserver)
        dailyWork.observeForever(dailyObserver)
        onDispose {
            manualWork.removeObserver(manualObserver)
            dailyWork.removeObserver(dailyObserver)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val persistedWriteGrant = context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isWritePermission
                }
                val writableFolder = DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
                if (!persistedWriteGrant || !writableFolder) {
                    error("Провайдер не выдал постоянный доступ на запись. Выбери папку повторно и подтверди доступ.")
                }
                folderUri = uri.toString()
                prefs.edit().putString(GlafiraBackupWorker.KEY_TREE_URI, folderUri).apply()
                folderError = null
                manualBackupGuidance = null
                status = "Папка облачного хранилища подключена и доступна для записи."
            } catch (error: Exception) {
                folderError = "Не удалось подключить папку: ${error.message ?: "ошибка разрешения"}"
                status = "Выбранная папка не подключена. Предыдущая настройка сохранена."
            }
        } else {
            status = "Выбор папки отменён. Текущая настройка не изменена."
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
                        val archive = withCoreBackupCoordinator(context) { it.create(recoveryPassphrase) }
                        try {
                            val destination = context.contentResolver.openOutputStream(uri)
                                ?: error("Не удалось открыть файл назначения.")
                            destination.use { output -> archive.inputStream().use { input -> input.copyTo(output) } }
                            val verification = withCoreBackupCoordinator(context) { it.verify(archive, recoveryPassphrase) }
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
                Text("Сохрани пароль отдельно. На этом телефоне он хранится в зашифрованном виде: для повторного ручного запуска поле можно оставить пустым. На новом устройстве потребуется ввести пароль снова.", color = Tx, fontSize = 11.sp)

                OutlinedButton(
                    onClick = {
                        try {
                            folderError = null
                            folderPicker.launch(null)
                        } catch (error: Exception) {
                            folderError = "Не удалось открыть выбор папки: ${error.message ?: "ошибка Android"}"
                            status = "Проверь доступность Google Drive / Яндекс Диска и повтори попытку."
                        }
                    },
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

                if (folderUri != null && passphrase.length < 12) {
                    Text("Для ручного и автоматического копирования задай пароль не короче 12 символов. Кнопка остаётся доступной и объяснит, чего не хватает.", color = Tx, fontSize = 10.sp)
                }
                manualBackupGuidance?.let { Text(it, color = Tx, fontSize = 11.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val selectedFolder = folderUri?.let { Uri.parse(it) }
                            val storedPassphrase = EncryptedApiKeyStore.load(
                                context, GlafiraBackupWorker.BACKUP_PASSPHRASE_KEY
                            )
                            val effectivePassphrase = BackupManualPassphrase.resolve(
                                entered = passphrase,
                                stored = storedPassphrase
                            )
                            when {
                                selectedFolder == null -> {
                                    manualBackupGuidance = "Сначала выбери папку Google Drive или Яндекс Диска."
                                    status = "Выбери папку облачного хранилища, чтобы начать резервирование."
                                }
                                effectivePassphrase.length < 12 -> status = "Введи пароль не короче 12 символов. Если пароль уже сохранялся на этом телефоне, поле можно оставить пустым."
                                !context.contentResolver.persistedUriPermissions.any {
                                    it.uri == selectedFolder && it.isWritePermission
                                } -> status = "Нет сохранённого разрешения на запись в выбранную папку. Выбери её повторно и подтверди доступ."
                                DocumentFile.fromTreeUri(context, selectedFolder)?.canWrite() != true ->
                                    status = "Облачная папка доступна только для чтения или провайдер не поддерживает запись."
                                passphrase.isNotBlank() && !EncryptedApiKeyStore.save(
                                    context, GlafiraBackupWorker.BACKUP_PASSPHRASE_KEY, effectivePassphrase
                                ) -> status = "Не удалось сохранить пароль в Android Keystore."
                                else -> {
                                    manualBackupGuidance = null
                                    BackupScheduler.runNow(context, wifiOnly)
                                    status = "Резервная копия поставлена в очередь. Пароль взят из защищённого хранилища; проверь результат загрузки ниже."
                                }
                            }
                        },
                        enabled = !busy,
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

                if (GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) {
                    OutlinedButton(onClick = {
                        val recoveryPassphrase = passphrase.toCharArray()
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    withCoreBackupCoordinator(context) { it.recover(recoveryPassphrase) }
                                }
                                status = "Откат завершён. Можно продолжить работу."
                            } catch (error: Exception) {
                                status = "Ошибка отката: ${error.message}"
                            } finally {
                                recoveryPassphrase.fill('\u0000')
                                busy = false
                            }
                        }
                    }, enabled = !busy && passphrase.length >= 12, modifier = Modifier.fillMaxWidth()) {
                        Text("Завершить прерванное восстановление")
                    }
                }

                if (lastSuccess > 0L) {
                    Text("Последняя успешная копия: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(lastSuccess))}", color = Gr, fontSize = 11.sp)
                }
                if (!lastError.isNullOrBlank()) Text("Последняя ошибка: $lastError", color = Red, fontSize = 11.sp)
                if (!persistedStatus.isNullOrBlank()) {
                    Text("Последний результат: $persistedStatus", color = Tx, fontSize = 10.sp)
                }
                val visibleStatus = if (busy) "Выполняется операция…" else status
                Text(
                    visibleStatus,
                    color = if (status.startsWith("Ошибка")) Red else Tx,
                    fontSize = 11.sp,
                    modifier = Modifier.semantics {
                        contentDescription = "Статус резервного копирования: $visibleStatus"
                    }
                )
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
                                        val verification = withCoreBackupCoordinator(context) { it.restore(temp, recoveryPassphrase) }
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
