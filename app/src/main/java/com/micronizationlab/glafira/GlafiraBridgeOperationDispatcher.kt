package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes only the small, typed, local Core operation set after Bridge admission.
 * It cannot invoke tools, providers, external services, arbitrary paths or shell commands.
 */
class GlafiraBridgeOperationDispatcher(
    context: Context,
    private val coreOwner: GlafiraCoreRuntimeOwner? = null
) {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir.canonicalFile

    fun dispatch(request: GlafiraBridgeRequest): GlafiraBridgeResponse {
        return try {
            val project = findProject(request.projectId)
                ?: return failed(request, "PROJECT_NOT_FOUND")
            when (request.operation) {
                GlafiraBridgeOperation.PROJECT_READ -> succeeded(
                    request,
                    mapOf("projectId" to project.first, "projectName" to project.second)
                )
                GlafiraBridgeOperation.CONVERSATION_READ -> {
                    val conversationId = request.payload.getValue("conversationId")
                    val file = conversationFile(request.projectId, conversationId)
                    if (!file.isFile) return failed(request, "CONVERSATION_NOT_FOUND")
                    val messages = readMessages(file)
                    succeeded(
                        request,
                        mapOf(
                            "projectId" to project.first,
                            "conversationId" to conversationId,
                            "messageCount" to messages.length().toString(),
                            "messagesJson" to messages.toString()
                        )
                    )
                }
                GlafiraBridgeOperation.CONVERSATION_APPEND -> {
                    val conversationId = request.payload.getValue("conversationId")
                    val messageText = request.payload.getValue("messageText")
                    require(messageText.isNotBlank() && messageText.length <= MAX_MESSAGE_CHARS)
                    val file = conversationFile(request.projectId, conversationId)
                    val messages = if (file.exists()) readMessages(file) else JSONArray()
                    require(messages.length() < MAX_MESSAGES)
                    val now = Date()
                    messages.put(JSONObject().apply {
                        put("text", messageText)
                        put("isUser", true)
                        put("time", SimpleDateFormat("HH:mm", Locale.ROOT).format(now))
                        put("date", SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now))
                    })
                    val encodedMessages = messages.toString().toByteArray(Charsets.UTF_8)
                    try {
                        require(encodedMessages.size <= MAX_CONVERSATION_BYTES) {
                            "Conversation exceeds the Bridge storage limit."
                        }
                        writeAtomically(file, encodedMessages)
                    } finally {
                        encodedMessages.fill(0)
                    }
                    succeeded(
                        request,
                        mapOf(
                            "projectId" to project.first,
                            "conversationId" to conversationId,
                            "messageCount" to messages.length().toString()
                        )
                    )
                }
                GlafiraBridgeOperation.APPROVAL_REQUEST ->
                    failed(request, "APPROVAL_QUEUE_NOT_CONNECTED")
                GlafiraBridgeOperation.DESKTOP_TASK_CLAIM -> dispatchDesktopTaskClaim(request)
                GlafiraBridgeOperation.DESKTOP_TASK_RESULT -> dispatchDesktopTaskResult(request)
            }
        } catch (_: Exception) {
            failed(request, "OPERATION_FAILED")
        }
    }

    /**
     * Called only after Bridge identity, HMAC, replay and scope admission have succeeded.
     * The Desktop worker receives either a redacted DRY_RUN preview or an authorized lease.
     */
    private fun dispatchDesktopTaskClaim(request: GlafiraBridgeRequest): GlafiraBridgeResponse {
        val owner = coreOwner ?: return failed(request, "CORE_QUEUE_UNAVAILABLE")
        return try {
            val capabilitiesJson = JSONArray(request.payload.getValue("capabilities"))
            val capabilities = buildSet {
                for (index in 0 until capabilitiesJson.length()) add(capabilitiesJson.getString(index))
            }
            val maxParallel = request.payload.getValue("maxParallelTasks").toInt()
            owner.recordDesktopNode(request.deviceId, capabilities, maxParallel)

            val preview = owner.previewDesktopTask(capabilities)
            if (preview != null) {
                return succeeded(request, mapOf(
                    "dryRunPreview" to "true",
                    "previewJson" to preview.toSafeJson()
                ))
            }

            val task = owner.claimDesktopTask(request.deviceId, capabilities, maxParallel)
                ?: return succeeded(request, mapOf("available" to "false"))
            val payload = JSONObject(task.payload)
            val required = payload.optJSONArray("requiredCapabilities") ?: JSONArray()
            succeeded(request, mapOf(
                "available" to "true",
                "taskId" to task.taskId,
                "projectId" to task.projectId,
                "parentTaskId" to payload.getString("parentTaskId"),
                "subtaskId" to payload.getString("subtaskId"),
                "title" to payload.getString("title"),
                "instruction" to payload.getString("instruction"),
                "executor" to payload.getString("executor"),
                "requiredCapabilities" to required.toString(),
                "createdAtEpochMs" to task.createdAtMillis.toString(),
                "expiresAtEpochMs" to (task.deadlineAtMillis ?: task.createdAtMillis + 60_000L).toString(),
                "priority" to task.priority.toString()
            ))
        } catch (_: Exception) {
            failed(request, "DESKTOP_TASK_CLAIM_FAILED")
        }
    }

    private fun dispatchDesktopTaskResult(request: GlafiraBridgeRequest): GlafiraBridgeResponse {
        val owner = coreOwner ?: return failed(request, "CORE_QUEUE_UNAVAILABLE")
        return try {
            val taskId = request.payload.getValue("taskId")
            val updated = owner.updateDesktopTask(
                taskId = taskId,
                deviceId = request.deviceId,
                state = request.payload.getValue("state"),
                progressPercent = request.payload.getValue("progressPercent").toInt(),
                progressMessage = request.payload["progressMessage"]?.takeUnless { it == "none" },
                result = request.payload["result"]?.takeUnless { it == "none" },
                errorCode = request.payload["errorCode"]?.takeUnless { it == "none" }
            )
            if (!updated) failed(request, "DESKTOP_TASK_RESULT_REJECTED")
            else succeeded(request, mapOf("updated" to "true", "taskId" to taskId))
        } catch (_: Exception) {
            failed(request, "DESKTOP_TASK_RESULT_FAILED")
        }
    }

    private fun findProject(projectId: String): Pair<String, String>? {
        val file = File(filesRoot, PROJECTS_FILE)
        if (!isDirectChild(file) || !file.isFile || file.length() !in 1..MAX_PROJECTS_FILE_BYTES) return null
        val projects = JSONArray(file.readText(Charsets.UTF_8))
        if (projects.length() > MAX_PROJECTS) return null
        for (index in 0 until projects.length()) {
            val item = projects.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name")
            if (id == projectId && name.isNotBlank() && name.length <= MAX_PROJECT_NAME_CHARS) {
                return id to name
            }
        }
        return null
    }

    private fun conversationFile(projectId: String, conversationId: String): File {
        require(isAllowedConversationId(projectId, conversationId))
        val file = File(filesRoot, "chat_$conversationId.json")
        require(isDirectChild(file))
        return file
    }

    private fun readMessages(file: File): JSONArray {
        require(isDirectChild(file) && file.isFile && file.length() in 1..MAX_CONVERSATION_BYTES)
        val parsed = JSONArray(file.readText(Charsets.UTF_8))
        require(parsed.length() <= MAX_MESSAGES)
        val safe = JSONArray()
        for (index in 0 until parsed.length()) {
            val item = parsed.getJSONObject(index)
            val text = item.getString("text")
            require(text.length <= MAX_MESSAGE_CHARS)
            val isUser = item.getBoolean("isUser")
            val time = item.optString("time", "")
            val date = item.optString("date", "")
            require(time.length <= 16 && date.length <= 16)
            safe.put(JSONObject().apply {
                put("text", text)
                put("isUser", isUser)
                put("time", time)
                put("date", date)
            })
        }
        return safe
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        require(isDirectChild(target))
        val temp = Files.createTempFile(filesRoot.toPath(), ".glafira-bridge-", ".tmp").toFile()
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
            bytes.fill(0)
        }
    }

    private fun isDirectChild(file: File): Boolean {
        val normalizedPath = file.toPath().toAbsolutePath().normalize()
        val normalizedFile = normalizedPath.toFile()
        return normalizedFile.parentFile == filesRoot &&
            !Files.isSymbolicLink(normalizedPath) &&
            normalizedFile.canonicalFile == normalizedFile
    }

    private fun succeeded(request: GlafiraBridgeRequest, result: Map<String, String>) =
        GlafiraBridgeResponse(
            GlafiraBridgeProtocol.CURRENT_VERSION,
            request.requestId,
            GlafiraBridgeStatus.SUCCEEDED,
            result
        )

    private fun failed(request: GlafiraBridgeRequest, code: String) =
        GlafiraBridgeResponse(
            GlafiraBridgeProtocol.CURRENT_VERSION,
            request.requestId,
            GlafiraBridgeStatus.FAILED,
            errorCode = code
        )

    companion object {
        private const val PROJECTS_FILE = "projects.json"
        private const val MAX_PROJECTS_FILE_BYTES = 256 * 1024L
        private const val MAX_PROJECTS = 2_000
        private const val MAX_PROJECT_NAME_CHARS = 128
        private const val MAX_CONVERSATION_BYTES = 512 * 1024L
        private const val MAX_MESSAGES = 2_000
        private const val MAX_MESSAGE_CHARS = 16_384
        private val KNOWN_AGENT_IDS = setOf(
            "ГЛАФИРА", "АНАЛИТИК", "ПРОГРАММИСТ", "GigaChat", "ChatGPT",
            "DeepSeek", "Grok", "Алиса", "Gemini", "OpenRouter Free"
        )

        internal fun isAllowedConversationId(projectId: String, conversationId: String): Boolean {
            val prefix = "${projectId}_"
            if (!conversationId.startsWith(prefix)) return false
            val agentId = conversationId.removePrefix(prefix)
            return agentId in KNOWN_AGENT_IDS
        }
    }
}
