package com.micronizationlab.glafira.desktop

/** Uses the terminal queue record itself as the durable event, avoiding a completion/outbox dual write. */
internal class DesktopResultOutbox(
    private val queue: DesktopTaskQueue,
    private val projectId: String,
    private val sendUntilRemoteCommitAcknowledged: (DesktopTaskRecord) -> Unit
) {
    fun deliver(record: DesktopTaskRecord) {
        require(record.envelope.projectId == projectId)
        sendUntilRemoteCommitAcknowledged(record)
        check(queue.acknowledgeResult(record.envelope.taskId, record.sequence)) { "RESULT_ACK_STATE_CHANGED" }
    }

    fun flush(): Int {
        var delivered = 0
        for (record in queue.pendingResults(projectId)) {
            try {
                deliver(record)
                delivered++
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: Exception) {
                // Retain the unchanged terminal record. Other results may still be deliverable.
            }
        }
        return delivered
    }
}
