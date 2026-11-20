package com.micronizationlab.glafira

import android.content.Context
import androidx.work.WorkManager
import java.util.UUID

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
    // Pairing approval writes device credentials into this same encrypted approval DB.
    // Reading from the legacy SharedPreferences store would make newly paired devices
    // invisible to Core authorization even though HTTPS admission accepts them.
    private val bridgeCredentialStore = AndroidKeystoreBridgeCredentialStore(appContext)
    private val taskGrantSource = GlafiraCompositeTaskGrantSource(
        ownerApprovalStore,
        bridgeCredentialStore
    )

    private val bridgeDispatcher = GlafiraBridgeOperationDispatcher(appContext)
    // Live mode remains unavailable until a separate owner-approved run API is implemented.
    private val executionMode = GlafiraExecutionMode.DRY_RUN
    internal val executionLimiter = GlafiraCoreExecutionLimiter()

    private val executor = GlafiraCoreTaskExecutor(
        queue = taskStore,
        authorizer = RegistryBackedGlafiraTaskAuthorizer(taskGrantSource),
        handlers = listOf(
            "project.read" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            "conversation.read" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            "conversation.append" to GlafiraTaskHandler { task -> executeBridgeTask(task) }
        ),
        runtimeBudget = { loadRuntimeBudget() },
        executionMode = { executionMode },
        dryRunPreviewPersistence = taskStore,
        executionLimiter = executionLimiter
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

    /** Current owner-controlled, persisted Core execution limits. */
    fun runtimeBudget(): GlafiraRuntimeBudget = loadRuntimeBudget()

    /** Persist a validated budget. Parallel task execution is bounded; multi-step handlers remain disabled. */
    fun setRuntimeBudget(budget: GlafiraRuntimeBudget) {
        val editor = preferences.edit()
            .putLong(KEY_BUDGET_WALL_CLOCK_MS, budget.maxWallClockMillis)
            .putInt(KEY_BUDGET_MAX_STEPS, budget.maxSteps)
            .putInt(KEY_BUDGET_MAX_RETRIES, budget.maxRetries)
            .putInt(KEY_BUDGET_MAX_CONCURRENT, budget.maxConcurrentTasks)
        if (budget.maxCostMicros == null) editor.remove(KEY_BUDGET_MAX_COST_MICROS)
        else editor.putLong(KEY_BUDGET_MAX_COST_MICROS, budget.maxCostMicros)
        check(editor.commit()) { "Could not persist GLAFIRA runtime budget." }
    }

    private fun loadRuntimeBudget(): GlafiraRuntimeBudget = GlafiraRuntimeBudget(
        maxWallClockMillis = preferences.getLong(KEY_BUDGET_WALL_CLOCK_MS, 60_000L),
        maxSteps = preferences.getInt(KEY_BUDGET_MAX_STEPS, 1),
        maxRetries = preferences.getInt(KEY_BUDGET_MAX_RETRIES, 5),
        maxConcurrentTasks = preferences.getInt(KEY_BUDGET_MAX_CONCURRENT, GlafiraRuntimeBudget().maxConcurrentTasks),
        maxCostMicros = if (preferences.contains(KEY_BUDGET_MAX_COST_MICROS))
            preferences.getLong(KEY_BUDGET_MAX_COST_MICROS, 0L) else null
    )

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

    /** Atomically stores a task and its dependency graph before any worker is woken. */
    fun enqueue(task: GlafiraTask, dependencies: Set<String>): GlafiraTaskEnqueueResult {
        val result = taskStore.enqueueWithDependencies(task, dependencies)
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
        // Desktop workers cannot bypass the Core Dry Run gate. Preview plans are delivered
        // through previewDesktopTask; this claim API never issues a lease in DRY_RUN.
        if (executionMode == GlafiraExecutionMode.DRY_RUN) return null
        val now = System.currentTimeMillis()
        for (candidate in taskStore.queuedDesktopTasks()) {
            val configuredConcurrency = loadRuntimeBudget().maxConcurrentTasks
            val leased = taskStore.claimDesktopTask(
                candidate.taskId, deviceId, capabilities,
                minOf(maxParallelTasks, configuredConcurrency), now
            ) ?: continue
            val budget = loadRuntimeBudget()
            val budgetDecision = GlafiraRuntimeBudgetPolicy.evaluate(
                task = leased,
                nowMillis = now,
                budgetStartedAtMillis = leased.createdAtMillis,
                budget = budget.copy(maxRetries = minOf(budget.maxRetries, 2)),
                measuredCostMicros = null
            )
            if (!budgetDecision.allowed) {
                taskStore.transition(
                    leased.taskId, setOf(GlafiraTaskState.RUNNING),
                    GlafiraTaskState.FAILED, now,
                    lastError = budgetDecision.reason ?: "RUNTIME_BUDGET_REJECTED"
                )
                taskStore.releaseDesktopLease(leased.taskId, deviceId)
                continue
            }
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

    /**
     * Builds and persists one redacted Desktop Dry Run preview without claiming or leasing
     * the task. Only the plan is returned over Bridge; executable payload values stay in Core.
     */
    fun previewDesktopTask(capabilities: Set<String>): GlafiraDryRunPlan? {
        if (executionMode != GlafiraExecutionMode.DRY_RUN) return null
        require(capabilities.size <= 16)
        val authorizer = RegistryBackedGlafiraTaskAuthorizer(taskGrantSource)
        val now = System.currentTimeMillis()
        val candidates = taskStore.listTasks(setOf(GlafiraTaskState.QUEUED), 100)
        for (candidate in candidates) {
            if (candidate.taskType != GlafiraTaskStore.DESKTOP_TASK_TYPE) continue
            val required = runCatching {
                val values = org.json.JSONObject(candidate.payload).getJSONArray("requiredCapabilities")
                buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
            }.getOrNull() ?: continue
            if (!capabilities.containsAll(required)) continue
            val authorization = authorizer.inspectForDryRun(candidate, now)
            val plan = GlafiraDryRunPlanner.build(candidate, authorization)
            val persisted = taskStore.persistDryRunPreview(
                candidate.taskId, setOf(GlafiraTaskState.QUEUED), plan, now
            )
            if (persisted) return plan
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

    /** Project-scoped lookup for a successfully persisted Core handler result. */
    fun coreTaskResult(taskId: String, projectId: String): GlafiraCoreTaskResultRecord? =
        taskStore.coreTaskResult(taskId, projectId)

    /** Tasks with typed, durable Dry Run previews awaiting owner review. */
    fun dryRunPreviews(limit: Int = 100): List<GlafiraTask> =
        taskStore.dryRunPreviews(limit).mapNotNull { taskStore.getTask(it.taskId) }

    /** Returns the Core task only while its typed preview row still exists. */
    fun dryRunPreview(taskId: String): GlafiraTask? =
        taskStore.getTask(taskId)?.takeIf {
            it.state == GlafiraTaskState.WAITING_FOR_OWNER &&
                taskStore.dryRunPreview(taskId) != null
        }

    /** Typed, encrypted Dry Run previews for the owner review screen. */
    fun dryRunPreviewRecords(limit: Int = 100): List<GlafiraDryRunPreviewRecord> =
        taskStore.dryRunPreviews(limit)

    fun dryRunPreviewJson(taskId: String): String? =
        taskStore.dryRunPreview(taskId)?.planJson

    /** Acknowledge/reject a preview after fresh owner authentication; never starts LIVE execution. */
    fun reviewDryRunPreview(
        taskId: String,
        expectedPlanHash: String,
        decision: GlafiraDryRunReviewStatus,
        ownerSession: GlafiraCoreOwnerSession
    ): Boolean {
        if (!ownerSession.consume()) return false
        return taskStore.reviewDryRunPreview(taskId, expectedPlanHash, decision)
    }

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
        private const val DRY_RUN_PREVIEW_PREFIX = "DRY_RUN_PREVIEW:"
        private const val PREFERENCES = "glafira_core_runtime"
        private const val KEY_ENABLED = "runtime_enabled"
        private const val KEY_BUDGET_WALL_CLOCK_MS = "runtime_budget_wall_clock_ms"
        private const val KEY_BUDGET_MAX_STEPS = "runtime_budget_max_steps"
        private const val KEY_BUDGET_MAX_RETRIES = "runtime_budget_max_retries"
        private const val KEY_BUDGET_MAX_CONCURRENT = "runtime_budget_max_concurrent"
        private const val KEY_BUDGET_MAX_COST_MICROS = "runtime_budget_max_cost_micros"

        @Volatile
        private var instance: GlafiraCoreRuntimeOwner? = null

        fun initialize(context: Context): GlafiraCoreRuntimeOwner =
            instance ?: synchronized(this) {
                instance ?: GlafiraCoreRuntimeOwner(context).also { instance = it }
            }

        fun get(context: Context): GlafiraCoreRuntimeOwner = initialize(context)
    }
}
