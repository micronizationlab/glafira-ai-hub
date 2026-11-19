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

    init {
        // Recover only at runtime construction, before this worker can own any active tasks.
        queue.recoverInterruptedTasks()
    }

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
            try {
                // Cancellation may win between claim and dispatch. Never execute a task
                // unless both durable state transitions succeeded.
                val accepted = queue.transition(task.taskId, DesktopTaskState.ACCEPTED)
                if (accepted.state != DesktopTaskState.ACCEPTED) {
                    slots.release()
                    continue
                }
                val running = queue.transition(task.taskId, DesktopTaskState.RUNNING)
                if (running.state != DesktopTaskState.RUNNING) {
                    slots.release()
                    continue
                }
            } catch (_: Exception) {
                slots.release()
                continue
            }
            val future = CompletableFuture<DesktopAgentResult>()
            active[task.taskId] = future
            try {
                workers.execute {
                    try {
                        val result = try {
                            onProgress(task.taskId, 1, "started")
                            val output = handler.execute(task)
                            require(output.isNotBlank() && output.length <= MAX_RESULT_CHARS)
                            val complete = queue.complete(task.taskId, output)
                            DesktopAgentResult(task.taskId, complete.state, result = output)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            val failed = runCatching { queue.fail(task.taskId, "WORKER_INTERRUPTED") }.getOrNull()
                            DesktopAgentResult(task.taskId, failed?.state ?: DesktopTaskState.FAILED, errorCode = "WORKER_INTERRUPTED")
                        } catch (error: Exception) {
                            val code = error.javaClass.simpleName.uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(48)
                                .ifBlank { "AGENT_FAILED" }
                            val failed = runCatching { queue.fail(task.taskId, code) }.getOrNull()
                            DesktopAgentResult(task.taskId, failed?.state ?: DesktopTaskState.FAILED, errorCode = code)
                        }
                        future.complete(result)
                        runCatching { onResult(result) }
                    } catch (fatal: Throwable) {
                        future.completeExceptionally(fatal)
                        throw fatal
                    } finally {
                        active.remove(task.taskId)
                        slots.release()
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                active.remove(task.taskId)
                runCatching { queue.fail(task.taskId, "WORKER_REJECTED") }
                slots.release()
                throw error
            }
            started++
        }
        return started
    }

    fun activeTaskIds(): Set<String> = active.keys.toSet()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Let already-claimed work finish and persist its result before interrupting workers.
        workers.shutdown()
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow()
                workers.awaitTermination(5, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            workers.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val DEFAULT_PARALLELISM = 2
        const val MAX_PARALLELISM = 8
        private const val MAX_RESULT_CHARS = 12_000
    }
}
