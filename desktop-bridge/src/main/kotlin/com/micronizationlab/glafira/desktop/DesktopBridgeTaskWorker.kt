package com.micronizationlab.glafira.desktop

import org.json.JSONArray
import org.json.JSONObject

/**
 * Legacy Desktop-side pull worker for the historical Android-hosted Bridge v1 endpoint.
 * The production architecture makes Windows Desktop Hub the authoritative TLS host.
 * This compatibility worker keeps task execution registry-bound and fences every result
 * update with the server-issued lease ID and fencing token.
 */
internal class DesktopBridgeTaskWorker(
    private val origin: String,
    private val credentials: DesktopBridgeCredentials,
    private val projectId: String,
    private val queue: DesktopTaskQueue = DesktopTaskQueue.inMemory(),
    handlers: Map<String, DesktopAgentExecutor>,
    private val client: DesktopBridgeHttpsClient = DesktopBridgeHttpsClient(),
    private val maxParallelTasks: Int = DesktopTaskRuntime.DEFAULT_PARALLELISM,
    onResult: (DesktopAgentResult) -> Unit = {},
    private val onDryRunPreview: (DesktopDryRunPreview) -> Unit = {}
) : AutoCloseable {
    init {
        require(credentials.grantedScopes.containsAll(setOf("desktop.task.claim", "desktop.task.result"))) {
            "Desktop task worker requires both owner-granted task Bridge scopes."
        }
    }

    private val advertisedCapabilities = handlers.keys.map(::capabilityForTaskType).toSet()
    private val deliveredDryRunPlanHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val resultOutbox = DesktopResultOutbox(queue, projectId, ::sendTerminal)
    private val runtime = DesktopTaskRuntime(
        queue = queue,
        handlers = handlers,
        maxParallelTasks = maxParallelTasks,
        onProgress = { taskId, percent, message ->
            postTaskUpdate(taskId, "RUNNING", percent, message, "none", "none")
        },
        onResult = { result ->
            runCatching { queue.get(result.taskId)?.let { resultOutbox.deliver(it) } }
            onResult(result)
        }
    )

    /** Polls one owner-approved Bridge lease and starts available parallel work. */
    fun pollOnce(): Int {
        resultOutbox.flush()
        if (!queue.hasAdmissionCapacity()) return 0
        val response = client.post(
            origin, credentials, projectId,
            DesktopBridgeOperation.DESKTOP_TASK_CLAIM,
            mapOf(
                "capabilities" to org.json.JSONArray(advertisedCapabilities.toList()).toString(),
                "maxParallelTasks" to maxParallelTasks.toString()
            )
        )
        if (response.status != DesktopBridgeStatus.ACCEPTED &&
            response.status != DesktopBridgeStatus.SUCCEEDED
        ) {
            throw IllegalStateException(response.errorCode ?: "DESKTOP_TASK_CLAIM_FAILED")
        }
        if (response.result["dryRunPreview"] == "true") {
            // Preview is delivered to the owner-facing callback only. It is never
            // enqueued in the Desktop worker queue and cannot reach an agent handler.
            val preview = DesktopDryRunPreviewCodec.decode(
                response.result["previewJson"]
                    ?: throw IllegalStateException("DESKTOP_DRY_RUN_PREVIEW_MISSING")
            )
            if (deliveredDryRunPlanHashes.add(preview.planHash)) {
                onDryRunPreview(preview)
            }
            return 0
        }
        if (response.result["available"] == "true") {
            val json = JSONObject().apply {
                put("taskId", response.result.getValue("taskId"))
                put("projectId", response.result.getValue("projectId"))
                put("parentTaskId", response.result.getValue("parentTaskId"))
                put("subtaskId", response.result.getValue("subtaskId"))
                put("title", response.result.getValue("title"))
                put("instruction", response.result.getValue("instruction"))
                put("executor", response.result.getValue("executor"))
                put("requiredCapabilities", JSONArray(response.result.getValue("requiredCapabilities")))
                put("createdAtEpochMs", response.result.getValue("createdAtEpochMs").toLong())
                put("expiresAtEpochMs", response.result.getValue("expiresAtEpochMs").toLong())
                put("priority", response.result.getValue("priority").toInt())
                put("leaseId", response.result.getValue("leaseId"))
                put("fencingToken", response.result.getValue("fencingToken").toLong())
            }
            val task = decodeTask(json)
            val existing = queue.get(task.taskId)
            require(task.projectId == projectId) { "DESKTOP_TASK_PROJECT_MISMATCH" }
            when (existing?.state) {
                DesktopTaskState.SUCCEEDED, DesktopTaskState.FAILED,
                DesktopTaskState.CANCELLED, DesktopTaskState.EXPIRED -> {
                    // A new lease cannot authorize replay of an already completed local operation.
                    // Keep the original lease on the durable result until it is reconciled/ACKed.
                    resultOutbox.deliver(existing)
                }
                DesktopTaskState.DISPATCHED, DesktopTaskState.ACCEPTED, DesktopTaskState.RUNNING -> {
                    queue.fail(task.taskId, "RECOVERY_REQUIRES_REVIEW")
                    queue.get(task.taskId)?.let { resultOutbox.deliver(it) }
                }
                DesktopTaskState.QUEUED, null -> {
                    if (existing == null) queue.enqueue(task)
                    else queue.attachLease(task.taskId, task.leaseId!!, task.fencingToken!!)
                    postTaskUpdate(task.taskId, "ACCEPTED", 0, "accepted", "none", "none")
                }
            }
        }
        return runtime.dispatchAvailable()
    }

    fun runPollingLoop(shouldContinue: () -> Boolean, pollIntervalMs: Long = 1_000L) {
        require(pollIntervalMs in 250L..30_000L)
        while (shouldContinue()) {
            try {
                val started = pollOnce()
                if (started == 0) Thread.sleep(pollIntervalMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Exception) {
                Thread.sleep(pollIntervalMs.coerceAtMost(5_000L))
            }
        }
    }

    fun flushPendingResults(): Int = resultOutbox.flush()

    private fun sendTerminal(record: DesktopTaskRecord) {
        postTaskUpdate(record.envelope.taskId,
            if (record.state == DesktopTaskState.SUCCEEDED) "SUCCEEDED" else "FAILED",
            if (record.state == DesktopTaskState.SUCCEEDED) 100 else 0,
            "completed", record.result?.let(JSONObject::quote) ?: "null",
            record.errorCode ?: if (record.state == DesktopTaskState.EXPIRED) "TASK_EXPIRED" else "none",
            resultEncoding = "json")
    }

    private fun postTaskUpdate(
        taskId: String,
        state: String,
        progress: Int,
        progressMessage: String,
        result: String,
        errorCode: String,
        resultEncoding: String? = null
    ) {
        val lease = queue.get(taskId)?.envelope
            ?: throw IllegalStateException("DESKTOP_TASK_LEASE_MISSING")
        val leaseId = lease.leaseId ?: throw IllegalStateException("DESKTOP_TASK_LEASE_ID_MISSING")
        val fencingToken = lease.fencingToken ?: throw IllegalStateException("DESKTOP_TASK_FENCING_TOKEN_MISSING")
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val response = client.post(
                    origin, credentials, projectId,
                    DesktopBridgeOperation.DESKTOP_TASK_RESULT,
                    mapOf(
                        "taskId" to taskId,
                        "leaseId" to leaseId,
                        "fencingToken" to fencingToken.toString(),
                        "state" to state,
                        "progressPercent" to progress.toString(),
                        "progressMessage" to progressMessage,
                        "result" to result,
                        "errorCode" to errorCode
                    ) + (resultEncoding?.let { mapOf("resultEncoding" to it) } ?: emptyMap())
                )
                if (response.status != DesktopBridgeStatus.SUCCEEDED || response.result["accepted"] != "true") {
                    throw IllegalStateException(response.errorCode ?: "DESKTOP_TASK_RESULT_FAILED")
                }
                if (resultEncoding != null) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(result.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                    check(response.result["taskId"] == taskId && response.result["leaseId"] == leaseId &&
                        response.result["fencingToken"] == fencingToken.toString() && response.result["state"] == state &&
                        response.result["resultSha256"] == digest) { "DESKTOP_RESULT_ACK_MISMATCH" }
                }
                return
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) Thread.sleep(250L * (attempt + 1))
            }
        }
        throw IllegalStateException("DESKTOP_TASK_RESULT_DELIVERY_FAILED", lastError)
    }

    private fun capabilityForTaskType(taskType: String): String = when (taskType) {
        "core.orchestration" -> "core.orchestration"
        "analysis.document" -> "document.analysis"
        "code.build" -> "code.build"
        "web.research" -> "web.research"
        else -> throw IllegalArgumentException("UNSUPPORTED_AGENT_TASK_TYPE")
    }

    private fun decodeTask(json: JSONObject): DesktopTaskEnvelope {
        val executor = json.getString("executor")
        val taskType = when (executor) {
            "GLAFIRA" -> "core.orchestration"
            "ANALYST" -> "analysis.document"
            "PROGRAMMER" -> "code.build"
            "RESEARCHER" -> "web.research"
            else -> throw IllegalArgumentException("UNKNOWN_AGENT_ROLE")
        }
        val capabilityArray = json.optJSONArray("requiredCapabilities") ?: JSONArray()
        val capabilities = buildSet {
            for (index in 0 until capabilityArray.length()) {
                when (capabilityArray.getString(index)) {
                    "document.analysis" -> add(DesktopTaskCapability.DOCUMENT_ANALYSIS)
                    "code.build" -> add(DesktopTaskCapability.CODE_BUILD)
                    "web.research" -> add(DesktopTaskCapability.WEB_RESEARCH)
                    "core.orchestration" -> add(DesktopTaskCapability.LOCAL_MODEL)
                    else -> throw IllegalArgumentException("UNSUPPORTED_TASK_CAPABILITY")
                }
            }
        }
        val taskId = json.getString("taskId")
        return DesktopTaskEnvelope(
            taskId = taskId,
            projectId = json.getString("projectId"),
            taskType = taskType,
            instruction = json.getString("instruction"),
            contextRefs = emptyList(),
            requiredCapabilities = capabilities,
            requiredScopes = setOf("project.read"),
            priority = json.optInt("priority", 5),
            createdAt = json.getLong("createdAtEpochMs"),
            expiresAt = json.getLong("expiresAtEpochMs"),
            idempotencyKey = "desktop-task-$taskId",
            leaseId = json.getString("leaseId"),
            fencingToken = json.getLong("fencingToken")
        )
    }

    override fun close() {
        runtime.close()
        credentials.hmacSecret.fill(0)
    }
}
