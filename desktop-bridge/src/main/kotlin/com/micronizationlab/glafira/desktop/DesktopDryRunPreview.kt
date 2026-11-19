package com.micronizationlab.glafira.desktop

import org.json.JSONArray
import org.json.JSONObject

/** Validated, redacted preview envelope received from Core; never an executable task. */
internal data class DesktopDryRunPreview(
    val taskId: String,
    val projectId: String,
    val toolId: String,
    val risk: String,
    val simulationStatus: String,
    val planHash: String,
    val safeJson: String
)

internal object DesktopDryRunPreviewCodec {
    private val expectedKeys = setOf(
        "mode", "taskId", "projectId", "toolId", "risk", "simulationStatus",
        "targetFields", "expectedChanges", "requiredPermissions", "approvalPoints",
        "authorizationStatus", "authorizationReason", "planHash", "limitations"
    )
    private val safeTargetFields = setOf(
        "target", "recipient", "channel", "recordId", "conversationId",
        "fileName", "amount", "currency", "url", "projectId"
    )

    fun decode(source: String): DesktopDryRunPreview {
        require(source.toByteArray(Charsets.UTF_8).size in 1..8_192)
        val json = JSONObject(source)
        val actualKeys = buildSet {
            val keys = json.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(actualKeys == expectedKeys) { "Desktop Dry Run preview schema mismatch." }
        require(json.getString("mode") == "DRY_RUN")
        val taskId = json.getString("taskId")
        val projectId = json.getString("projectId")
        val toolId = json.getString("toolId")
        require(taskId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        require(projectId.isNotBlank() && projectId.length <= 128)
        require(toolId.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        val risk = json.getString("risk")
        require(risk in setOf("READ_ONLY", "DRAFT", "EXTERNAL_WRITE", "FINANCIAL", "DESTRUCTIVE"))
        val simulationStatus = json.getString("simulationStatus")
        require(simulationStatus in setOf("PREVIEW_ONLY", "NOT_SIMULATABLE"))
        require(json.getString("authorizationStatus") in setOf("AUTHORIZED", "WAITING_FOR_OWNER", "DENIED"))
        val hash = json.getString("planHash")
        require(hash.matches(Regex("[0-9a-f]{64}")))
        validateStringArray(json.getJSONArray("targetFields"), 10, 64).also {
            require(it.all { field -> field in safeTargetFields })
        }
        validateStringArray(json.getJSONArray("expectedChanges"), 16, 256)
        validateStringArray(json.getJSONArray("requiredPermissions"), 32, 128)
        validateStringArray(json.getJSONArray("approvalPoints"), 16, 256)
        validateStringArray(json.getJSONArray("limitations"), 16, 256)
        val reason = json.opt("authorizationReason")
        require(reason == JSONObject.NULL || (reason is String && reason.length <= 128))
        return DesktopDryRunPreview(
            taskId = taskId,
            projectId = projectId,
            toolId = toolId,
            risk = risk,
            simulationStatus = simulationStatus,
            planHash = hash,
            safeJson = json.toString()
        )
    }

    private fun validateStringArray(array: JSONArray, maxItems: Int, maxLength: Int): List<String> {
        require(array.length() <= maxItems)
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.get(index)
                require(value is String && value.isNotBlank() && value.length <= maxLength)
                add(value)
            }
        }
    }
}
