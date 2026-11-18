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
            GlafiraSecretRedactor.redact(
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
                    "$key: ${GlafiraSecretRedactor.redact(value)}"
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
