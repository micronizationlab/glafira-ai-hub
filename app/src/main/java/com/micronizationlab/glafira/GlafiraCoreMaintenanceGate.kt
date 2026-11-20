package com.micronizationlab.glafira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Read/write-style gate shared by execution and Core snapshot/restore.
 *
 * Task handlers hold a shared execution lease only while they run. Maintenance waits for
 * every active lease, prevents new leases from entering, and then performs its exclusive work.
 */
object GlafiraCoreMaintenanceGate {
    private val stateLock = Mutex()
    private val maintenanceLock = Mutex()
    private var activeExecutions = 0
    private var maintenancePending = false
    private var maintenanceActive = false
    private var stateChanged = CompletableDeferred<Unit>()

    @Volatile
    var maintenanceEpoch: Long = 0L
        private set

    suspend fun <T> withExecution(block: suspend () -> T): T {
        while (true) {
            val waitForChange = stateLock.withLock {
                if (!maintenancePending && !maintenanceActive) {
                    activeExecutions += 1
                    null
                } else {
                    stateChanged
                }
            }
            if (waitForChange == null) break
            waitForChange.await()
        }

        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock {
                    check(activeExecutions > 0) { "Core execution lease underflow." }
                    activeExecutions -= 1
                    signalStateChangeLocked()
                }
            }
        }
    }

    suspend fun <T> withExclusive(block: suspend () -> T): T = maintenanceLock.withLock {
        var markedPending = false
        var acquired = false
        try {
            stateLock.withLock {
                maintenancePending = true
                markedPending = true
                signalStateChangeLocked()
            }

            while (!acquired) {
                val waitForChange = stateLock.withLock {
                    if (activeExecutions == 0 && !maintenanceActive) {
                        maintenanceActive = true
                        acquired = true
                        null
                    } else {
                        stateChanged
                    }
                }
                waitForChange?.await()
            }

            block()
        } finally {
            if (markedPending) {
                withContext(NonCancellable) {
                    stateLock.withLock {
                        if (acquired) maintenanceActive = false
                        maintenancePending = false
                        maintenanceEpoch += 1L
                        signalStateChangeLocked()
                    }
                }
            }
        }
    }

    /** Called only while stateLock is held; wakes every waiter without losing notifications. */
    private fun signalStateChangeLocked() {
        val previous = stateChanged
        stateChanged = CompletableDeferred()
        previous.complete(Unit)
    }
}
