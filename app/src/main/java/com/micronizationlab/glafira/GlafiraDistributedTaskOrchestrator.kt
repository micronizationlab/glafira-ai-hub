package com.micronizationlab.glafira

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

internal enum class GlafiraExecutionNode { ANDROID, DESKTOP }

internal data class GlafiraNodeCapabilities(
    val nodeId: String,
    val available: Boolean,
    val maxParallelTasks: Int,
    val capabilities: Set<String>
)

internal data class GlafiraDistributedTask(
    val taskId: String,
    val projectId: String,
    val parentTaskId: String,
    val subtaskId: String,
    val title: String,
    val instruction: String,
    val executor: GlafiraTaskExecutor,
    val requiredCapabilities: Set<String>,
    val priority: Int = 5,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

internal data class GlafiraDistributedTaskResult(
    val taskId: String,
    val node: GlafiraExecutionNode,
    val succeeded: Boolean,
    val output: String? = null,
    val errorCode: String? = null
)

/**
 * Transport seam between Core and a paired Desktop node. Implementations must use the
 * authenticated, pinned Bridge; this interface never accepts credentials or raw vault data.
 */
internal fun interface GlafiraDesktopTaskPort {
    suspend fun submit(task: GlafiraDistributedTask): GlafiraDistributedTaskResult
}

internal fun interface GlafiraLocalTaskPort {
    suspend fun execute(task: GlafiraDistributedTask): GlafiraDistributedTaskResult
}

/**
 * Dependency-aware Core dispatcher. Independent tasks are submitted concurrently to Desktop
 * when it is available and advertises the required capabilities; otherwise only explicitly
 * safe Android fallbacks are used.
 */
internal class GlafiraDistributedTaskOrchestrator(
    private val desktop: GlafiraDesktopTaskPort,
    private val android: GlafiraLocalTaskPort,
    private val desktopCapabilities: () -> GlafiraNodeCapabilities?,
    private val safeAndroidCapabilities: Set<String>,
    private val onTaskResult: (GlafiraDistributedTaskResult) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun execute(
        projectId: String,
        plan: GlafiraTaskPlan,
        ttlMs: Long = DEFAULT_TASK_TTL_MS
    ): List<GlafiraDistributedTaskResult> = coroutineScope {
        require(projectId.isNotBlank() && projectId.length <= 128)
        require(ttlMs in MIN_TASK_TTL_MS..MAX_TASK_TTL_MS)
        val parentId = UUID.randomUUID().toString()
        val batches = GlafiraTaskPlanner.readyBatches(plan)
        val results = mutableListOf<GlafiraDistributedTaskResult>()
        for (batch in batches) {
            val batchResults = batch.map { subtask ->
                async {
                    val required = requiredCapabilities(subtask.executor)
                    val task = GlafiraDistributedTask(
                        taskId = UUID.randomUUID().toString(),
                        projectId = projectId,
                        parentTaskId = parentId,
                        subtaskId = subtask.id,
                        title = subtask.title,
                        instruction = subtask.instruction,
                        executor = subtask.executor,
                        requiredCapabilities = required,
                        priority = 5,
                        createdAtEpochMs = clock(),
                        expiresAtEpochMs = clock() + ttlMs
                    )
                    val desktopNode = desktopCapabilities()
                    val result = if (desktopNode != null && desktopNode.available &&
                        desktopNode.maxParallelTasks > 0 &&
                        desktopNode.capabilities.containsAll(required)
                    ) {
                        desktop.submit(task)
                    } else {
                        require(required.all { it in safeAndroidCapabilities }) {
                            "DESKTOP_REQUIRED_UNAVAILABLE"
                        }
                        android.execute(task)
                    }
                    runCatching { onTaskResult(result) }
                    result
                }
            }.awaitAll()
            results += batchResults
            if (batchResults.any { !it.succeeded }) break
        }
        results
    }

    private fun requiredCapabilities(executor: GlafiraTaskExecutor): Set<String> = when (executor) {
        GlafiraTaskExecutor.GLAFIRA -> setOf("core.orchestration")
        GlafiraTaskExecutor.ANALYST -> setOf("document.analysis")
        GlafiraTaskExecutor.PROGRAMMER -> setOf("code.build")
        GlafiraTaskExecutor.RESEARCHER -> setOf("web.research")
    }

    companion object {
        const val DEFAULT_TASK_TTL_MS = 30 * 60 * 1000L
        const val MIN_TASK_TTL_MS = 30_000L
        const val MAX_TASK_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
