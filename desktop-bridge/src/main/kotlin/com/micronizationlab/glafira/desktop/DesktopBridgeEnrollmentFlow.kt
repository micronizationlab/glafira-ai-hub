package com.micronizationlab.glafira.desktop

import java.nio.file.Path

/**
 * Small runtime facade that joins the invitation, approval and vault steps for Desktop.
 * The UI supplies clipboard text and the owner passphrase; no secret is logged or persisted
 * outside the encrypted vault file.
 */
class DesktopBridgeEnrollmentFlow(
    private val pairingClient: DesktopBridgePairingHttpClient = DesktopBridgePairingHttpClient()
) {
    fun submitInvitation(
        invitationBundle: String,
        deviceLabel: String,
        requestedScopes: Set<String>
    ): DesktopBridgePairingResponse {
        val session = DesktopBridgePairing.fromInvitationBundle(
            invitationBundle, deviceLabel, requestedScopes
        )
        return pairingClient.submit(
            session.origin,
            session.serverCertificateSha256,
            session.enrollment
        )
    }

    fun importOwnerApprovedCredential(
        vaultPath: Path,
        ownerCredentialBundle: String,
        vaultPassphrase: CharArray
    ) {
        DesktopBridgeCredentialVault.importOwnerBundle(
            vaultPath, ownerCredentialBundle, vaultPassphrase
        )
    }

    @JvmOverloads
    fun postFromVault(
        vaultPath: Path,
        vaultPassphrase: CharArray,
        projectId: String,
        operation: DesktopBridgeOperation,
        payload: Map<String, String>,
        logicalOperationId: String? = null
    ): DesktopBridgeResponse {
        return DesktopBridgeCredentialVault.loadAndUseVault(vaultPath, vaultPassphrase) { origin, credentials ->
            DesktopBridgeHttpsClient().post(
                origin, credentials, projectId, operation, payload, logicalOperationId
            )
        }
    }
}
