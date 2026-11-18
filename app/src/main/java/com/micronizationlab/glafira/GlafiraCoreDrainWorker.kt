package com.micronizationlab.glafira

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Deferred worker that calls the exact same application-scoped executor as interactive entry
 * points. It never dispatches tools directly and never bypasses Core authorization.
 */
class GlafiraCoreDrainWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runtime = GlafiraCoreRuntimeOwner.get(applicationContext)
        if (!runtime.isEnabled) return Result.success()

        return try {
            repeat(MAX_TASKS_PER_RUN) {
                if (isStopped) return Result.success()
                when (runtime.executeNext().status) {
                    GlafiraTaskExecutionStatus.IDLE -> return Result.success()
                    GlafiraTaskExecutionStatus.SUCCEEDED -> Unit
                    GlafiraTaskExecutionStatus.RETRY_SCHEDULED -> {
                        GlafiraCoreWorkScheduler.scheduleRetry(applicationContext, RETRY_DELAY_MILLIS)
                        return Result.success()
                    }
                    GlafiraTaskExecutionStatus.WAITING_FOR_NETWORK -> {
                        GlafiraCoreWorkScheduler.scheduleWhenOnline(applicationContext)
                        return Result.success()
                    }
                    GlafiraTaskExecutionStatus.WAITING_FOR_OWNER,
                    GlafiraTaskExecutionStatus.FAILED -> return Result.success()
                }
            }
            GlafiraCoreWorkScheduler.scheduleImmediate(applicationContext)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_WORKER_ATTEMPTS - 1) Result.failure()
            else Result.retry()
        }
    }

    companion object {
        private const val MAX_TASKS_PER_RUN = 8
        private const val MAX_WORKER_ATTEMPTS = 5
        private const val RETRY_DELAY_MILLIS = 30_000L
    }
}
