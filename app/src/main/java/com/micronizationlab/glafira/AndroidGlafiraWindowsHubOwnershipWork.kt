package com.micronizationlab.glafira

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class GlafiraWindowsHubOwnershipScheduleResult {
    ENQUEUED,
    BLOCKED_UNPAIRED,
    BLOCKED_EXPIRED,
    BLOCKED_REVOKED,
    BLOCKED_INVALID
}

/** Pure admission boundary used before any durable ownership-sync work is created. */
internal class GlafiraWindowsHubOwnershipWorkAdmission(
    private val pairingState: GlafiraWindowsHubPairingStateProvider,
    private val enqueue: (projectId: String, transferId: String) -> Unit
) {
    fun schedule(
        projectId: String,
        transferId: String,
        nowEpochMs: Long
    ): GlafiraWindowsHubOwnershipScheduleResult {
        require(validProjectIdentifier(projectId) && validIdentifier(transferId) && nowEpochMs > 0) {
            "WINDOWS_HUB_OWNERSHIP_WORK_INVALID"
        }
        val status = runCatching { pairingState.state(nowEpochMs).status }
            .getOrDefault(GlafiraWindowsHubPairingStatus.INVALID)
        return when (status) {
            GlafiraWindowsHubPairingStatus.PAIRED -> {
                enqueue(projectId, transferId)
                GlafiraWindowsHubOwnershipScheduleResult.ENQUEUED
            }
            GlafiraWindowsHubPairingStatus.UNPAIRED ->
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_UNPAIRED
            GlafiraWindowsHubPairingStatus.EXPIRED ->
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_EXPIRED
            GlafiraWindowsHubPairingStatus.REVOKED ->
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_REVOKED
            GlafiraWindowsHubPairingStatus.INVALID ->
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_INVALID
        }
    }

    private fun validIdentifier(value: String): Boolean = value.length in 8..128 &&
        value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-_." }

    private fun validProjectIdentifier(value: String): Boolean =
        value == "default" || validIdentifier(value)
}

/** Durable scheduler only for read-side Windows Hub ownership synchronization. */
object AndroidGlafiraWindowsHubOwnershipScheduler {
    fun schedule(
        context: Context,
        projectId: String,
        transferId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraWindowsHubOwnershipScheduleResult {
        val application = context.applicationContext
        val store = runCatching { AndroidGlafiraWindowsHubCredentialStore(application) }
            .getOrElse { return GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_INVALID }
        return GlafiraWindowsHubOwnershipWorkAdmission(store) { project, transfer ->
            val request = OneTimeWorkRequestBuilder<AndroidGlafiraWindowsHubOwnershipWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(PROJECT_ID to project, TRANSFER_ID to transfer))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(application).enqueueUniqueWork(
                uniqueWorkName(project, transfer),
                ExistingWorkPolicy.KEEP,
                request
            )
        }.schedule(projectId, transferId, nowEpochMs)
    }

    internal fun uniqueWorkName(projectId: String, transferId: String): String {
        val canonical = "${projectId.length}:$projectId${transferId.length}:$transferId"
            .toByteArray(Charsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(canonical)
        } finally {
            canonical.fill(0)
        }
        return try {
            "glafira-windows-hub-ownership-" + digest.joinToString("") { "%02x".format(it) }
        } finally {
            digest.fill(0)
        }
    }

    internal const val PROJECT_ID = "project_id"
    internal const val TRANSFER_ID = "transfer_id"
}

/** Re-checks pairing immediately before network so queued work cannot outlive a credential. */
class AndroidGlafiraWindowsHubOwnershipWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getString(AndroidGlafiraWindowsHubOwnershipScheduler.PROJECT_ID)
            ?: return Result.failure()
        val transferId = inputData.getString(AndroidGlafiraWindowsHubOwnershipScheduler.TRANSFER_ID)
            ?: return Result.failure()
        val store = runCatching { AndroidGlafiraWindowsHubCredentialStore(applicationContext) }
            .getOrElse { return Result.failure() }
        if (store.state(System.currentTimeMillis()).status != GlafiraWindowsHubPairingStatus.PAIRED) {
            return Result.success()
        }
        val consumer = GlafiraMobileOwnershipSyncAdapter(
            AndroidGlafiraMobileOwnershipProjectionStore(applicationContext),
            store
        )
        return try {
            AndroidGlafiraWindowsHubOwnershipClient(
                credentials = store,
                exchange = AndroidGlafiraPinnedHttpsBridgeExchange(),
                consumer = consumer
            ).read(projectId, transferId)
            Result.success()
        } catch (_: GlafiraWindowsHubCredentialUnavailableException) {
            Result.success()
        } catch (_: IOException) {
            if (runAttemptCount < MAX_NETWORK_ATTEMPTS - 1) Result.retry() else Result.failure()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        private const val MAX_NETWORK_ATTEMPTS = 3
    }
}
