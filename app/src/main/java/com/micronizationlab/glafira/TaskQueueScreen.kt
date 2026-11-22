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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun TaskQueueScreen(currentProject: Project, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = remember(context) { GlafiraCoreRuntimeOwner.get(context) }
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(owner.taskStore.listTasks(limit = 200)) }
    val projectTasks = tasks.filter { it.projectId == currentProject.id }
    var showAddDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(5) }
    var message by remember { mutableStateOf<String?>(null) }
    var runtimeEnabled by remember { mutableStateOf(owner.isEnabled) }
    var planning by remember { mutableStateOf(false) }
    var planningIdempotencyKey by remember { mutableStateOf(newPlanningIdempotencyKey()) }

    fun refresh() {
        tasks = owner.taskStore.listTasks(limit = 200)
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("←  НАЗАД", color = Color.White, modifier = Modifier.clickable { onBack() }.padding(4.dp))
            Text("ОЧЕРЕДЬ ЗАДАЧ", color = Color.White, fontWeight = FontWeight.Bold)
            Text("＋", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp).clickable { showAddDialog = true }.semantics { contentDescription = "Добавить задачу" })
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Проект: ${currentProject.name}", color = Gr, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Сохранение в зашифрованную очередь Core", color = Tx, fontSize = 10.sp)
            }
            Button(onClick = { showAddDialog = true }) { Text("＋ ЗАДАЧА") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Автообработка Core", color = Gr, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "Обрабатывает durable-очередь и создаёт Dry Run. Desktop LIVE, публикации и платежи остаются выключены.",
                    color = Tx,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = runtimeEnabled,
                onCheckedChange = { enabled ->
                    runCatching { owner.setEnabled(enabled) }
                        .onSuccess {
                            runtimeEnabled = enabled
                            message = if (enabled) "Core включён владельцем." else "Core приостановлен владельцем."
                        }
                        .onFailure { message = it.message ?: "Не удалось изменить режим Core." }
                },
                modifier = Modifier.semantics { contentDescription = "Автообработка Core" }
            )
        }
        message?.let {
            Text(it, color = Tx, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
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
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!planning) showAddDialog = false },
            title = { Text("Добавить задачу в очередь") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(value = instruction, onValueChange = { instruction = it }, label = { Text("Что нужно сделать") }, minLines = 3)
                    Text("Приоритет черновика: $priority (0 — обычный, 10 — высокий)", fontSize = 11.sp, color = Tx)
                    Slider(value = priority.toFloat(), onValueChange = { priority = it.toInt() }, valueRange = 0f..10f, steps = 9)
                    Text(
                        "Черновик только сохраняется. «Сформировать план» обращается к выбранному ИИ-провайдеру, проверяет DAG и атомарно сохраняет его в Core. Внешние действия, публикации и платежи не выполняются.",
                        color = Tx,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = !planning, onClick = {
                        val request = runCatching {
                            GlafiraOwnerPlanRequestFactory.create(
                                projectId = currentProject.id,
                                title = title,
                                instruction = instruction,
                                idempotencyKey = planningIdempotencyKey
                            )
                        }.onFailure {
                            message = it.message ?: "Не удалось подготовить запрос планирования."
                        }.getOrNull() ?: return@TextButton
                        planning = true
                        scope.launch {
                            runCatching { owner.submitInstruction(request) }
                                .onSuccess { result ->
                                    title = ""
                                    instruction = ""
                                    priority = 5
                                    planningIdempotencyKey = newPlanningIdempotencyKey()
                                    showAddDialog = false
                                    message = if (result.inserted) {
                                        "План проверен и сохранён: ${result.plan.nodes.size} задач."
                                    } else {
                                        "Этот план уже сохранён; дублирование не выполнено."
                                    }
                                    refresh()
                                }
                                .onFailure {
                                    message = when (it.message) {
                                        "TASK_PLANNER_PROVIDER_NOT_CONFIGURED" ->
                                            "Сначала настрой выбранного ИИ-провайдера. Черновик можно сохранить без него."
                                        "TASK_PLANNER_PROVIDER_REQUEST_FAILED" ->
                                            "ИИ-провайдер не вернул проверяемый план. Повтор использует тот же ключ и не создаст дубликат."
                                        else -> it.message ?: "Не удалось сформировать план."
                                    }
                                }
                            planning = false
                        }
                    }) { Text(if (planning) "ПЛАНИРОВАНИЕ…" else "СФОРМИРОВАТЬ ПЛАН") }
                    TextButton(enabled = !planning, onClick = {
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
                            planningIdempotencyKey = newPlanningIdempotencyKey()
                            showAddDialog = false
                            message = "Черновик сохранён в зашифрованной очереди."
                            refresh()
                        }.onFailure {
                            message = it.message ?: "Не удалось добавить задачу."
                        }
                    }) { Text("СОХРАНИТЬ ЧЕРНОВИК") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("ОТМЕНА") }
            }
        )
    }
}

private fun newPlanningIdempotencyKey(): String = "owner-plan-" + UUID.randomUUID().toString()
