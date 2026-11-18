package com.micronizationlab.glafira.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class DesktopAgentRole { GLAFIRA, ANALYST, PROGRAMMER, RESEARCHER }

internal data class DesktopAgentResult(
    val taskId: String,
    val state: DesktopTaskState,
    val result: String? = null,
    val errorCode: String? = null
)

/** Pluggable execution boundary. Implementations may call a local model or an approved provider. */
internal fun interface DesktopAgentExecutor {
    fun execute(task: DesktopTaskEnvelope): String
}

/**
 * Bounded parallel worker runtime. No arbitrary shell execution is provided.
 * A task is executed only when a trusted handler is registered for its taskType.
 */
internal class DesktopTaskRuntime(
    private val queue: DesktopTaskQueue,
    private val handlers: Map<String, DesktopAgentExecutor>,
    maxParallelTasks: Int = DEFAULT_PARALLELISM,
    private val onResult: (DesktopAgentResult) -> Unit = {},
    private val onProgress: (String, Int, String) -> Unit = { _, _, _ -> }
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val slots = Semaphore(maxParallelTasks.also { require(it in 1..MAX_PARALLELISM) })
    private val workers: ExecutorService = Executors.newFixedThreadPool(maxParallelTasks)
    private val active = java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<DesktopAgentResult>>()

    /** Claims and starts up to the available worker capacity; safe to call repeatedly. */
    fun dispatchAvailable(): Int {
        check(!closed.get()) { "Desktop task runtime is closed." }
        var started = 0
        while (slots.tryAcquire()) {
            val record = try {
                queue.claimNext()
            } catch (error: Exception) {
                slots.release()
                throw error
            }
            if (record == null) {
                slots.release()
                break
            }
            val task = record.envelope
            val handler = handlers[task.taskType]
            if (handler == null) {
                val failed = runCatching { queue.fail(task.taskId, "NO_AGENT_HANDLER") }.getOrNull()
                onResult(DesktopAgentResult(task.taskId, failed?.state ?: DesktopTaskState.FAILED, errorCode = "NO_AGENT_HANDLER"))
                slots.release()
                continue
            }
            queue.transition(task.taskId, DesktopTaskState.ACCEPTED)
            queue.transition(task.taskId, DesktopTaskState.RUNNING)
            val future = CompletableFuture.supplyAsync({
                try {
                    onProgress(task.taskId, 1, "started")
                    val output = handler.execute(task)
                    require(output.isNotBlank() && output.length <= MAX_RESULT_CHARS)
                    val complete = queue.complete(task.taskId, output)
                    DesktopAgentResult(task.taskId, complete.state, result = output)
                } catch (error: Exception) {
                    val code = error.javaClass.simpleName.uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(48)
                        .ifBlank { "AGENT_FAILED" }
                    val failed = runCatching { queue.fail(task.taskId, code) }.getOrNull()
                    DesktopAgentResult(task.taskId, failed?.state ?: DesktopTaskState.FAILED, errorCode = code)
                }
            }, workers).whenComplete { result, error ->
                val finalResult = result ?: DesktopAgentResult(
                    task.taskId, DesktopTaskState.FAILED,
                    errorCode = error?.javaClass?.simpleName ?: "AGENT_FAILED"
                )
                active.remove(task.taskId)
                runCatching { onResult(finalResult) }
                slots.release()
            }
            active[task.taskId] = future
            started++
        }
        return started
    }

    fun activeTaskIds(): Set<String> = active.keys.toSet()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        workers.shutdownNow()
        workers.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        const val DEFAULT_PARALLELISM = 2
        const val MAX_PARALLELISM = 8
        private const val MAX_RESULT_CHARS = 24_000
    }
}
