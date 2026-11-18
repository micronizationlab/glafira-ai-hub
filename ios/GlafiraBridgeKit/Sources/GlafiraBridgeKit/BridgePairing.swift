import Foundation
import CryptoKit

public enum BridgeClientPlatform: String, Codable, Sendable {
    case ios = "IOS"
}

public struct BridgePairingInvitation: Codable, Sendable {
    public let origin: String
    public let certificateSHA256: String
    public let ticketId: String
    public let pairingCapability: String
    public let expiresAtEpochMs: Int64

    public init(origin: String, certificateSHA256: String, ticketId: String,
                pairingCapability: String, expiresAtEpochMs: Int64) {
        self.origin = origin
        self.certificateSHA256 = certificateSHA256
        self.ticketId = ticketId
        self.pairingCapability = pairingCapability
        self.expiresAtEpochMs = expiresAtEpochMs
    }
}

public struct BridgePairingSubmission: Codable, Sendable {
    public let ticketId: String
    public let clientNonce: String
    public let proofHex: String
    public let deviceLabel: String
    public let platform: BridgeClientPlatform
    public let requestedScopes: [String]
}

public struct BridgePairingPendingResponse: Codable, Sendable {
    public let status: String
    public let approvalId: String
    public let receivedAtEpochMs: Int64
}

/// Implements the same one-time HMAC proof as Android. Proof acceptance only
/// queues a pending owner decision; it never grants scopes or creates secrets.
public enum BridgePairing {
    public static func makeSubmission(invitation: BridgePairingInvitation,
                                      deviceLabel: String,
                                      requestedScopes: [String],
                                      nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)) throws -> BridgePairingSubmission {
        guard invitation.ticketId.utf8.count in 16...128,
              invitation.ticketId.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }),
              invitation.expiresAtEpochMs > nowEpochMs,
              invitation.expiresAtEpochMs - nowEpochMs <= 120_000,
              !deviceLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              deviceLabel.utf8.count <= 128,
              requestedScopes.count <= 64,
              Set(requestedScopes).count == requestedScopes.count,
              requestedScopes.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 128 }),
              let capability = Data(base64URLEncoded: invitation.pairingCapability),
              capability.count == 32 else {
            throw BridgeTransportError.invalidHTTPResponse
        }
        var nonceBytes = Data(count: 32)
        let randomStatus = nonceBytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard randomStatus == errSecSuccess else { throw BridgeTransportError.invalidHTTPResponse }
        let nonce = nonceBytes.base64URLEncodedString()
        let verifier = Data(SHA256.hash(data: capability))
        let message = Data("GLAFIRA-BRIDGE-PAIRING-V1\0\(invitation.ticketId)\0\(nonce)".utf8)
        let proof = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: verifier))
        return BridgePairingSubmission(ticketId: invitation.ticketId, clientNonce: nonce,
                                       proofHex: proof.map { String(format: "%02x", $0) }.joined(),
                                       deviceLabel: deviceLabel, platform: .ios,
                                       requestedScopes: requestedScopes.sorted())
    }

    public static func submit(_ submission: BridgePairingSubmission,
                              invitation: BridgePairingInvitation,
                              client: PinnedBridgeHTTPSClient) async throws -> BridgePairingPendingResponse {
        guard submission.ticketId == invitation.ticketId,
              invitation.expiresAtEpochMs > Int64(Date().timeIntervalSince1970 * 1_000) else {
            throw BridgeTransportError.invalidHTTPResponse
        }
        return try await client.submitPairing(submission)
    }

private extension Data {
    init?(base64URLEncoded value: String) {
        var normalized = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 { normalized += String(repeating: "=", count: 4 - remainder) }
        self.init(base64Encoded: normalized)
    }

    func base64URLEncodedString() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}
private extension Data {
    init?(base64URLEncoded value: String) {
        var normalized = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 { normalized += String(repeating: "=", count: 4 - remainder) }
        self.init(base64Encoded: normalized)
    }

    func base64URLEncodedString() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}
