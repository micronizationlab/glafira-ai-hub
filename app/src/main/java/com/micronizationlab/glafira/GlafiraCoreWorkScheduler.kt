package com.micronizationlab.glafira

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Unique WorkManager entry point for deferred Core queue processing. */
object GlafiraCoreWorkScheduler {
    private const val UNIQUE_WORK = "glafira-core-queue-drain"
    private const val NETWORK_WAKE = "glafira-core-network-wake"
    private const val RETRY_WAKE = "glafira-core-retry-wake"
    internal const val RESUME_NETWORK = "resume_network"

    fun scheduleImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<GlafiraCoreDrainWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun scheduleWhenOnline(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<GlafiraCoreWakeWorker>()
            .setInputData(workDataOf(RESUME_NETWORK to true))
            // Avoid a tight wake/drain loop if the link is up but the endpoint is down.
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(NETWORK_WAKE, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleRetry(context: Context, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<GlafiraCoreWakeWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(RETRY_WAKE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(UNIQUE_WORK)
            cancelUniqueWork(NETWORK_WAKE)
            cancelUniqueWork(RETRY_WAKE)
        }
    }
}
