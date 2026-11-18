package com.micronizationlab.glafira.desktop

internal object DesktopBridgeScopePolicy {
    val knownScopes = setOf(
        "project.read",
        "conversation.read",
        "conversation.append",
        "approval.request",
        "content.draft",
        "communications.send",
        "social.publish",
        "crm.write",
        "finance.payment",
        "data.delete"
    )

    fun isValidScope(scope: String): Boolean =
        scope in knownScopes
}
