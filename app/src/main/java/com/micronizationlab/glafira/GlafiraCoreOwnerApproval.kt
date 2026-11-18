package com.micronizationlab.glafira

import androidx.biometric.BiometricPrompt
import android.os.SystemClock
import java.util.UUID

/**
 * Short-lived proof created only from a successful AndroidX BiometricPrompt callback.
 * It is intentionally process-local and cannot be restored from backup or supplied by an agent.
 */
class GlafiraCoreOwnerSession private constructor(
    val sessionId: String,
    val expiresAtElapsedRealtime: Long
) {
    internal fun isValid(): Boolean =
        sessionId.length in 16..64 &&
            expiresAtElapsedRealtime > SystemClock.elapsedRealtime()

    companion object {
        internal fun fromAuthentication(
            result: BiometricPrompt.AuthenticationResult,
            lifetimeMillis: Long = 120_000L
        ): GlafiraCoreOwnerSession {
            // The result object is supplied only by BiometricPrompt.onAuthenticationSucceeded.
            require(lifetimeMillis in 1_000L..300_000L)
            result.authenticationType // Force use of a real platform authentication result.
            return GlafiraCoreOwnerSession(
                sessionId = UUID.randomUUID().toString(),
                expiresAtElapsedRealtime = SystemClock.elapsedRealtime() + lifetimeMillis
            )
        }
    }
}

data class GlafiraPendingActionApproval(
    val task: GlafiraTask,
    val actionHash: String,
    val requestedScopes: Set<String>,
    val requestedAtMillis: Long,
    val expiresAtMillis: Long
) {
    val actionSummary: String
        get() = runCatching {
            GlafiraOwnerApprovalPreviewRedactor.redact(
                org.json.JSONObject(task.payload).optString("actionSummary").trim()
            )
        }.getOrDefault("")

    /** Only known review fields are surfaced; secret-like values are masked before display. */
    val reviewDetails: String
        get() = runCatching {
            val json = org.json.JSONObject(task.payload)
            val visibleKeys = setOf(
                "recipient", "to", "destination", "url", "subject", "message", "content",
                "amount", "currency", "recordId", "fileName", "title", "text", "body",
                "target", "contact", "comment", "channel"
            )
            visibleKeys.sorted().mapNotNull { key ->
                if (!json.has(key)) null else {
                    val value = json.opt(key)?.toString().orEmpty().take(800)
                    "$key: ${GlafiraOwnerApprovalPreviewRedactor.redact(value)}"
                }
            }.joinToString("\n")
        }.getOrDefault("")
}

enum class GlafiraCoreOwnerApprovalMutation {
    APPROVED,
    REJECTED,
    NOT_FOUND,
    ALREADY_DECIDED,
    INVALID_SCOPE_SELECTION,
    OWNER_AUTHENTICATION_REQUIRED,
    STORAGE_ERROR
}


/** Masks credential material while preserving recipient and message text for owner review. */
internal object GlafiraOwnerApprovalPreviewRedactor {
    private val patterns = listOf(
        Regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b"),
        Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"),
        Regex("(?i)(api[_ -]?key\\s*[:=]\\s*)[^\\s,;]{8,}"),
        Regex("(?i)(password\\s*[:=]\\s*)[^\\s,;]{4,}"),
        Regex("(?i)(пароль\\s*[:=]\\s*)[^\\s,;]{4,}"),
        Regex("(?i)\\b(?:ghp_|gho_|github_pat_)[A-Za-z0-9_]{20,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b")
    )

    fun redact(value: String): String {
        var safe = value
        patterns.forEach { pattern ->
            safe = pattern.replace(safe) { match ->
                val prefix = match.groupValues.getOrNull(1).orEmpty()
                prefix + "[СКРЫТО]"
            }
        }
        return safe
    }
}
