package com.micronizationlab.glafira

import org.json.JSONObject
import java.util.UUID

/**
 * The only supported Bridge-to-Core ingress seam.
 *
 * Call this only after TLS device authentication, pairing lookup, request signature,
 * replay/idempotency and Bridge protocol validation have succeeded. The caller must pass
 * the device identity obtained from the trusted pairing store, never from an untrusted body.
 *
 * This adapter deliberately does not execute an operation. It translates the typed request
 * into a durable Core task so the Core queue and RegistryBackedGlafiraTaskAuthorizer remain
 * the sole scheduling and authorization path.
 */
class GlafiraCoreBridgeIngress(
    private val runtime: GlafiraCoreRuntimeOwner,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Operation(val toolId: String, val requiredScope: String) {
        PROJECT_READ("project.read", "project.read"),
        CONVERSATION_READ("conversation.read", "conversation.read"),
        CONVERSATION_APPEND("conversation.append", "conversation.append")
    }

    // Internal construction keeps this trust-boundary DTO out of public callers.
    // Only the authenticated Bridge ingress in this application module may create it.
    data class VerifiedRequest internal constructor(
        val requestId: String,
        val idempotencyKey: String,
        val authenticatedDeviceId: String,
        val projectId: String,
        val operation: Operation,
        val payload: Map<String, String>,
        val expiresAtEpochMs: Long
    )

    data class Submission(
        val taskId: String,
        val inserted: Boolean,
        val state: GlafiraTaskState
    )

    fun submit(request: VerifiedRequest): Submission {
        require(request.requestId.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        require(request.idempotencyKey.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        require(request.authenticatedDeviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(request.projectId.isNotBlank() && request.projectId.length <= 128)
        val now = clock()
        require(request.expiresAtEpochMs > now) { "BRIDGE_REQUEST_EXPIRED" }
        require(request.expiresAtEpochMs <= now + MAX_REQUEST_TTL_MS) {
            "BRIDGE_REQUEST_TTL_EXCEEDED"
        }
        validatePayload(request.operation, request.payload)

        val taskId = "bridge-" + UUID.randomUUID().toString()
        val summary = when (request.operation) {
            Operation.PROJECT_READ -> "Read project ${request.projectId}"
            Operation.CONVERSATION_READ ->
                "Read conversation ${request.payload.getValue("conversationId")}"
            Operation.CONVERSATION_APPEND ->
                "Append message to conversation ${request.payload.getValue("conversationId")}"
        }
        val payload = JSONObject().apply {
            put("bridgeRequestId", request.requestId)
            put("deviceId", request.authenticatedDeviceId)
            put("operation", request.operation.name)
            put("actionSummary", summary)
            request.payload.toSortedMap().forEach { (key, value) -> put(key, value) }
        }.toString()

        val task = GlafiraTask(
            taskId = taskId,
            projectId = request.projectId,
            taskType = request.operation.toolId,
            payload = payload,
            origin = "android-desktop-bridge:${request.authenticatedDeviceId}",
            priority = 0,
            createdAtMillis = now,
            updatedAtMillis = now,
            deadlineAtMillis = request.expiresAtEpochMs,
            idempotencyKey = request.idempotencyKey,
            requiredScopes = setOf(request.operation.requiredScope)
        )
        val result = runtime.enqueue(task)
        return Submission(result.task.taskId, result.inserted, result.task.state)
    }

    fun claimDesktopTask(
        authenticatedDeviceId: String,
        projectId: String,
        capabilities: Set<String>,
        maxParallelTasks: Int
    ): GlafiraTask? {
        runtime.recordDesktopNode(authenticatedDeviceId, capabilities, maxParallelTasks)
        return runtime.claimDesktopTask(authenticatedDeviceId, projectId, capabilities, maxParallelTasks)
    }

    /** Core-owned Dry Run path: persists a redacted preview, but never claims a lease or exposes executable payload. */
    fun previewDesktopTask(deviceId: String, projectId: String, capabilities: Set<String>): GlafiraDryRunPlan? =
        runtime.previewDesktopTask(deviceId, projectId, capabilities)

    fun desktopTaskLeaseExpiry(taskId: String): Long? = runtime.desktopTaskLeaseExpiry(taskId)

    fun updateDesktopTask(
        taskId: String,
        projectId: String,
        authenticatedDeviceId: String,
        state: String,
        progressPercent: Int,
        progressMessage: String?,
        result: String?,
        errorCode: String?
    ): Boolean = runtime.updateDesktopTask(
        taskId, projectId, authenticatedDeviceId, state, progressPercent,
        progressMessage, result, errorCode
    )

    /** Only authenticated callers holding the task's read scopes may query this API. */
    fun status(taskId: String, projectId: String, deviceId: String): GlafiraTask? =
        runtime.taskStore.getTask(taskId)?.takeIf {
            it.projectId == projectId && (it.origin == "android-desktop-bridge:$deviceId" ||
                it.taskType == GlafiraTaskStore.DESKTOP_TASK_TYPE)
        }

    fun hasActiveReadScopes(deviceId: String, scopes: Set<String>): Boolean =
        runtime.hasActiveReadScopes(deviceId, scopes)

    fun result(taskId: String, projectId: String): GlafiraStoredTaskResult? =
        runtime.resultStore.get(taskId, projectId)

    fun acknowledgeResult(taskId: String, projectId: String, resultId: String, deviceId: String): Boolean =
        status(taskId, projectId, deviceId) != null &&
            runtime.resultStore.acknowledge(taskId, projectId, resultId, deviceId, clock())

    fun history(taskId: String, projectId: String): String =
        runtime.taskStore.readableDatabase.rawQuery("""SELECT from_state, to_state, changed_at, attempt_count
            FROM core_task_history WHERE task_id = ? AND project_id = ? ORDER BY event_id DESC LIMIT 100""".trimIndent(),
            arrayOf(taskId, projectId)).use { cursor ->
            val events = org.json.JSONArray()
            while (cursor.moveToNext()) events.put(JSONObject().apply {
                put("from", if (cursor.isNull(0)) JSONObject.NULL else cursor.getString(0))
                put("to", cursor.getString(1)); put("at", cursor.getLong(2)); put("attempt", cursor.getInt(3))
            })
            events.toString()
        }

    private fun validatePayload(operation: Operation, payload: Map<String, String>) {
        require(payload.size <= MAX_PAYLOAD_FIELDS)
        require(payload.keys.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")) })
        require(payload.values.all { it.length <= MAX_FIELD_CHARS })
        when (operation) {
            Operation.PROJECT_READ -> require(payload.isEmpty())
            Operation.CONVERSATION_READ -> {
                require(payload.keys == setOf("conversationId"))
                require(payload.getValue("conversationId").isNotBlank())
            }
            Operation.CONVERSATION_APPEND -> {
                require(payload.keys == setOf("conversationId", "messageText"))
                require(payload.getValue("conversationId").isNotBlank())
                require(payload.getValue("messageText").isNotBlank())
            }
        }
    }

    companion object {
        private const val MAX_REQUEST_TTL_MS = 15L * 60L * 1000L
        private const val MAX_PAYLOAD_FIELDS = 8
        private const val MAX_FIELD_CHARS = 16_384
    }
}
