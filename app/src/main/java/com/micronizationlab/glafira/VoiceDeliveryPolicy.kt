package com.micronizationlab.glafira

/** Prevents automatic speech from replaying loaded history or duplicate poll snapshots. */
internal fun shouldAutoSpeakAgentReply(previous: Msg?, current: Msg?, enabled: Boolean): Boolean =
    enabled && current != null && !current.isUser && current.operationId.isNotBlank() && current.operationId != previous?.operationId
