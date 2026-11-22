package com.micronizationlab.glafira

/**
 * Speaks only a newly delivered assistant message.
 *
 * A normal request stores the owner's message and the assistant's reply with the same
 * operationId. Therefore the role transition from user -> assistant must be considered
 * a new reply even though the operationId is unchanged. Repeated assistant snapshots
 * from history polling must not be spoken twice.
 */
internal fun shouldAutoSpeakAgentReply(previous: Msg?, current: Msg?, enabled: Boolean): Boolean {
    if (!enabled || current == null || current.isUser) return false
    if (previous == null || previous.isUser) return true
    return current.operationId.isNotBlank() && current.operationId != previous.operationId
}
