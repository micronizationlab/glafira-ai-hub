package com.micronizationlab.glafira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coroutine-safe dynamic concurrency limiter. A new permit uses the current configured limit;
 * lowering the limit never interrupts already-running work and simply stops admitting new work.
 */
internal class GlafiraCoreExecutionLimiter {
    private val lock = Mutex()
    private var active = 0
    private var changed = CompletableDeferred<Unit>()

    suspend fun <T> withPermit(limit: Int, block: suspend () -> T): T {
        require(limit in 1..GlafiraRuntimeBudget.MAX_CONCURRENT_TASKS) {
            "Core concurrency limit is outside the supported range."
        }

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
            return block()
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
}
