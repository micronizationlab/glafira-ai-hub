package com.micronizationlab.glafira

import java.util.Collections
import java.util.LinkedHashSet

class GlafiraProviderDataGrant(
    val projectId: String,
    val providerId: ProviderId,
    allowedClassifications: Set<GlafiraProviderDataClassification>,
    val expiresAtMillis: Long,
    val revokedAtMillis: Long? = null
) {
    // Snapshot caller input and prevent mutation through either the original Set or a cast.
    val allowedClassifications: Set<GlafiraProviderDataClassification> =
        Collections.unmodifiableSet(LinkedHashSet(allowedClassifications))

    init {
        require(isSafePolicyId(projectId)) { "PROVIDER_GRANT_PROJECT_INVALID" }
        require(this.allowedClassifications.isNotEmpty()) { "PROVIDER_GRANT_EMPTY" }
        require(GlafiraProviderDataClassification.RESTRICTED !in this.allowedClassifications) {
            "PROVIDER_RESTRICTED_CLASSIFICATION_NEVER_GRANTABLE"
        }
        require(expiresAtMillis > 0L) { "PROVIDER_GRANT_EXPIRY_INVALID" }
        require(revokedAtMillis == null || revokedAtMillis > 0L) {
            "PROVIDER_GRANT_REVOCATION_INVALID"
        }
    }
}

data class GlafiraProviderContextItem(
    val referenceId: String,
    val projectId: String,
    val classification: GlafiraProviderDataClassification,
    val content: String
) {
    init {
        require(isSafePolicyId(referenceId)) { "PROVIDER_CONTEXT_REFERENCE_INVALID" }
        require(isSafePolicyId(projectId)) { "PROVIDER_CONTEXT_PROJECT_INVALID" }
        require(content.isNotBlank() && content.length <= GlafiraProviderContextPolicy.MAX_PROVIDER_CONTEXT_ITEM_CHARS) {
            "PROVIDER_CONTEXT_ITEM_SIZE_INVALID"
        }
    }
}

enum class GlafiraProviderContextDenial {
    NO_VALID_GRANT,
    PROJECT_MISMATCH,
    RESTRICTED_NEVER_SHARED,
    CLASSIFICATION_NOT_GRANTED
}

data class GlafiraProviderContextDecision(
    val allowedItems: List<GlafiraProviderContextItem>,
    /** Only opaque references and reason codes are returned; denied content is never copied. */
    val deniedItems: List<Pair<String, GlafiraProviderContextDenial>>
)

/** Pure fail-closed policy kernel; callers must use durable owner grants and Trace-before-dispatch. */
object GlafiraProviderContextPolicy {
    const val MAX_PROVIDER_CONTEXT_ITEMS = 64
    const val MAX_PROVIDER_CONTEXT_TOTAL_CHARS = 65_536
    const val MAX_PROVIDER_CONTEXT_ITEM_CHARS = 16_384

    fun assemble(
        projectId: String,
        providerId: ProviderId,
        items: List<GlafiraProviderContextItem>,
        grant: GlafiraProviderDataGrant?,
        nowMillis: Long
    ): GlafiraProviderContextDecision {
        require(isSafePolicyId(projectId)) { "PROVIDER_CONTEXT_PROJECT_INVALID" }
        require(nowMillis > 0L) { "PROVIDER_CONTEXT_TIME_INVALID" }
        require(items.size <= MAX_PROVIDER_CONTEXT_ITEMS) { "PROVIDER_CONTEXT_ITEM_COUNT_EXCEEDED" }
        require(items.map { it.referenceId }.distinct().size == items.size) {
            "PROVIDER_CONTEXT_DUPLICATE_REFERENCE"
        }
        require(items.sumOf { it.content.length } <= MAX_PROVIDER_CONTEXT_TOTAL_CHARS) {
            "PROVIDER_CONTEXT_TOTAL_SIZE_EXCEEDED"
        }

        val grantIsValid = grant != null &&
            grant.projectId == projectId &&
            grant.providerId == providerId &&
            grant.revokedAtMillis == null &&
            nowMillis < grant.expiresAtMillis

        val allowed = mutableListOf<GlafiraProviderContextItem>()
        val denied = mutableListOf<Pair<String, GlafiraProviderContextDenial>>()
        items.forEach { item ->
            val reason = when {
                !grantIsValid -> GlafiraProviderContextDenial.NO_VALID_GRANT
                item.projectId != projectId -> GlafiraProviderContextDenial.PROJECT_MISMATCH
                item.classification == GlafiraProviderDataClassification.RESTRICTED ->
                    GlafiraProviderContextDenial.RESTRICTED_NEVER_SHARED
                item.classification !in grant!!.allowedClassifications ->
                    GlafiraProviderContextDenial.CLASSIFICATION_NOT_GRANTED
                else -> null
            }
            if (reason == null) allowed += item else denied += item.referenceId to reason
        }
        return GlafiraProviderContextDecision(
            Collections.unmodifiableList(allowed.toList()),
            Collections.unmodifiableList(denied.toList())
        )
    }
}

private fun isSafePolicyId(value: String): Boolean =
    value.length in 1..128 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*"))
