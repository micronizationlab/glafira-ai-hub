package com.micronizationlab.glafira

/**
 * Speaks only a newly delivered assistant message with a stable operation identity.
 * Partial failure previews use the ":partial" suffix and must never be spoken.
 */
internal fun shouldAutoSpeakAgentReply(previous: Msg?, current: Msg?, enabled: Boolean): Boolean {
    if (!enabled || current == null || current.isUser) return false
    val operationId = current.operationId
    if (operationId.isBlank() || operationId.endsWith(":partial")) return false
    if (previous == null || previous.isUser) return true
    return operationId != previous.operationId
}
