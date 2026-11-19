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
            if (drainGlafiraCoreBatch(MAX_TASKS_PER_RUN, { isStopped }, { runtime.executeNext() })) {
                GlafiraCoreWorkScheduler.scheduleImmediate(applicationContext)
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_WORKER_ATTEMPTS - 1) Result.failure()
            else Result.retry()
        } finally {
            if (runtime.isEnabled) {
                runtime.taskStore.nextRetryAtMillis()?.let { retryAt ->
                    GlafiraCoreWorkScheduler.scheduleRetry(applicationContext,
                        (retryAt - System.currentTimeMillis()).coerceAtLeast(1_000L))
                }
                if (runtime.taskStore.hasWaitingForNetworkTasks()) {
                    GlafiraCoreWorkScheduler.scheduleWhenOnline(applicationContext)
                }
            }
        }
    }

    companion object {
        private const val MAX_TASKS_PER_RUN = 8
        private const val MAX_WORKER_ATTEMPTS = 5
    }
}


/** Delayed jobs wake the drain without replacing or cancelling a running executor. */
class GlafiraCoreWakeWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val runtime = GlafiraCoreRuntimeOwner.get(applicationContext)
        if (!runtime.isEnabled) return Result.success()
        return try {
            if (inputData.getBoolean(GlafiraCoreWorkScheduler.RESUME_NETWORK, false)) {
                runtime.taskStore.resumeWaitingForNetwork()
            }
            GlafiraCoreWorkScheduler.scheduleImmediate(applicationContext)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount >= 4) Result.failure() else Result.retry()
        }
    }
}
