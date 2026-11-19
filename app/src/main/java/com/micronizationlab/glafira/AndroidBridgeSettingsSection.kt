package com.micronizationlab.glafira

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun AndroidBridgeSettingsSection() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(
            GlafiraBridgeHostService.HOST_STATE_PREFS,
            Context.MODE_PRIVATE
        )
    }
    var running by remember { mutableStateOf(prefs.getBoolean(GlafiraBridgeHostService.HOST_RUNNING_KEY, false)) }
    var startupError by remember { mutableStateOf(prefs.getString(GlafiraBridgeHostService.HOST_ERROR_KEY, null)) }
    var lanAddress by remember { mutableStateOf(findPrivateIpv4Address()) }
    var invitation by remember { mutableStateOf<GlafiraBridgePairingInvitation?>(null) }
    var pending by remember { mutableStateOf(emptyList<GlafiraBridgePendingApproval>()) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var parallelTasks by remember(context) {
        mutableStateOf(GlafiraCoreRuntimeOwner.get(context).runtimeBudget().maxConcurrentTasks)
    }
    var budgetMessage by remember { mutableStateOf<String?>(null) }
    val port = GlafiraBridgeTlsHttpServer.DEFAULT_PORT

    fun refresh() {
        running = prefs.getBoolean(GlafiraBridgeHostService.HOST_RUNNING_KEY, false)
        startupError = prefs.getString(GlafiraBridgeHostService.HOST_ERROR_KEY, null)
        lanAddress = findPrivateIpv4Address()
        val store = runCatching { AndroidKeystoreBridgePairingApprovalStore(context) }.getOrNull()
        pending = if (store == null) {
            emptyList()
        } else {
            try {
                store.listPending(20)
            } finally {
                store.close()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(2000)
        }
    }

    Column {
        Text(
            "ANDROID BRIDGE",
            color = Gr,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (running) "ЗАЩИЩЁННЫЙ СЕРВЕР АКТИВЕН" else "ЗАЩИЩЁННЫЙ СЕРВЕР ОСТАНОВЛЕН",
                        color = if (running) Gr else Tx,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (running) "●" else "○", color = if (running) Gr else Tx)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (running) "HTTPS · TLS 1.2/1.3 · только локальная сеть · порт $port"
                    else "Сервер не запускается автоматически. Запуск доступен только по явному действию владельца.",
                    color = Tx,
                    fontSize = 10.sp
                )
                Text(
                    "Адрес устройства: ${lanAddress ?: "не найден"}",
                    color = Color(0xFF202020),
                    fontSize = 11.sp
                )
                if (startupError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Ошибка запуска: $startupError", color = Red, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !running && lanAddress != null,
                        onClick = {
                            val address = lanAddress
                            if (address == null) {
                                actionMessage = "Не удалось определить частный IPv4-адрес Wi-Fi/LAN."
                            } else {
                                runCatching {
                                    GlafiraBridgeHostService.startFromOwnerAction(context, address, port)
                                }.onSuccess {
                                    actionMessage = "Команда запуска отправлена. Проверяю состояние сервера…"
                                }.onFailure {
                                    actionMessage = it.message ?: "Не удалось запустить Bridge."
                                }
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
                    ) { Text("ЗАПУСТИТЬ") }
                    OutlinedButton(
                        enabled = running,
                        onClick = {
                            runCatching {
                                GlafiraBridgeHostService.stopFromOwnerAction(context)
                            }.onSuccess {
                                actionMessage = "Команда остановки отправлена."
                            }.onFailure {
                                actionMessage = it.message ?: "Не удалось остановить Bridge."
                            }
                            refresh()
                        }
                    ) { Text("ОСТАНОВИТЬ") }
                }
                actionMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Tx, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("ОДНОРАЗОВОЕ ПРИГЛАШЕНИЕ ДЛЯ DESKTOP", color = Gr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Приглашение действует 2 минуты. Секрет показывается только после нажатия и не сохраняется в открытом виде.",
                    color = Tx,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = running,
                    onClick = {
                        var store: AndroidKeystoreBridgePairingTicketStore? = null
                        invitation = runCatching {
                            store = AndroidKeystoreBridgePairingTicketStore(context)
                            GlafiraBridgePairingProtocol(store!!).issueInvitation()
                        }.onFailure {
                            actionMessage = it.message ?: "Не удалось создать приглашение."
                        }.getOrNull()
                        store?.close()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
                ) { Text("СОЗДАТЬ ПРИГЛАШЕНИЕ") }
                invitation?.let { item ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "КОД ПРИГЛАШЕНИЯ · ДЕЙСТВУЕТ ДО ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.expiresAtEpochMs))}",
                        color = Gr,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Ticket ID", color = Tx, fontSize = 9.sp)
                    Text(item.ticketId, color = Color(0xFF202020), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Одноразовый секрет", color = Tx, fontSize = 9.sp)
                    Text(item.pairingCapability, color = Color(0xFF202020), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "Не отправляй этот секрет в открытый чат и не используй его повторно. После истечения срока создай новое приглашение.",
                        color = Red,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("ПАРАЛЛЕЛЬНЫЕ ИСПОЛНИТЕЛИ GLAFIRA CORE", color = Gr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Количество независимых заданий, которые Core может обрабатывать одновременно. Диапазон: 1–8. Desktop Bridge дополнительно соблюдает собственный лимит узла.",
                    color = Tx,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("$parallelTasks из 8 исполнителей", color = Color(0xFF202020), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = parallelTasks.toFloat(),
                    onValueChange = { parallelTasks = it.roundToInt().coerceIn(1, GlafiraRuntimeBudget.MAX_CONCURRENT_TASKS) },
                    valueRange = 1f..GlafiraRuntimeBudget.MAX_CONCURRENT_TASKS.toFloat(),
                    steps = GlafiraRuntimeBudget.MAX_CONCURRENT_TASKS - 2,
                    onValueChangeFinished = {
                        runCatching {
                            val runtime = GlafiraCoreRuntimeOwner.get(context)
                            runtime.setRuntimeBudget(
                                runtime.runtimeBudget().copy(maxConcurrentTasks = parallelTasks)
                            )
                            if (runtime.isEnabled) GlafiraCoreWorkScheduler.scheduleImmediate(context)
                        }.onSuccess {
                            budgetMessage = "Лимит сохранён: $parallelTasks."
                        }.onFailure {
                            budgetMessage = it.message ?: "Не удалось сохранить лимит."
                        }
                    }
                )
                budgetMessage?.let { Text(it, color = Tx, fontSize = 10.sp) }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = GrD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ОЧЕРЕДЬ СОПРЯЖЕНИЯ", color = Gr, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${pending.size}", color = Tx, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Подтверждение запроса не выдаёт полномочия автоматически: права должны быть выбраны владельцем отдельно.",
                    color = Tx,
                    fontSize = 10.sp
                )
                if (pending.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Ожидающих запросов нет.", color = Tx, fontSize = 11.sp)
                } else {
                    pending.forEach { request ->
                        var selectedScopes by remember(request.approvalId) { mutableStateOf(emptySet<String>()) }
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.HorizontalDivider(color = Line)
                        Spacer(Modifier.height(6.dp))
                        Text(request.deviceLabel, color = Color(0xFF202020), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Платформа: ${request.platform}", color = Tx, fontSize = 10.sp)
                        Text("Запрошенные области (по умолчанию ничего не разрешено):", color = Tx, fontSize = 10.sp)
                        request.requestedScopes.sorted().forEach { scope ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = scope in selectedScopes,
                                    onCheckedChange = { checked ->
                                        selectedScopes = if (checked) selectedScopes + scope else selectedScopes - scope
                                    }
                                )
                                Text(scope, color = Color(0xFF202020), fontSize = 10.sp)
                            }
                        }
                        Text("Статус: ${request.status} · ${request.approvalId}", color = Tx, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = selectedScopes.isNotEmpty(),
                                onClick = {
                                    authenticateBridgeOwner(context) { ownerSession ->
                                        var store: AndroidKeystoreBridgePairingApprovalStore? = null
                                        val result = runCatching {
                                            store = AndroidKeystoreBridgePairingApprovalStore(context)
                                            GlafiraBridgeOwnerApprovalService(store!!).approve(
                                                request.approvalId,
                                                selectedScopes,
                                                ownerSession
                                            )
                                        }.getOrElse { GlafiraBridgeApprovalMutation.StorageError }
                                        store?.close()
                                        actionMessage = "Результат подтверждения: $result"
                                        refresh()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
                            ) { Text("ОДОБРИТЬ ВЫБРАННОЕ") }
                            OutlinedButton(
                                onClick = {
                                    authenticateBridgeOwner(context) { ownerSession ->
                                        var store: AndroidKeystoreBridgePairingApprovalStore? = null
                                        val rejected = runCatching {
                                            store = AndroidKeystoreBridgePairingApprovalStore(context)
                                            GlafiraBridgeOwnerApprovalService(store!!).reject(
                                                request.approvalId,
                                                ownerSession
                                            )
                                        }.getOrDefault(false)
                                        store?.close()
                                        actionMessage = if (rejected) "Запрос отклонён." else "Не удалось отклонить запрос."
                                        refresh()
                                    }
                                }
                            ) { Text("ОТКЛОНИТЬ") }
                        }
                    }
                }
            }
        }
    }
}

private fun findPrivateIpv4Address(): String? = runCatching {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
    val candidates = mutableListOf<String>()
    while (interfaces.hasMoreElements()) {
        val network = interfaces.nextElement()
        if (!network.isUp || network.isLoopback || network.isVirtual) continue
        val addresses = network.inetAddresses
        while (addresses.hasMoreElements()) {
            val address = addresses.nextElement() as? Inet4Address ?: continue
            if (address.isSiteLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                candidates.add(address.hostAddress ?: continue)
            }
        }
    }
    candidates.sorted().firstOrNull()
}.getOrNull()

private fun authenticateBridgeOwner(
    context: Context,
    onAuthenticated: (GlafiraBridgeOwnerSession) -> Unit
) {
    val activity = context as? FragmentActivity ?: return
    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val now = System.currentTimeMillis()
                onAuthenticated(
                    GlafiraBridgeOwnerSession(
                        sessionId = UUID.randomUUID().toString(),
                        expiresAtEpochMs = now + 3 * 60 * 1000L
                    )
                )
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Подтверждение владельца GLAFIRA")
        .setSubtitle("Разрешение на сопряжение устройства")
        .setAllowedAuthenticators(
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(info)
}
