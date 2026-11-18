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
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val taskStore = GlafiraTaskStore(appContext)

    private val executor = GlafiraCoreTaskExecutor(
        queue = taskStore,
        authorizer = GlafiraTaskAuthorizer { _, _ ->
            GlafiraTaskAuthorizationResult(
                GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER,
                "OWNER_POLICY_NOT_CONFIGURED"
            )
        },
        handlers = emptyList()
    )

    val isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist GLAFIRA runtime owner setting."
        }
        if (enabled) GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        else GlafiraCoreWorkScheduler.cancel(appContext)
    }

    fun enqueue(task: GlafiraTask): GlafiraTaskEnqueueResult {
        val result = taskStore.enqueue(task)
        if (result.inserted && isEnabled) {
            GlafiraCoreWorkScheduler.scheduleImmediate(appContext)
        }
        return result
    }

    internal suspend fun executeNext(): GlafiraTaskExecutionResult {
        check(isEnabled) { "GLAFIRA autonomous runtime is paused by owner." }
        return executor.executeNext()
    }

    companion object {
        private const val PREFERENCES = "glafira_core_runtime"
        private const val KEY_ENABLED = "runtime_enabled"

        @Volatile
        private var instance: GlafiraCoreRuntimeOwner? = null

        fun initialize(context: Context): GlafiraCoreRuntimeOwner =
            instance ?: synchronized(this) {
                instance ?: GlafiraCoreRuntimeOwner(context).also { instance = it }
            }

        fun get(context: Context): GlafiraCoreRuntimeOwner = initialize(context)
    }
}
