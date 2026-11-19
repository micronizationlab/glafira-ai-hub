package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class GlafiraExecutionMode { DRY_RUN, LIVE }
enum class GlafiraDryRunSimulationStatus { PREVIEW_ONLY, NOT_SIMULATABLE }

/**
 * A redacted, deterministic preview. It deliberately contains field names, never payload
 * values, credentials, message bodies, recipient addresses or provider responses.
 */
data class GlafiraDryRunPlan(
    val taskId: String,
    val projectId: String,
    val toolId: String,
    val risk: ToolRiskLevel,
    val simulationStatus: GlafiraDryRunSimulationStatus,
    val targetFields: List<String>,
    val expectedChanges: List<String>,
    val requiredPermissions: List<String>,
    val approvalPoints: List<String>,
    val authorizationStatus: GlafiraTaskAuthorizationStatus,
    val authorizationReason: String?,
    val planHash: String,
    val limitations: List<String>
) {
    /** Compact, safe representation suitable for the task's bounded status detail. */
    fun toSafeJson(): String = JSONObject().apply {
        put("mode", GlafiraExecutionMode.DRY_RUN.name)
        put("taskId", taskId)
        put("projectId", projectId)
        put("toolId", toolId)
        put("risk", risk.name)
        put("simulationStatus", simulationStatus.name)
        put("targetFields", JSONArray(targetFields))
        put("expectedChanges", JSONArray(expectedChanges))
        put("requiredPermissions", JSONArray(requiredPermissions))
        put("approvalPoints", JSONArray(approvalPoints))
        put("authorizationStatus", authorizationStatus.name)
        put("authorizationReason", authorizationReason)
        put("planHash", planHash)
        put("limitations", JSONArray(limitations))
    }.toString()
}

object GlafiraDryRunPlanner {
    private val safeTargetFieldNames = setOf(
        "target", "recipient", "channel", "recordId", "conversationId",
        "fileName", "amount", "currency", "url", "projectId"
    )

    fun build(
        task: GlafiraTask,
        authorization: GlafiraTaskAuthorizationResult = GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
    ): GlafiraDryRunPlan {
        val definition = GlafiraToolRegistry.find(task.taskType)
        val parsed = runCatching { JSONObject(task.payload) }.getOrNull()
        val targetFields = parsed?.keys()?.asSequence()
            ?.filter { it in safeTargetFieldNames }
            ?.sorted()
            ?.toList()
            .orEmpty()
        val risk = definition?.risk ?: ToolRiskLevel.DESTRUCTIVE
        val summaryPresent = parsed?.optString("actionSummary")?.isNotBlank() == true
        val previewable = definition != null && parsed != null && summaryPresent &&
            risk in setOf(ToolRiskLevel.READ_ONLY, ToolRiskLevel.DRAFT)
        val expected = if (task.taskType == GlafiraTaskStore.DESKTOP_TASK_TYPE) {
            listOf("Задание может быть передано Desktop-узлу только в отдельном LIVE запуске; сейчас передача и исполнение не выполняются.")
        } else when (risk) {
            ToolRiskLevel.READ_ONLY -> listOf("Проверить доступность данных без их изменения.")
            ToolRiskLevel.DRAFT -> listOf("Подготовить локальный черновик операции; не сохранять и не отправлять его.")
            ToolRiskLevel.EXTERNAL_WRITE -> listOf("Предлагаемое внешнее изменение не выполняется.")
            ToolRiskLevel.FINANCIAL -> listOf("Платёжная операция не создаётся и не отправляется.")
            ToolRiskLevel.DESTRUCTIVE -> listOf("Удаление или необратимое изменение не выполняется.")
        }
        val approvals = buildList {
            if (GlafiraToolRegistry.requiresOwnerApproval(risk)) {
                add("Требуется отдельное подтверждение владельца для точного действия.")
            }
            if (authorization.status != GlafiraTaskAuthorizationStatus.AUTHORIZED) {
                add("Core policy requires owner review before LIVE execution.")
            }
            add("Перед LIVE потребуется отдельное задание с разрешением, связанным с этим planHash.")
        }
        val limitations = buildList {
            if (parsed == null) add("Payload не является корректным JSON; симуляция недоступна.")
            if (!previewable) add("Для этого инструмента нет проверенного безопасного симулятора.")
            if (!summaryPresent) add("В задании отсутствует actionSummary; точное действие нельзя считать полностью описанным.")
            if (authorization.status != GlafiraTaskAuthorizationStatus.AUTHORIZED) {
                add("Live execution is not authorized: ${authorization.reason ?: authorization.status.name}.")
            }
            add("Dry Run не вызывает обработчик инструмента и не обращается к внешним сервисам.")
            add("Значения полей и секреты намеренно скрыты; перечислены только безопасные имена полей.")
        }
        val canonical = listOf(
            task.taskId, task.projectId, task.taskType, task.origin,
            task.payload, task.requiredScopes.sorted().joinToString("\u0000"),
            risk.name, if (previewable) "PREVIEW_ONLY" else "NOT_SIMULATABLE"
        ).joinToString("") { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return GlafiraDryRunPlan(
            taskId = task.taskId,
            projectId = task.projectId,
            toolId = task.taskType,
            risk = risk,
            simulationStatus = if (previewable) GlafiraDryRunSimulationStatus.PREVIEW_ONLY
                else GlafiraDryRunSimulationStatus.NOT_SIMULATABLE,
            targetFields = targetFields,
            expectedChanges = expected,
            requiredPermissions = ((definition?.requiredScopes ?: emptySet()) + task.requiredScopes).sorted(),
            approvalPoints = approvals,
            authorizationStatus = authorization.status,
            authorizationReason = authorization.reason,
            planHash = hash,
            limitations = limitations
        )
    }
}
