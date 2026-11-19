package com.micronizationlab.glafira

/**
 * Safe rollout boundary for the recovered runtime foundation. Enabling scheduling may
 * create previews, but cannot execute a live handler or hand work to a Desktop node.
 * A future LIVE rollout needs separately reviewed handler, pairing and delivery changes.
 */
internal object GlafiraRuntimeFoundation {
    private val executionMode = GlafiraExecutionMode.DRY_RUN

    val allowsDesktopTaskClaims: Boolean
        get() = executionMode == GlafiraExecutionMode.LIVE

    fun createExecutor(
        queue: GlafiraTaskQueue,
        authorizer: GlafiraTaskAuthorizer,
        handlers: Collection<Pair<String, GlafiraTaskHandler>>,
        dryRunPreviewPersistence: GlafiraDryRunPreviewPersistence
    ): GlafiraCoreTaskExecutor = GlafiraCoreTaskExecutor(
        queue = queue,
        authorizer = authorizer,
        handlers = handlers,
        executionMode = { executionMode },
        dryRunPreviewPersistence = dryRunPreviewPersistence
    )
}
