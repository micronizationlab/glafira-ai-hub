package com.micronizationlab.glafira

import org.json.JSONObject

/** Durable receipt identity; transport request IDs and timestamps do not define a new action. */
internal object GlafiraBridgeDurableTaskIdentity {
    fun supports(operation: GlafiraBridgeOperation): Boolean = operation in setOf(
        GlafiraBridgeOperation.PROJECT_READ,
        GlafiraBridgeOperation.CONVERSATION_READ,
        GlafiraBridgeOperation.CONVERSATION_APPEND
    )

    fun equivalent(existing: GlafiraTask, submitted: GlafiraTask): Boolean {
        if (existing.projectId != submitted.projectId || existing.origin != submitted.origin ||
            existing.taskType != submitted.taskType || existing.requiredScopes != submitted.requiredScopes ||
            existing.idempotencyKey != submitted.idempotencyKey
        ) return false
        val existingPayload = semanticPayload(existing.payload) ?: return false
        val submittedPayload = semanticPayload(submitted.payload) ?: return false
        return existingPayload == submittedPayload
    }

    private fun semanticPayload(encoded: String): Map<String, String>? = runCatching {
        val json = JSONObject(encoded)
        buildMap {
            for (key in json.keys()) {
                // Only this transport correlation field may change on resubmission.
                if (key == "bridgeRequestId") continue
                val value = json.get(key)
                require(value is String) { "Bridge task payload fields must be strings." }
                put(key, value)
            }
        }
    }.getOrNull()
}
