import Foundation
import CoreFoundation

/// Exact owner-created invitation emitted by the authoritative Windows Desktop Hub.
public struct BridgePairingInvitation: Sendable {
    public let version: Int
    public let agentId: String
    public let endpoint: String
    public let certificateSHA256: String
    public let ownershipTrustedSigningKey: MobileOwnershipTrustedSigningKey
    public let ticketId: String
    public let pairingCode: String
    public let scopes: Set<String>
    public let expiresAtEpochMs: Int64

    public init(
        version: Int = 1,
        agentId: String,
        endpoint: String,
        certificateSHA256: String,
        ownershipTrustedSigningKey: MobileOwnershipTrustedSigningKey,
        ticketId: String,
        pairingCode: String,
        scopes: Set<String>,
        expiresAtEpochMs: Int64
    ) {
        self.version = version
        self.agentId = agentId
        self.endpoint = endpoint
        self.certificateSHA256 = certificateSHA256
        self.ownershipTrustedSigningKey = ownershipTrustedSigningKey
        self.ticketId = ticketId
        self.pairingCode = pairingCode
        self.scopes = scopes
        self.expiresAtEpochMs = expiresAtEpochMs
    }

    public static func parseDesktopHubBundle(
        _ json: Data,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) throws -> BridgePairingInvitation {
        guard (1...8_192).contains(json.count),
              let object = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              Set(object.keys) == Set([
                "version", "agentId", "endpoint", "tlsCertificateSha256", "ticketId",
                "ownerCoreId", "ownershipSigningKeyEpoch",
                "ownershipSigningPublicKeySpkiBase64", "ownershipSigningPublicKeySha256",
                "pairingCode", "scopes", "expiresAtEpochMs"
              ]),
              let versionNumber = object["version"] as? NSNumber,
              CFGetTypeID(versionNumber) != CFBooleanGetTypeID(),
              versionNumber.intValue == 1, versionNumber.doubleValue == 1,
              let agentId = object["agentId"] as? String,
              let endpoint = object["endpoint"] as? String,
              let pin = object["tlsCertificateSha256"] as? String,
              let ownerCoreId = object["ownerCoreId"] as? String,
              let keyEpochNumber = object["ownershipSigningKeyEpoch"] as? NSNumber,
              CFGetTypeID(keyEpochNumber) != CFBooleanGetTypeID(),
              keyEpochNumber.doubleValue == Double(keyEpochNumber.int64Value),
              let publicKeyText = object["ownershipSigningPublicKeySpkiBase64"] as? String,
              let publicKeyFingerprint = object["ownershipSigningPublicKeySha256"] as? String,
              let ticketId = object["ticketId"] as? String,
              let pairingCode = object["pairingCode"] as? String,
              let scopesArray = object["scopes"] as? [String],
              let expiryNumber = object["expiresAtEpochMs"] as? NSNumber,
              CFGetTypeID(expiryNumber) != CFBooleanGetTypeID(),
              expiryNumber.doubleValue == Double(expiryNumber.int64Value) else {
            throw BridgeInvitationError.invalidFormat
        }

        let expiry = expiryNumber.int64Value
        let ownershipTrustedSigningKey = MobileOwnershipTrustedSigningKey(
            ownerCoreId: ownerCoreId,
            signingKeyEpoch: keyEpochNumber.int64Value,
            signingPublicKeySha256: publicKeyFingerprint,
            derEncodedPublicKey: Data(base64Encoded: publicKeyText) ?? Data()
        )
        guard expiry > nowEpochMs, expiry - nowEpochMs <= 10 * 60 * 1_000 else {
            throw BridgeInvitationError.expired
        }
        guard let url = URL(string: endpoint),
              url.scheme?.lowercased() == "https", url.host != nil,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.path == "/bridge/v1",
              (url.port == nil || (1...65_535).contains(url.port!)),
              Self.validIdentifier(agentId, minimum: 8),
              ticketId.utf8.count == 32, ticketId.allSatisfy(\.isHexDigit),
              pin.utf8.count == 64, pin.allSatisfy(\.isHexDigit),
              MobileOwnershipTrustValidation.isValid(ownershipTrustedSigningKey),
              ownershipTrustedSigningKey.derEncodedPublicKey.base64EncodedString() == publicKeyText,
              let pairingBytes = Data(base64URLEncoded: pairingCode), pairingBytes.count == 32 else {
            throw BridgeInvitationError.invalidServerIdentity
        }
        let scopes = Set(scopesArray)
        guard !scopes.isEmpty, scopes.count == scopesArray.count, scopes.count <= 32,
              scopes.allSatisfy(Self.validScope) else {
            throw BridgeInvitationError.invalidScopes
        }
        return BridgePairingInvitation(
            agentId: agentId,
            endpoint: endpoint,
            certificateSHA256: pin.lowercased(),
            ownershipTrustedSigningKey: ownershipTrustedSigningKey,
            ticketId: ticketId.lowercased(),
            pairingCode: pairingCode,
            scopes: scopes,
            expiresAtEpochMs: expiry
        )
    }

    private static func validIdentifier(_ value: String, minimum: Int) -> Bool {
        (minimum...128).contains(value.utf8.count) && value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_")
        }
    }

    private static func validScope(_ scope: String) -> Bool {
        (1...96).contains(scope.utf8.count) && scope.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-")
        }
    }
}

public struct BridgePairingRequest: Codable, Sendable {
    public let version: Int
    public let ticketId: String
    public let pairingCode: String
    public let agentId: String

    public init(invitation: BridgePairingInvitation) {
        version = invitation.version
        ticketId = invitation.ticketId
        pairingCode = invitation.pairingCode
        agentId = invitation.agentId
    }
}

public struct BridgePairingEnrollmentResponse: Codable, Sendable {
    public let version: Int
    public let status: String
    public let errorCode: String?
    public let agentId: String?
    public let enrollmentId: String?
    public let desktopHubId: String?
    public let endpoint: String?
    public let tlsCertificateSha256: String?
    public let ownerCoreId: String?
    public let ownershipSigningKeyEpoch: Int64?
    public let ownershipSigningPublicKeySpkiBase64: String?
    public let ownershipSigningPublicKeySha256: String?
    public let sharedSecretBase64: String?
    public let scopes: [String]
    public let enrolledAtEpochMs: Int64?
    public let expiresAtEpochMs: Int64?

    public static func decodeStrict(_ data: Data) throws -> BridgePairingEnrollmentResponse {
        let expectedKeys = Set([
            "version", "status", "errorCode", "agentId", "enrollmentId", "desktopHubId",
            "endpoint", "tlsCertificateSha256", "ownerCoreId", "ownershipSigningKeyEpoch",
            "ownershipSigningPublicKeySpkiBase64", "ownershipSigningPublicKeySha256",
            "sharedSecretBase64", "scopes",
            "enrolledAtEpochMs", "expiresAtEpochMs"
        ])
        guard (1...8_192).contains(data.count),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == expectedKeys else {
            throw BridgeInvitationError.invalidEnrollmentResponse
        }
        do {
            return try JSONDecoder().decode(BridgePairingEnrollmentResponse.self, from: data)
        } catch {
            throw BridgeInvitationError.invalidEnrollmentResponse
        }
    }

    public func credential(
        matching invitation: BridgePairingInvitation,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) throws -> BridgeDeviceCredential {
        let grantedScopes = Set(scopes)
        let invitationTrust = invitation.ownershipTrustedSigningKey
        guard version == 1, status == "ENROLLED", errorCode == nil,
              agentId == invitation.agentId,
              enrollmentId == invitation.ticketId,
              endpoint == invitation.endpoint,
              tlsCertificateSha256?.caseInsensitiveCompare(invitation.certificateSHA256) == .orderedSame,
              ownerCoreId == invitationTrust.ownerCoreId,
              ownershipSigningKeyEpoch == invitationTrust.signingKeyEpoch,
              ownershipSigningPublicKeySha256 == invitationTrust.signingPublicKeySha256,
              ownershipSigningPublicKeySpkiBase64 == invitationTrust.derEncodedPublicKey.base64EncodedString(),
              desktopHubId.map({ hubId in
                (1...128).contains(hubId.utf8.count) && hubId.allSatisfy {
                    $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_")
                }
              }) == true,
              grantedScopes.count == scopes.count, !grantedScopes.isEmpty,
              grantedScopes.isSubset(of: invitation.scopes),
              let enrolledAtEpochMs, let expiresAtEpochMs,
              enrolledAtEpochMs <= nowEpochMs, expiresAtEpochMs > nowEpochMs,
              let secretText = sharedSecretBase64,
              let secret = Data(base64Encoded: secretText), secret.count == 32 else {
            throw BridgeInvitationError.invalidEnrollmentResponse
        }
        return try BridgeDeviceCredential(
            deviceId: invitation.agentId,
            hmacSecret: secret,
            ownershipTrustedSigningKey: invitationTrust
        )
    }
}

public enum BridgeInvitationError: Error, Equatable, Sendable {
    case invalidFormat
    case expired
    case invalidServerIdentity
    case invalidScopes
    case invalidEnrollmentResponse
}

private extension Data {
    init?(base64URLEncoded value: String) {
        var normalized = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 { normalized += String(repeating: "=", count: 4 - remainder) }
        self.init(base64Encoded: normalized)
    }
}
