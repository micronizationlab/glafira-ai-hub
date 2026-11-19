package com.micronizationlab.glafira

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal fun interface GlafiraTaskHandler {
    suspend fun execute(task: GlafiraSubtask, dependencyResults: Map<String, String>): String
}

internal enum class GlafiraTaskStatus { SUCCEEDED, FAILED, SKIPPED }

internal data class GlafiraTaskExecutionResult(
    val taskId: String,
    val status: GlafiraTaskStatus,
    val output: String? = null,
    val error: String? = null
)

internal data class GlafiraTaskExecutionReport(
    val goal: String,
    val results: List<GlafiraTaskExecutionResult>
) {
    val succeeded: Boolean
        get() = results.isNotEmpty() && results.all { it.status == GlafiraTaskStatus.SUCCEEDED }
}

/** Executes validated plans through explicitly registered handlers; missing handlers fail closed. */
internal object GlafiraTaskDispatcher {
    suspend fun execute(
        plan: GlafiraTaskPlan,
        handlers: Map<GlafiraTaskExecutor, GlafiraTaskHandler>
    ): GlafiraTaskExecutionReport {
        val completedOutputs = linkedMapOf<String, String>()
        val results = linkedMapOf<String, GlafiraTaskExecutionResult>()

        for (batch in GlafiraTaskPlanner.readyBatches(plan)) {
            val batchResults = coroutineScope {
                batch.map { task ->
                    async {
                        val handler = handlers[task.executor]
                        if (handler == null) {
                            task.id to GlafiraTaskExecutionResult(
                                task.id, GlafiraTaskStatus.FAILED,
                                error = "Исполнитель ${task.executor} не зарегистрирован."
                            )
                        } else {
                            val dependencies = task.dependsOn.associateWith { completedOutputs.getValue(it) }
                            try {
                                val output = handler.execute(task, dependencies)
                                if (output.isBlank()) {
                                    task.id to GlafiraTaskExecutionResult(
                                        task.id, GlafiraTaskStatus.FAILED,
                                        error = "Исполнитель вернул пустой результат."
                                    )
                                } else {
                                    task.id to GlafiraTaskExecutionResult(
                                        task.id, GlafiraTaskStatus.SUCCEEDED,
                                        output = output.take(MAX_OUTPUT_CHARS)
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                task.id to GlafiraTaskExecutionResult(
                                    task.id, GlafiraTaskStatus.FAILED,
                                    error = (error.message ?: "Ошибка исполнения").take(MAX_ERROR_CHARS)
                                )
                            }
                        }
                    }
                }.map { it.await() }
            }

            batchResults.forEach { (taskId, result) ->
                results[taskId] = result
                if (result.status == GlafiraTaskStatus.SUCCEEDED) {
                    completedOutputs[taskId] = result.output.orEmpty()
                }
            }

            if (batchResults.any { it.second.status == GlafiraTaskStatus.FAILED }) {
                plan.tasks.filterNot { it.id in results }.forEach { task ->
                    results[task.id] = GlafiraTaskExecutionResult(
                        task.id, GlafiraTaskStatus.SKIPPED,
                        error = "Пропущено: предыдущий этап завершился ошибкой."
                    )
                }
                break
            }
        }

        return GlafiraTaskExecutionReport(
            plan.goal,
            plan.tasks.map { results.getValue(it.id) }
        )
    }

    private const val MAX_OUTPUT_CHARS = 12_000
    private const val MAX_ERROR_CHARS = 500
}
