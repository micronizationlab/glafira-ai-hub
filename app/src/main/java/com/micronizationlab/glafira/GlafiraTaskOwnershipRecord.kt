package com.micronizationlab.glafira

/** Canonical owner metadata for one task; distinct from any executor/Hub lease. */
data class GlafiraTaskOwnershipRecord(
    val taskId: String,
    val projectId: String,
    val ownerCoreId: String,
    val ownerGeneration: Long,
    val stateVersion: Long,
    val payloadSha256: String,
    val idempotencyKey: String,
    val effectRisk: String,
    val approvalRef: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
