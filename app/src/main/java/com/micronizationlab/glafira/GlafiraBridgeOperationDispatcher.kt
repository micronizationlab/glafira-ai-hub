package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes only the small, typed, local Core operation set after Bridge admission.
 * It cannot invoke tools, providers, external services, arbitrary paths or shell commands.
 */
class GlafiraBridgeOperationDispatcher(context: Context) {
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
                    val messages = messagesJson(loadHistory(appContext, conversationId))
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
                    conversationFile(request.projectId, conversationId) // validate the same project-bound history key
                    val now = Date()
                    val messages = appendHistory(appContext, conversationId, listOf(Msg(
                        text = messageText,
                        isUser = true,
                        time = SimpleDateFormat("HH:mm", Locale.ROOT).format(now),
                        date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now),
                        operationId = "bridge:${request.projectId}:${request.idempotencyKey}"
                    )))
                    succeeded(
                        request,
                        mapOf(
                            "projectId" to project.first,
                            "conversationId" to conversationId,
                            "messageCount" to messages.size.toString()
                        )
                    )
                }
                GlafiraBridgeOperation.APPROVAL_REQUEST ->
                    failed(request, "APPROVAL_QUEUE_NOT_CONNECTED")
                GlafiraBridgeOperation.TASK_STATUS,
                GlafiraBridgeOperation.TASK_RESULT_ACK,
                GlafiraBridgeOperation.DESKTOP_TASK_CLAIM,
                GlafiraBridgeOperation.DESKTOP_TASK_RESULT ->
                    failed(request, "DESKTOP_OPERATIONS_REQUIRE_CORE_QUEUE")
            }
        } catch (_: Exception) {
            failed(request, "OPERATION_FAILED")
        }
    }

    private fun findProject(projectId: String): Pair<String, String>? =
        loadProjects(appContext).firstOrNull { it.id == projectId }?.let { it.id to it.name }

    private fun conversationFile(projectId: String, conversationId: String): File {
        require(isAllowedConversationId(projectId, conversationId))
        val file = File(filesRoot, "chat_$conversationId.json")
        require(isDirectChild(file))
        return file
    }

    private fun messagesJson(messages: List<Msg>): JSONArray = JSONArray().apply {
        messages.forEach { message -> put(JSONObject().apply {
            put("text", message.text); put("isUser", message.isUser)
            put("time", message.time); put("date", message.date)
        }) }
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
