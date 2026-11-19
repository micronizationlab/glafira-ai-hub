package com.micronizationlab.glafira

import android.content.Context

/** Wires Core routing, the durable Android/Desktop inbox, local fallback and owner notifications. */
internal object GlafiraDistributedTaskRuntimeFactory {
    fun create(
        context: Context,
        localTaskPort: GlafiraLocalTaskPort,
        desktopCapabilities: () -> GlafiraNodeCapabilities?,
        safeAndroidCapabilities: Set<String>
    ): GlafiraDistributedTaskOrchestrator {
        val appContext = context.applicationContext
        val coreRuntime = GlafiraCoreRuntimeOwner.get(appContext)
        val notifications = GlafiraTaskNotificationPublisher(appContext)
        return GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraCoreDesktopTaskPort(coreRuntime),
            android = localTaskPort,
            desktopCapabilities = { desktopCapabilities() ?: coreRuntime.desktopNodeCapabilities() },
            safeAndroidCapabilities = safeAndroidCapabilities,
            onTaskResult = notifications::publish
        )
    }
}
