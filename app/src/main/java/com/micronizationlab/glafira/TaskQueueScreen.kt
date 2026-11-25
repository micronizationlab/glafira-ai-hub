package com.micronizationlab.glafira

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TaskQueueScreen(currentProject: Project, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = remember(context) { GlafiraCoreRuntimeOwner.get(context) }
    var tasks by remember { mutableStateOf(owner.taskStore.listTasks(limit = 200)) }
    var approvals by remember {
        mutableStateOf(owner.pendingOwnerApprovals().filter { it.task.projectId == currentProject.id })
    }
    val projectTasks = tasks.filter { it.projectId == currentProject.id }
    var showAddDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(5) }
    var message by remember { mutableStateOf<String?>(null) }
    var cancelTaskTarget by remember { mutableStateOf<Triple<String, String, GlafiraTaskOwnershipRecord>?>(null) }
    var cancellingTask by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(currentProject.id) { cancelTaskTarget = null }

    fun refresh() {
        tasks = owner.taskStore.listTasks(limit = 200)
        approvals = owner.pendingOwnerApprovals().filter { it.task.projectId == currentProject.id }
    }

    fun authenticateApproval(approval: GlafiraPendingActionApproval, approve: Boolean) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            message = "Не удалось открыть подтверждение владельца из текущего окна."
            return
        }
        showBiometricPromptWithResult(
            activity = activity,
            onSuccess = { authenticationResult ->
                val session = GlafiraCoreOwnerSession.fromAuthentication(authenticationResult)
                val result = if (approve) {
                    owner.approveOwnerAction(approval.task.taskId, approval.requestedScopes, session)
                } else {
                    owner.rejectOwnerAction(approval.task.taskId, session)
                }
                message = when (result) {
                    GlafiraCoreOwnerApprovalMutation.APPROVED ->
                        "Подтверждено. Core разрешил только показанные действие и scopes."
                    GlafiraCoreOwnerApprovalMutation.REJECTED ->
                        "Действие отклонено. Core не будет его выполнять."
                    GlafiraCoreOwnerApprovalMutation.OWNER_AUTHENTICATION_REQUIRED ->
                        "Подтверждение владельца не принято. Повтори биометрическую проверку."
                    GlafiraCoreOwnerApprovalMutation.INVALID_SCOPE_SELECTION ->
                        "Набор разрешений не совпал с зарегистрированными scopes. Действие оставлено без разрешения."
                    GlafiraCoreOwnerApprovalMutation.NOT_FOUND ->
                        "Запрос подтверждения истёк или больше не существует."
                    GlafiraCoreOwnerApprovalMutation.ALREADY_DECIDED ->
                        "Этот запрос уже обработан или истёк."
                    GlafiraCoreOwnerApprovalMutation.STORAGE_ERROR ->
                        "Не удалось безопасно сохранить решение владельца."
                }
                refresh()
            },
            onError = { error ->
                message = "Биометрическое подтверждение отменено: $error"
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("←  НАЗАД", color = Color.White, modifier = Modifier.clickable { onBack() }.padding(4.dp))
            Text("ОЧЕРЕДЬ ЗАДАЧ", color = Color.White, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("↻", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp).clickable { refresh() }.semantics { contentDescription = "Обновить очередь и подтверждения" })
                Text("＋", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp).clickable { showAddDialog = true }.semantics { contentDescription = "Добавить задачу" })
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Проект: ${currentProject.name}", color = Gr, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Сохранение в зашифрованную очередь Core", color = Tx, fontSize = 10.sp)
            }
            Button(onClick = { showAddDialog = true }) { Text("＋ ЗАДАЧА") }
        }
        message?.let {
            Text(it, color = Tx, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        if (approvals.isNotEmpty()) {
            Text(
                "ТРЕБУЮТ ПОДТВЕРЖДЕНИЯ ВЛАДЕЛЬЦА",
                color = Red,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(horizontal = 10.dp)
            ) {
                items(approvals, key = { it.task.taskId }) { approval ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(approval.actionSummary.ifBlank { approval.task.taskType }, color = Gr, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Инструмент: ${approval.task.taskType}", color = Tx, fontSize = 10.sp)
                            Text("Детали действия:", color = Tx, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            Text(
                                approval.reviewDetails.ifBlank { "Дополнительные безопасно отображаемые детали отсутствуют." },
                                color = Color(0xFF202020),
                                fontSize = 10.sp
                            )
                            Text("Запрашиваемые права:", color = Tx, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            approval.requestedScopes.sorted().forEach { scope ->
                                Text("• $scope", color = Color(0xFF202020), fontSize = 10.sp)
                            }
                            Text(
                                "Решение относится только к этому task ID и точному хешу действия. Для подтверждения потребуется новая биометрическая проверка.",
                                color = Tx,
                                fontSize = 10.sp
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { authenticateApproval(approval, true) },
                                    enabled = approval.requestedScopes.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("ПОДТВЕРДИТЬ") }
                                OutlinedButton(
                                    onClick = { authenticateApproval(approval, false) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("ОТКЛОНИТЬ") }
                            }
                        }
                    }
                }
            }
        }

        if (projectTasks.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Очередь пока пуста.", color = Tx)
                Text("Нажми «＋ ЗАДАЧА», чтобы добавить поручение в текущий проект.", color = Tx, fontSize = 11.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                items(projectTasks, key = { it.taskId }) { task ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            val data = runCatching { JSONObject(task.payload) }.getOrNull()
                            Text(data?.optString("title").orEmpty().ifBlank { task.taskType }, color = Gr, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(data?.optString("instruction").orEmpty(), color = Color(0xFF202020), fontSize = 11.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Приоритет: ${task.priority}", color = Tx, fontSize = 10.sp)
                                Text(task.state.databaseValue, color = if (task.state == GlafiraTaskState.FAILED) Red else Gr, fontSize = 10.sp)
                            }
                            Text(
                                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(task.createdAtMillis)),
                                color = Tx, fontSize = 9.sp
                            )
                            task.lastError?.let { Text("Причина/результат: $it", color = Red, fontSize = 10.sp) }
                            if (GlafiraTaskPolicy.ownerCanCancel(task)) {
                                TextButton(onClick = {
                                    val ownership = owner.taskStore.taskOwnership(task.taskId, task.projectId)
                                    if (ownership == null) {
                                        message = "Нельзя отменить задачу: Core не подтвердил её владение."
                                    } else {
                                        cancelTaskTarget = Triple(currentProject.id, task.taskId, ownership)
                                    }
                                }) {
                                    Text("ОТМЕНИТЬ ЗАДАЧУ")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    cancelTaskTarget?.let { (projectId, taskId, ownership) ->
        AlertDialog(
            onDismissRequest = { if (!cancellingTask) cancelTaskTarget = null },
            title = { Text("Отменить задачу?") },
            text = {
                Text("Задача будет отменена только если Core подтвердит, что она ещё не была запущена. Неопределённый результат внешнего действия не отменяется. Для подтверждения потребуется новая биометрическая проверка владельца.")
            },
            confirmButton = {
                TextButton(enabled = !cancellingTask, onClick = {
                    val activity = context as? FragmentActivity
                    if (activity == null) {
                        message = "Не удалось открыть подтверждение владельца из текущего окна."
                        cancelTaskTarget = null
                    } else {
                        cancellingTask = true
                        showBiometricPromptWithResult(
                            activity = activity,
                            onSuccess = { authenticationResult ->
                                val session = GlafiraCoreOwnerSession.fromAuthentication(authenticationResult)
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            owner.cancelTask(
                                                taskId, projectId, ownership.ownerCoreId,
                                                ownership.ownerGeneration, ownership.stateVersion, session
                                            )
                                        }
                                    }
                                    cancellingTask = false
                                    message = when {
                                        result.isFailure -> result.exceptionOrNull()?.message
                                            ?: "Не удалось отменить задачу."
                                        result.getOrDefault(false) -> "Задача отменена."
                                        else -> "Задача уже запущена или больше не доступна для отмены."
                                    }
                                    cancelTaskTarget = null
                                    refresh()
                                }
                            },
                            onError = { error ->
                                cancellingTask = false
                                message = "Биометрическое подтверждение отменено: $error"
                                cancelTaskTarget = null
                            }
                        )
                    }
                }) { Text(if (cancellingTask) "ПРОВЕРКА…" else "ПОДТВЕРДИТЬ") }
            },
            dismissButton = {
                TextButton(enabled = !cancellingTask, onClick = { cancelTaskTarget = null }) {
                    Text("НЕТ")
                }
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Добавить задачу в очередь") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(value = instruction, onValueChange = { instruction = it }, label = { Text("Что нужно сделать") }, minLines = 3)
                    Text("Приоритет: $priority (0 — обычный, 10 — высокий)", fontSize = 11.sp, color = Tx)
                    Slider(value = priority.toFloat(), onValueChange = { priority = it.toInt() }, valueRange = 0f..10f, steps = 9)
                    Text("Задача сохраняется как черновое поручение. Внешние действия, публикации и платежи не выполняются без отдельного подтверждения владельца.", color = Tx, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val task = GlafiraManualTaskFactory.create(
                            projectId = currentProject.id,
                            title = title,
                            instruction = instruction,
                            priority = priority
                        )
                        val result = owner.enqueue(task)
                        check(result.inserted || result.task.taskId == task.taskId) { "Задача с таким ключом уже существует." }
                    }.onSuccess {
                        title = ""
                        instruction = ""
                        priority = 5
                        showAddDialog = false
                        message = "Задача сохранена в зашифрованной очереди."
                        refresh()
                    }.onFailure {
                        message = it.message ?: "Не удалось добавить задачу."
                    }
                }) { Text("ДОБАВИТЬ") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("ОТМЕНА") }
            }
        )
    }
}
