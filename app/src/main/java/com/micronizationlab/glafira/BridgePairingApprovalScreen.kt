package com.micronizationlab.glafira

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import java.util.Base64
import java.util.UUID

@Composable
fun BridgePairingApprovalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val store = remember(context) { AndroidKeystoreBridgePairingApprovalStore(context) }
    val service = remember(store) { GlafiraBridgeOwnerApprovalService(store) }
    var ownerSession by remember { mutableStateOf<GlafiraBridgeOwnerSession?>(null) }
    var pending by remember { mutableStateOf(emptyList<GlafiraBridgePendingApproval>()) }
    var selectedScopes by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var delivery by remember { mutableStateOf<GlafiraBridgeCredentialDelivery?>(null) }
    var deliveryText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Для просмотра и подтверждения устройств подтвердите личность владельца.") }
    var isBusy by remember { mutableStateOf(false) }

    val availableScopes = remember {
        GlafiraToolRegistry.all().flatMap { it.requiredScopes }.toSortedSet()
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val previousSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (previousSecure == false) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            store.close()
        }
    }

    fun refreshQueue() {
        if (ownerSession == null) return
        pending = service.pendingApprovals()
    }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = androidx.compose.ui.graphics.Color.White, fontSize = 22.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 14.dp))
            Text("СОПРЯЖЕНИЕ УСТРОЙСТВ", color = androidx.compose.ui.graphics.Color.White,
                fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Подтверждение нового устройства",
            color = Gr, fontSize = 17.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Одноразовый код сопряжения сам по себе не даёт доступ. Только владелец выбирает права и выпускает постоянный ключ.",
            color = Tx, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        if (ownerSession == null || !ownerSession!!.isValid(System.currentTimeMillis())) {
            Button(
                onClick = {
                    if (activity == null) {
                        status = "Не удалось открыть системную проверку владельца."
                    } else if (!canUseBiometric(context)) {
                        status = "На устройстве не настроена биометрия или PIN-код блокировки."
                    } else {
                        showBiometricPrompt(
                            activity = activity,
                            onSuccess = {
                                val now = System.currentTimeMillis()
                                val session = GlafiraBridgeOwnerSession(
                                    UUID.randomUUID().toString(),
                                    now + OWNER_SESSION_LIFETIME_MS
                                )
                                ownerSession = session
                                status = "Владелец подтверждён. Сессия действует 2 минуты."
                                pending = service.pendingApprovals()
                                val undelivered = service.pendingCredentialDeliveries(session, now).firstOrNull()
                                if (undelivered != null) {
                                    when (val recovered = service.claimCredentialDelivery(
                                        undelivered.approvalId, session, now
                                    )) {
                                        is GlafiraBridgeDeliveryResult.Available -> {
                                            delivery = recovered.delivery
                                            deliveryText = null
                                            status = "Найден невручённый ключ устройства. Завершите его передачу."
                                        }
                                        else -> status = "Очередь обновлена; не удалось восстановить выдачу: $recovered"
                                    }
                                }
                            },
                            onError = { status = "Проверка владельца не пройдена: $it" }
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Gr)
            ) {
                Text("ПОДТВЕРДИТЬ ВЛАДЕЛЬЦА")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Владелец подтверждён", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { refreshQueue() }) { Text("ОБНОВИТЬ") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(status, color = Tx, fontSize = 11.sp)

        delivery?.let { issued ->
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GrD)) {
                Column(Modifier.padding(14.dp)) {
                    Text("КЛЮЧ УСТРОЙСТВА СОЗДАН", color = Gr, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("ID: ${issued.deviceId}", color = Tx, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "HMAC: ${Base64.getUrlEncoder().withoutPadding().encodeToString(issued.hmacSecret)}",
                        color = Tx, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val connection = AndroidKeystoreBridgeServerIdentityStore(context)
                                .currentConnectionDetails()
                            if (connection == null) {
                                status = "Нет сохранённой TLS-идентичности сервера. Сначала запустите локальный Bridge."
                                return@Button
                            }
                            val payload = org.json.JSONObject().apply {
                                put("format", "GLAFIRA_BRIDGE_CREDENTIAL_V1")
                                put("origin", connection.origin)
                                put("deviceId", issued.deviceId)
                                put("hmacSecretBase64Url", Base64.getUrlEncoder().withoutPadding().encodeToString(issued.hmacSecret))
                                put("grantedScopes", org.json.JSONArray(issued.grantedScopes.sorted()))
                                put("serverCertificateSha256", connection.certificateSha256)
                            }.toString()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("GLAFIRA encrypted-import bundle", payload)
                            if (Build.VERSION.SDK_INT >= 33) {
                                clip.description.extras = android.os.PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                            }
                            clipboard.setPrimaryClip(clip)
                            deliveryText = payload
                            status = "Пакет сопряжения скопирован. Импортируйте его в Desktop-хранилище с паролем владельца."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Gr)
                    ) { Text("СКОПИРОВАТЬ ДАННЫЕ СОПРЯЖЕНИЯ") }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            if (clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() == deliveryText) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                            }
                            if (service.acknowledgeCredentialDelivery(
                                    issued.approvalId, ownerSession!!, System.currentTimeMillis()
                                )
                            ) {
                                issued.hmacSecret.fill(0)
                                delivery = null
                                deliveryText = null
                                status = "Получение подтверждено. Временная копия секрета удалена."
                                refreshQueue()
                            } else status = "Не удалось подтвердить получение. Повторно подтвердите владельца."
                        }
                    ) { Text("Я ПЕРЕДАЛ КЛЮЧ — УДАЛИТЬ ВРЕМЕННУЮ КОПИЮ") }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("ОЖИДАЮТ РЕШЕНИЯ: ${pending.size}", color = Gr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (ownerSession != null && pending.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Новых подтверждённых запросов пока нет.", color = Tx, fontSize = 12.sp)
        }

        pending.forEach { request ->
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GrD)) {
                Column(Modifier.padding(12.dp)) {
                    Text(request.deviceLabel, color = androidx.compose.ui.graphics.Color(0xFF202020),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Платформа: ${request.platform} · Запрос: ${request.approvalId}",
                        color = Tx, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text("Запрошенные права (не выданы): ${request.requestedScopes.joinToString().ifBlank { "не указаны" }}",
                        color = Tx, fontSize = 10.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("ВЫБЕРИТЕ РАЗРЕШЁННЫЕ ПРАВА", color = Gr, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    availableScopes.forEach { permission ->
                        val selected = permission in selectedScopes[request.approvalId].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    val current = selectedScopes[request.approvalId].orEmpty()
                                    selectedScopes = selectedScopes + (
                                        request.approvalId to if (checked) current + permission else current - permission
                                    )
                                }
                            )
                            Text(permission, color = Tx, fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = ownerSession?.isValid(System.currentTimeMillis()) == true &&
                                selectedScopes[request.approvalId].orEmpty().isNotEmpty() && !isBusy,
                            onClick = {
                                val session = ownerSession ?: return@Button
                                isBusy = true
                                when (val result = service.approve(
                                    request.approvalId,
                                    selectedScopes[request.approvalId].orEmpty(),
                                    session
                                )) {
                                    is GlafiraBridgeApprovalMutation.Approved -> {
                                        when (val claim = service.claimCredentialDelivery(
                                            request.approvalId, session
                                        )) {
                                            is GlafiraBridgeDeliveryResult.Available -> {
                                                delivery = claim.delivery
                                                deliveryText = null
                                                status = "Выдайте ключ только сопрягаемому устройству."
                                            }
                                            else -> status = "Ключ создан, но его выдача пока недоступна: $claim"
                                        }
                                        refreshQueue()
                                    }
                                    else -> status = "Подтверждение не выполнено: $result"
                                }
                                isBusy = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Gr)
                        ) { Text("РАЗРЕШИТЬ") }
                        OutlinedButton(
                            enabled = ownerSession?.isValid(System.currentTimeMillis()) == true && !isBusy,
                            onClick = {
                                val session = ownerSession ?: return@OutlinedButton
                                if (service.reject(request.approvalId, session)) {
                                    status = "Запрос отклонён. Ключ не создан."
                                    refreshQueue()
                                } else status = "Не удалось отклонить запрос."
                            }
                        ) { Text("ОТКЛОНИТЬ") }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("НАЗАД") }
    }
}

private const val OWNER_SESSION_LIFETIME_MS = 120_000L
