package com.micronizationlab.glafira

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes task execution against a consistent Core snapshot or restore.
 * All Core execution entry points must use this same process-wide gate.
 */
object GlafiraCoreMaintenanceGate {
    private val mutex = Mutex()

    suspend fun <T> withExclusive(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
