package com.micronizationlab.glafira

/**
 * Recognizes only explicit GitHub connection commands.
 *
 * This is navigation, not authorization: GitHub Device Flow still requires the
 * owner to complete the confirmation in GitHub and the app to persist the token.
 */
internal fun isGitHubConnectCommand(message: String): Boolean {
    val normalized = message
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    return normalized.matches(
        Regex(
            "^(?:глафира )?(?:подключи(?: меня)? (?:к )?github|" +
                "подключить (?:меня к )?github|" +
                "connect (?:me to )?github|" +
                "open github connection)$"
        )
    )
}
