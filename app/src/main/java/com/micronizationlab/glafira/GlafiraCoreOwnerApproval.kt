package com.micronizationlab.glafira

import androidx.biometric.BiometricPrompt
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived proof created only from a successful AndroidX BiometricPrompt callback.
 * It is intentionally process-local and cannot be restored from backup or supplied by an agent.
 */
class GlafiraCoreOwnerSession private constructor(
    val sessionId: String,
    val expiresAtElapsedRealtime: Long
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(): Boolean =
        sessionId.length in 16..64 &&
            expiresAtElapsedRealtime > SystemClock.elapsedRealtime() &&
            consumed.compareAndSet(false, true)

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

    /** Payload fields are shown to the owner with credential-like keys and values redacted. */
    val reviewDetails: String
        get() = runCatching {
            val json = org.json.JSONObject(task.payload)
            val secretKey = Regex("(?i).*(password|passphrase|secret|token|api.?key|authorization|credential|private.?key|cookie|signature|auth).*")
            val keys = json.keys().asSequence().filterNot { secretKey.matches(it) }.sorted().toList()
            val visible = keys.take(MAX_REVIEW_FIELDS).map { key ->
                val rawValue = json.opt(key)?.toString().orEmpty()
                val truncated = rawValue.length > MAX_REVIEW_FIELD_CHARS
                val value = rawValue.take(MAX_REVIEW_FIELD_CHARS)
                "$key: ${GlafiraOwnerApprovalPreviewRedactor.redact(value)}" +
                    if (truncated) " [ПОЛЕ ОБРЕЗАНО]" else ""
            }.toMutableList()
            val hidden = keys.size - visible.size
            if (hidden > 0) visible += "[ЕЩЁ СКРЫТО ПОЛЕЙ: $hidden — проверьте исходное задание перед подтверждением]"
            visible.joinToString("\n")
        }.getOrDefault("Не удалось безопасно прочитать детали задания.")

    companion object {
        private const val MAX_REVIEW_FIELDS = 24
        private const val MAX_REVIEW_FIELD_CHARS = 800
    }
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
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
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
