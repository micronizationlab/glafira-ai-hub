package com.micronizationlab.glafira

enum class GlafiraCoreRuntimeEnablementMutation {
    ENABLED,
    PAUSED,
    OWNER_AUTHENTICATION_REQUIRED,
    RESTORE_RECOVERY_REQUIRED,
    STORAGE_ERROR
}

/**
 * Applies the owner-controlled Core runtime switch without turning a UI toggle into authority.
 *
 * Enabling autonomous queue processing always consumes one fresh, process-local owner proof.
 * Pausing stays available without authentication so a locked or degraded device can fail safe.
 */
internal class GlafiraCoreRuntimeEnablementController(
    private val restoreInterrupted: () -> Boolean,
    private val persistEnabled: (Boolean) -> Unit,
    private val scheduleImmediate: () -> Unit,
    private val cancelScheduledWork: () -> Unit
) {
    fun apply(
        enabled: Boolean,
        consumeOwnerProof: (() -> Boolean)? = null
    ): GlafiraCoreRuntimeEnablementMutation {
        if (!enabled) {
            return try {
                persistEnabled(false)
                runCatching(cancelScheduledWork)
                GlafiraCoreRuntimeEnablementMutation.PAUSED
            } catch (_: Exception) {
                runCatching(cancelScheduledWork)
                GlafiraCoreRuntimeEnablementMutation.STORAGE_ERROR
            }
        }

        if (restoreInterrupted()) {
            return GlafiraCoreRuntimeEnablementMutation.RESTORE_RECOVERY_REQUIRED
        }
        if (consumeOwnerProof?.invoke() != true) {
            return GlafiraCoreRuntimeEnablementMutation.OWNER_AUTHENTICATION_REQUIRED
        }

        return try {
            persistEnabled(true)
            scheduleImmediate()
            GlafiraCoreRuntimeEnablementMutation.ENABLED
        } catch (_: Exception) {
            // Never leave an enabled durable flag behind when scheduling the guarded worker failed.
            runCatching { persistEnabled(false) }
            runCatching(cancelScheduledWork)
            GlafiraCoreRuntimeEnablementMutation.STORAGE_ERROR
        }
    }
}
