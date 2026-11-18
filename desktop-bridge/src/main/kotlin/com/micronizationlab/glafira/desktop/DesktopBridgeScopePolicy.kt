package com.micronizationlab.glafira.desktop

internal object DesktopBridgeScopePolicy {
    val knownScopes = setOf(
        "project.read",
        "conversation.read",
        "conversation.append",
        "approval.request",
        "desktop.task.claim",
        "desktop.task.result",
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
