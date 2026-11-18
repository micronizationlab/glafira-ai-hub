package com.micronizationlab.glafira

/**
 * Best-effort masking before optional cross-provider consultation.
 * This is not a DLP engine and must never replace owner-controlled sharing policy.
 */
internal object GlafiraSecretRedactor {
    private val patterns = listOf(
        Regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b"),
        Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"),
        Regex("(?i)(api[_ -]?key\\s*[:=]\\s*)[^\\s,;]{8,}"),
        Regex("(?i)(password\\s*[:=]\\s*)[^\\s,;]{4,}"),
        Regex("(?i)(пароль\\s*[:=]\\s*)[^\\s,;]{4,}"),
        Regex("(?i)\\b(?:ghp_|gho_|github_pat_)[A-Za-z0-9_]{20,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\b[0-9]{8,}:[A-Za-z0-9_-]{25,}\\b"),
        Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"),
        Regex("(?<!\\d)\\+?\\d[\\d ()-]{8,}\\d(?!\\d)")
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
