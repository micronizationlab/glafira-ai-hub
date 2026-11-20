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
    const val RESUME_NETWORK = "resume_network"
    private const val UNIQUE_WORK = "glafira-core-queue-drain"
    private const val UNIQUE_RETRY_WORK = "glafira-core-queue-retry"
    private const val UNIQUE_NETWORK_WORK = "glafira-core-queue-network"

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
        val request = OneTimeWorkRequestBuilder<GlafiraCoreDrainWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(RESUME_NETWORK to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NETWORK_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleRetry(context: Context, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<GlafiraCoreDrainWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_RETRY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(UNIQUE_WORK)
            cancelUniqueWork(UNIQUE_RETRY_WORK)
            cancelUniqueWork(UNIQUE_NETWORK_WORK)
        }
    }
}
