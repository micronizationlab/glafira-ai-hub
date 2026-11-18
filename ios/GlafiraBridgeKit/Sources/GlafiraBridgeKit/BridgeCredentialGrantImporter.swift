import Foundation

/// Credential payload expected from the Android owner-approval service after
/// the owner explicitly approves an enrollment. It must only be accepted over
/// the pinned Bridge HTTPS session; a pending pairing response is never a grant.
public struct OwnerApprovedBridgeCredentialGrant: Codable, Sendable {
    public let approvalId: String
    public let ticketId: String
    public let deviceId: String
    public let hmacSecretBase64URL: String
    public let approvedScopes: [String]
    public let issuedAtEpochMs: Int64
    public let expiresAtEpochMs: Int64

    public init(approvalId: String, ticketId: String, deviceId: String,
                hmacSecretBase64URL: String, approvedScopes: [String],
                issuedAtEpochMs: Int64, expiresAtEpochMs: Int64) {
        self.approvalId = approvalId
        self.ticketId = ticketId
        self.deviceId = deviceId
        self.hmacSecretBase64URL = hmacSecretBase64URL
        self.approvedScopes = approvedScopes
        self.issuedAtEpochMs = issuedAtEpochMs
        self.expiresAtEpochMs = expiresAtEpochMs
    }
}

public enum BridgeCredentialGrantError: Error, Equatable, Sendable {
    case invalidApprovalBinding
    case expiredGrant
    case invalidScopes
    case invalidSecret
}

/// Validates an Android-issued owner grant and persists its HMAC key in the
/// device-only, current-biometric-set Keychain. The grant is never accepted
/// from the PENDING pairing response and never persisted before Face ID/Touch ID.
public enum BridgeCredentialGrantImporter {
    public static func validate(
        _ grant: OwnerApprovedBridgeCredentialGrant,
        expectedApprovalId: String,
        expectedTicketId: String,
        requestedScopes: Set<String>,
        nowEpochMs: Int64
    ) throws -> BridgeDeviceCredential {
        guard !expectedApprovalId.isEmpty,
              grant.approvalId == expectedApprovalId,
              !expectedTicketId.isEmpty,
              grant.ticketId == expectedTicketId,
              !grant.deviceId.isEmpty else {
            throw BridgeCredentialGrantError.invalidApprovalBinding
        }
        let (latestIssuedAt, overflow) = nowEpochMs.addingReportingOverflow(30_000)
        guard !overflow,
              grant.issuedAtEpochMs > 0,
              grant.expiresAtEpochMs > nowEpochMs,
              grant.issuedAtEpochMs <= latestIssuedAt,
              grant.expiresAtEpochMs > grant.issuedAtEpochMs,
              grant.expiresAtEpochMs - grant.issuedAtEpochMs <= 5 * 60 * 1_000 else {
            throw BridgeCredentialGrantError.expiredGrant
        }
        let approved = Set(grant.approvedScopes)
        guard approved.count == grant.approvedScopes.count,
              approved.isSubset(of: requestedScopes),
              grant.approvedScopes.allSatisfy({ scope in
                  !scope.isEmpty && scope.count <= 96 &&
                  scope.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-" })
              }) else {
            throw BridgeCredentialGrantError.invalidScopes
        }
        guard let secret = Data(base64URLEncoded: grant.hmacSecretBase64URL),
              (32...64).contains(secret.count) else {
            throw BridgeCredentialGrantError.invalidSecret
        }
        return try BridgeDeviceCredential(deviceId: grant.deviceId, hmacSecret: secret)
    }

    public static func importApprovedGrant(
        _ grant: OwnerApprovedBridgeCredentialGrant,
        expectedApprovalId: String,
        expectedTicketId: String,
        requestedScopes: Set<String>,
        credentialStore: KeychainBridgeCredentialStore,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async throws {
        let credential = try validate(
            grant,
            expectedApprovalId: expectedApprovalId,
            expectedTicketId: expectedTicketId,
            requestedScopes: requestedScopes,
            nowEpochMs: nowEpochMs
        )
        try await credentialStore.store(
            credential,
            ownerConfirmationReason: "Подтвердите сохранение ключа одобренного GLAFIRA Bridge"
        )
    }
}

private extension Data {
    init?(base64URLEncoded value: String) {
        guard !value.isEmpty, value.utf8.count <= 128 else { return nil }
        var normalized = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 { normalized += String(repeating: "=", count: 4 - remainder) }
        self.init(base64Encoded: normalized)
    }
}
