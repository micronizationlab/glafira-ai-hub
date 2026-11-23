package com.micronizationlab.glafira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-safe dynamic concurrency limiter. A new permit uses the current configured limit;
 * lowering the limit never interrupts already-running work and simply stops admitting new work.
 */
class GlafiraCoreExecutionLimiter internal constructor() {
    private val lock = Mutex()
    private var active = 0
    private var changed = CompletableDeferred<Unit>()

    internal suspend fun <T> withPermit(limit: Int, block: suspend () -> T): T {
        require(limit in 1..GlafiraRuntimeBudget.MAX_CONCURRENT_TASKS) {
            "Core concurrency limit is outside the supported range."
        }
        // A provider call made by a Core handler already owns this limiter's slot.
        // Re-acquiring at limit=1 would deadlock; another limiter must not inherit it.
        if (currentCoroutineContext()[PermitContext]?.owner === this) return block()

        while (true) {
            val waitForChange = lock.withLock {
                if (active < limit) {
                    active += 1
                    null
                } else {
                    changed
                }
            }
            if (waitForChange == null) break
            waitForChange.await()
        }

        try {
            return withContext(PermitContext(this)) { block() }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    check(active > 0) { "Core execution permit underflow." }
                    active -= 1
                    val previous = changed
                    changed = CompletableDeferred()
                    previous.complete(Unit)
                }
            }
        }
    }

    private class PermitContext(
        val owner: GlafiraCoreExecutionLimiter
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PermitContext>
    }
}
