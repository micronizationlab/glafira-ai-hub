package com.micronizationlab.glafira

import android.content.Context

/**
 * Application-scoped owner for the persistent Core queue and its single executor.
 *
 * Execution is deliberately fail-closed until the durable owner grant store and reviewed
 * production handler adapters are installed. The current runtime can persist and schedule work,
 * but it cannot perform arbitrary actions or silently authorize model-generated requests.
 */
class GlafiraCoreRuntimeOwner private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val taskStore = GlafiraTaskStore(appContext)
    private val ownerApprovalStore = GlafiraCoreOwnerApprovalStore(taskStore)
    private val bridgeCredentialStore = AndroidKeystoreBridgeCredentialStore(appContext)
    private val taskGrantSource = GlafiraCompositeTaskGrantSource(
        ownerApprovalStore,
        bridgeCredentialStore
    )

    private val bridgeDispatcher = GlafiraBridgeOperationDispatcher(appContext)

    private val executor = GlafiraRuntimeFoundation.createExecutor(
        queue = taskStore,
        authorizer = RegistryBackedGlafiraTaskAuthorizer(taskGrantSource),
        handlers = listOf(
            "project.read" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            "conversation.read" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            "conversation.append" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            // Owner-created queue items are persisted as drafts and remain pending review.
            // This handler deliberately performs no external side effect.
            "content.draft" to GlafiraTaskHandler { GlafiraTaskExecutionOutcome.WaitingForOwner }
        ),
        dryRunPreviewPersistence = taskStore
    )

    private fun executeBridgeTask(task: GlafiraTask): GlafiraTaskExecutionOutcome {
        return try {
            val json = org.json.JSONObject(task.payload)
            val operation = GlafiraBridgeOperation.valueOf(json.getString("operation"))
            val payload = buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in setOf("bridgeRequestId", "deviceId", "operation", "actionSummary")) {
                        put(key, json.getString(key))
                    }
                }
            }
            val request = GlafiraBridgeRequest(
                protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
                requestId = json.getString("bridgeRequestId"),
                idempotencyKey = task.idempotencyKey,
                deviceId = json.getString("deviceId"),
                projectId = task.projectId,
                operation = operation,
                issuedAtEpochMs = task.createdAtMillis,
                expiresAtEpochMs = task.deadlineAtMillis ?: (task.createdAtMillis + 60_000L),
                payload = payload
            )
            val response = bridgeDispatcher.dispatch(request)
            if (response.status == GlafiraBridgeStatus.SUCCEEDED) {
                GlafiraTaskExecutionOutcome.Succeeded("Bridge operation completed.")
            } else {
                GlafiraTaskExecutionOutcome.PermanentFailure(
                    response.errorCode ?: "BRIDGE_OPERATION_FAILED"
                )
            }
        } catch (_: Exception) {
            GlafiraTaskExecutionOutcome.PermanentFailure("BRIDGE_TASK_INVALID")
        }
    }

    val isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist GLAFIRA runtime owner setting."
        }
        if (enabled) GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        else GlafiraCoreWorkScheduler.cancel(appContext)
    }

    fun enqueue(task: GlafiraTask): GlafiraTaskEnqueueResult {
        val result = taskStore.enqueue(task)
        if (result.inserted && isEnabled) {
            GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        }
        return result
    }

    internal suspend fun executeNext(): GlafiraTaskExecutionResult {
        check(isEnabled) { "GLAFIRA autonomous runtime is paused by owner." }
        return executor.executeNext()
    }


    /** Authenticated Desktop workers claim only after the same Core authorizer approves the task. */
    fun claimDesktopTask(
        deviceId: String,
        capabilities: Set<String>,
        maxParallelTasks: Int
    ): GlafiraTask? {
        // Compilation recovery must not enable live execution through a second entry point.
        if (!GlafiraRuntimeFoundation.allowsDesktopTaskClaims || !isEnabled) return null
        val now = System.currentTimeMillis()
        for (candidate in taskStore.queuedDesktopTasks()) {
            val leased = taskStore.claimDesktopTask(
                candidate.taskId, deviceId, capabilities, maxParallelTasks, now
            ) ?: continue
            val decision = RegistryBackedGlafiraTaskAuthorizer(taskGrantSource)
                .authorize(leased, now)
            when (decision.status) {
                GlafiraTaskAuthorizationStatus.AUTHORIZED -> return leased
                GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER -> {
                    taskStore.releaseDesktopLease(leased.taskId, deviceId)
                    if (isEnabled) GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
                }
                GlafiraTaskAuthorizationStatus.DENIED -> {
                    taskStore.transition(
                        leased.taskId, setOf(GlafiraTaskState.RUNNING),
                        GlafiraTaskState.FAILED, now, lastError = decision.reason ?: "DESKTOP_TASK_DENIED"
                    )
                    taskStore.releaseDesktopLease(leased.taskId, deviceId)
                }
            }
        }
        return null
    }

    fun updateDesktopTask(
        taskId: String,
        deviceId: String,
        state: String,
        progressPercent: Int,
        progressMessage: String?,
        result: String?,
        errorCode: String?
    ): Boolean = taskStore.updateDesktopTask(
        taskId, deviceId, state, progressPercent, progressMessage, result, errorCode
    )

    fun recordDesktopNode(deviceId: String, capabilities: Set<String>, maxParallelTasks: Int) =
        taskStore.recordDesktopNode(deviceId, capabilities, maxParallelTasks)

    fun desktopNodeCapabilities(): GlafiraNodeCapabilities? = taskStore.desktopNodeCapabilities()

    fun desktopTaskLeaseExpiry(taskId: String): Long? = taskStore.desktopTaskLeaseExpiry(taskId)

    fun pendingOwnerApprovals(): List<GlafiraPendingActionApproval> =
        ownerApprovalStore.pendingApprovals()

    fun approveOwnerAction(
        taskId: String,
        selectedScopes: Set<String>,
        ownerSession: GlafiraCoreOwnerSession
    ): GlafiraCoreOwnerApprovalMutation {
        val result = ownerApprovalStore.approve(taskId, selectedScopes, ownerSession)
        if (result == GlafiraCoreOwnerApprovalMutation.APPROVED && isEnabled) {
            GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        }
        return result
    }

    fun rejectOwnerAction(
        taskId: String,
        ownerSession: GlafiraCoreOwnerSession
    ): GlafiraCoreOwnerApprovalMutation =
        ownerApprovalStore.reject(taskId, ownerSession)

    companion object {
        private const val PREFERENCES = "glafira_core_runtime"
        private const val KEY_ENABLED = "runtime_enabled"

        @Volatile
        private var instance: GlafiraCoreRuntimeOwner? = null

        fun initialize(context: Context): GlafiraCoreRuntimeOwner =
            instance ?: synchronized(this) {
                instance ?: GlafiraCoreRuntimeOwner(context).also { instance = it }
            }

        fun get(context: Context): GlafiraCoreRuntimeOwner = initialize(context)
    }
}
