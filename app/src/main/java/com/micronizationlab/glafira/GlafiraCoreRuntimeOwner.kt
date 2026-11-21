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
    // SharedPreferences is read only for one-time migration. Runtime policy is SQLite-backed.
    private val legacyPreferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val runtimeConfigStore = GlafiraCoreRuntimeConfigStore(appContext)

    init {
        val legacyEnabled = if (legacyPreferences.contains(KEY_ENABLED)) {
            runCatching { legacyPreferences.getBoolean(KEY_ENABLED, false) }.getOrNull()
        } else null
        val legacyBudget = runCatching {
            if (!legacyPreferences.contains(KEY_BUDGET_WALL_CLOCK_MS) &&
                !legacyPreferences.contains(KEY_BUDGET_MAX_STEPS) &&
                !legacyPreferences.contains(KEY_BUDGET_MAX_RETRIES) &&
                !legacyPreferences.contains(KEY_BUDGET_MAX_CONCURRENT) &&
                !legacyPreferences.contains(KEY_BUDGET_MAX_COST_MICROS)
            ) null else GlafiraRuntimeBudget(
                maxWallClockMillis = legacyPreferences.getLong(KEY_BUDGET_WALL_CLOCK_MS, 60_000L),
                maxSteps = legacyPreferences.getInt(KEY_BUDGET_MAX_STEPS, 1),
                maxRetries = legacyPreferences.getInt(KEY_BUDGET_MAX_RETRIES, 5),
                maxConcurrentTasks = legacyPreferences.getInt(
                    KEY_BUDGET_MAX_CONCURRENT, GlafiraRuntimeBudget().maxConcurrentTasks
                ),
                maxCostMicros = if (legacyPreferences.contains(KEY_BUDGET_MAX_COST_MICROS)) {
                    legacyPreferences.getLong(KEY_BUDGET_MAX_COST_MICROS, 0L)
                } else null
            )
        }.getOrNull()
        // A missing legacy enablement flag initializes PAUSED, never implicitly enabled.
        // Remove the legacy copy after the migration attempt, even when SQLite is corrupt:
        // a later database recreation must not resurrect a stale "enabled" preference.
        try {
            runtimeConfigStore.loadOrInitialize(legacyEnabled, legacyBudget)
        } finally {
            check(legacyPreferences.edit()
                .remove(KEY_ENABLED)
                .remove(KEY_BUDGET_WALL_CLOCK_MS)
                .remove(KEY_BUDGET_MAX_STEPS)
                .remove(KEY_BUDGET_MAX_RETRIES)
                .remove(KEY_BUDGET_MAX_CONCURRENT)
                .remove(KEY_BUDGET_MAX_COST_MICROS)
                .commit()) { "Could not retire legacy GLAFIRA runtime preferences." }
        }
    }

    // Stable per-installation identity; this is not an owner grant or task-ownership lease.
    val installationIdentity = AndroidCoreInstallationIdentityStore(appContext).loadOrCreate()

    val taskStore = GlafiraTaskStore(appContext, installationIdentity.coreId)
    val traceStore = GlafiraCoreTraceStore(taskStore)
    private val ownerApprovalStore = GlafiraCoreOwnerApprovalStore(taskStore)
    // Pairing approval writes device credentials into this same encrypted approval DB.
    // Reading from the legacy SharedPreferences store would make newly paired devices
    // invisible to Core authorization even though HTTPS admission accepts them.
    private val bridgeCredentialStore = AndroidKeystoreBridgeCredentialStore(appContext)
    private val taskGrantSource = GlafiraCompositeTaskGrantSource(
        ownerApprovalStore,
        bridgeCredentialStore
    )
    private val liveRunStore = GlafiraCoreLiveRunStore(taskStore)

    private val bridgeDispatcher = GlafiraBridgeOperationDispatcher(appContext)
    // Core evaluates LIVE per task. Only a fresh, durable, owner-approved read grant can pass;
    // every other task falls back to a new Dry Run preview without invoking its handler.
    private val executionMode = GlafiraExecutionMode.LIVE
    private val desktopExecutionMode = GlafiraExecutionMode.DRY_RUN
    internal val executionLimiter = GlafiraCoreExecutionLimiter()

    private val executor = GlafiraCoreTaskExecutor(
        queue = taskStore,
        authorizer = RegistryBackedGlafiraTaskAuthorizer(taskGrantSource),
        handlers = listOf(
            "project.read" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            "conversation.read" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            "conversation.append" to GlafiraTaskHandler { task -> executeBridgeTask(task) },
            // Draft-only content never executes or publishes, even if LIVE mode is enabled later.
            "content.draft" to GlafiraTaskHandler { GlafiraTaskExecutionOutcome.WaitingForOwner }
        ),
        runtimeBudget = { loadRuntimeBudget() },
        executionMode = { executionMode },
        liveTaskAdmission = liveRunStore,
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
                GlafiraTaskExecutionOutcome.Succeeded(org.json.JSONObject(response.result).toString())
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
        get() = runCatching { runtimeConfigStore.read()?.enabled == true }.getOrDefault(false) &&
            !GlafiraCoreBackupCoordinator.hasInterruptedRestore(appContext)

    /** Current owner-controlled limits. Unreadable configuration returns conservative defaults;
     * execution remains paused because isEnabled fails closed. */
    fun runtimeBudget(): GlafiraRuntimeBudget = loadRuntimeBudget()

    /** Persist the complete validated budget and its audit event in one SQLite transaction. */
    fun setRuntimeBudget(budget: GlafiraRuntimeBudget) {
        runtimeConfigStore.setBudget(budget)
    }

    private fun loadRuntimeBudget(): GlafiraRuntimeBudget =
        runCatching { runtimeConfigStore.read()?.budget }.getOrNull() ?: GlafiraRuntimeBudget()

    fun setEnabled(enabled: Boolean) {
        runtimeConfigStore.setEnabled(enabled)
        if (enabled) GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        else GlafiraCoreWorkScheduler.cancel(appContext)
    }

    fun enqueue(task: GlafiraTask): GlafiraTaskEnqueueResult {
        check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(appContext)) { "RESTORE_RECOVERY_REQUIRED" }
        val result = taskStore.enqueue(task)
        if (result.inserted && isEnabled) {
            GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        }
        return result
    }

    /** Atomically stores a task and its dependency graph before any worker is woken. */
    fun enqueue(task: GlafiraTask, dependencies: Set<String>): GlafiraTaskEnqueueResult {
        check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(appContext)) { "RESTORE_RECOVERY_REQUIRED" }
        val result = taskStore.enqueueWithDependencies(task, dependencies)
        if (result.inserted && isEnabled) {
            GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        }
        return result
    }

    /** Decomposes a high-level instruction through a trusted planner, then persists its DAG. */
    suspend fun submitInstruction(
        request: GlafiraCoreTaskPlanRequest,
        decomposer: GlafiraCoreTaskPlanDecomposer
    ): GlafiraCoreTaskPlanEnqueueResult =
        GlafiraCoreTaskPlanOrchestrator(decomposer, ::enqueuePlan).submit(request)

    /** Uses the configured primary AI provider to decompose and persist a durable plan. */
    suspend fun submitInstruction(
        request: GlafiraCoreTaskPlanRequest
    ): GlafiraCoreTaskPlanEnqueueResult =
        submitInstruction(request, GlafiraAiProviderTaskPlanDecomposer(appContext))

    /** Atomically registers an entire bounded DAG before waking the shared dispatcher. */
    fun enqueuePlan(plan: GlafiraCoreTaskPlan): GlafiraCoreTaskPlanEnqueueResult {
        check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(appContext)) { "RESTORE_RECOVERY_REQUIRED" }
        val result = taskStore.enqueuePlan(plan)
        if (result.inserted && isEnabled) {
            GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        }
        return result
    }

    /** Project-scoped orchestration status and durable node journal. */
    fun taskPlan(planId: String, projectId: String): GlafiraCoreTaskPlanRecord? =
        taskStore.taskPlan(planId, projectId)

    /** Rehydrates the existing agent-role plan format from the durable task graph. */
    internal fun plannerPlan(planId: String, projectId: String): GlafiraTaskPlan? =
        taskStore.taskPlan(planId, projectId)?.let(GlafiraCoreTaskPlanAdapter::toPlannerPlan)

    /** Project-scoped plan journal, including current node states and outcomes. */
    fun taskPlans(projectId: String, limit: Int = 100): List<GlafiraCoreTaskPlanRecord> =
        taskStore.taskPlans(projectId, limit)

    /** Full project-scoped plan journal with task histories and persisted results. */
    fun taskPlanJournal(planId: String, projectId: String): GlafiraCoreTaskPlanJournal? =
        taskStore.taskPlanJournal(planId, projectId)

    internal suspend fun executeNext(): GlafiraTaskExecutionResult {
        check(isEnabled) { "GLAFIRA autonomous runtime is paused by owner." }
        return executor.executeNext()
    }


    /** Authenticated Desktop workers claim only after the same Core authorizer approves the task. */
    fun claimDesktopTask(
        deviceId: String,
        capabilities: Set<String>,
        maxParallelTasks: Int
    ): GlafiraDesktopTaskLease? {
        // Desktop workers cannot bypass the Core Dry Run gate. Preview plans are delivered
        // through previewDesktopTask; this claim API never issues a lease in DRY_RUN.
        if (desktopExecutionMode == GlafiraExecutionMode.DRY_RUN) return null
        val now = System.currentTimeMillis()
        for (candidate in taskStore.queuedDesktopTasks()) {
            val configuredConcurrency = loadRuntimeBudget().maxConcurrentTasks
            val claim = taskStore.claimDesktopTask(
                candidate.taskId, deviceId, capabilities,
                minOf(maxParallelTasks, configuredConcurrency), now
            ) ?: continue
            val leased = claim.task
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
                taskStore.releaseDesktopLease(leased.taskId, deviceId, claim.leaseId, claim.fencingToken)
                continue
            }
            val decision = RegistryBackedGlafiraTaskAuthorizer(taskGrantSource)
                .authorize(leased, now)
            when (decision.status) {
                GlafiraTaskAuthorizationStatus.AUTHORIZED -> return claim
                GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER -> {
                    taskStore.releaseDesktopLease(leased.taskId, deviceId, claim.leaseId, claim.fencingToken)
                    if (isEnabled) GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
                }
                GlafiraTaskAuthorizationStatus.DENIED -> {
                    taskStore.transition(
                        leased.taskId, setOf(GlafiraTaskState.RUNNING),
                        GlafiraTaskState.FAILED, now, lastError = decision.reason ?: "DESKTOP_TASK_DENIED"
                    )
                    taskStore.releaseDesktopLease(leased.taskId, deviceId, claim.leaseId, claim.fencingToken)
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
        if (desktopExecutionMode != GlafiraExecutionMode.DRY_RUN) return null
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
        leaseId: String,
        fencingToken: Long,
        state: String,
        progressPercent: Int,
        progressMessage: String?,
        result: String?,
        errorCode: String?
    ): Boolean = taskStore.updateDesktopTask(
        taskId, deviceId, leaseId, fencingToken, state, progressPercent, progressMessage, result, errorCode
    )

    fun recordDesktopNode(deviceId: String, capabilities: Set<String>, maxParallelTasks: Int) =
        taskStore.recordDesktopNode(deviceId, capabilities, maxParallelTasks)

    fun desktopNodeCapabilities(): GlafiraNodeCapabilities? = taskStore.desktopNodeCapabilities()

    /** Current authenticated Desktop worker inventory for capability-aware dispatch. */
    fun desktopNodes(): List<GlafiraNodeCapabilities> = taskStore.desktopNodes()

    /** Advisory placement across all live Desktop workers; claims still revalidate atomically. */
    fun desktopDispatchPlan(limit: Int = 100): List<GlafiraDesktopDispatchAssignment> {
        require(limit in 1..500)
        val now = System.currentTimeMillis()
        return GlafiraDesktopDispatchPlanner.plan(
            tasks = taskStore.queuedDesktopTasks(limit),
            nodes = taskStore.desktopNodes(nowMillis = now),
            activeLeasesByNode = taskStore.activeDesktopLeaseCounts(now),
            nowMillis = now
        )
    }

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

    /**
     * Requeues one acknowledged read-only preview for LIVE execution after a second fresh owner
     * authentication. The exact task and plan remain bound to a short-lived durable grant.
     */
    fun approveLiveReadRun(
        taskId: String,
        expectedPlanHash: String,
        ownerSession: GlafiraCoreOwnerSession
    ): GlafiraLiveRunApprovalMutation {
        val result = liveRunStore.approveReadOnlyRun(taskId, expectedPlanHash, ownerSession)
        if (result == GlafiraLiveRunApprovalMutation.APPROVED && isEnabled) {
            GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        }
        return result
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
        internal fun pauseForRestore(context: Context) {
            GlafiraCoreWorkScheduler.cancel(context)
            val store = GlafiraCoreRuntimeConfigStore(context)
            check(store.loadOrInitialize(null, null) != null) { "Конфигурация Core недоступна." }
            store.setEnabled(false, "RESTORE_PAUSE")
        }

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
