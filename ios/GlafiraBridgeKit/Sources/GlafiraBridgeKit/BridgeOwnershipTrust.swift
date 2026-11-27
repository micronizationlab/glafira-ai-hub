import Foundation
import CryptoKit

public enum BridgeOwnershipTrustError: Error, Equatable {
    case invalidTrustAnchor
}

/// Public ownership-signing identity pinned by an owner-created Windows Hub invitation.
/// Invalid or non-canonical anchors cannot be constructed through the public API.
public struct BridgeOwnershipTrustedSigningKey: Equatable, Sendable {
    public let ownerCoreId: String
    public let signingKeyEpoch: Int64
    public let signingPublicKeySHA256: String
    public let derEncodedPublicKey: Data

    public init(
        ownerCoreId: String,
        signingKeyEpoch: Int64,
        signingPublicKeySHA256: String,
        derEncodedPublicKey: Data
    ) throws {
        self.ownerCoreId = ownerCoreId
        self.signingKeyEpoch = signingKeyEpoch
        self.signingPublicKeySHA256 = signingPublicKeySHA256
        self.derEncodedPublicKey = derEncodedPublicKey
        guard BridgeOwnershipTrustValidation.isValid(self) else {
            throw BridgeOwnershipTrustError.invalidTrustAnchor
        }
    }

    /// Re-checks the trust anchor at an external package boundary.
    public func validate() throws {
        guard BridgeOwnershipTrustValidation.isValid(self) else {
            throw BridgeOwnershipTrustError.invalidTrustAnchor
        }
    }
}

enum BridgeOwnershipTrustValidation {
    static let maximumDEREncodedPublicKeyBytes = 512

    static func isCanonicalFingerprint(_ value: String) -> Bool {
        value.utf8.count == 64 &&
            value.allSatisfy { $0.isASCII && $0.isHexDigit && !$0.isUppercase }
    }

    static func isValid(_ key: BridgeOwnershipTrustedSigningKey) -> Bool {
        guard (8...128).contains(key.ownerCoreId.utf8.count),
              key.ownerCoreId.allSatisfy({
                  $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == ".")
              }),
              key.signingKeyEpoch > 0,
              isCanonicalFingerprint(key.signingPublicKeySHA256),
              (1...maximumDEREncodedPublicKeyBytes).contains(key.derEncodedPublicKey.count),
              SHA256.hash(data: key.derEncodedPublicKey).map({ String(format: "%02x", $0) }).joined() ==
                key.signingPublicKeySHA256,
              (try? P256.Signing.PublicKey(derRepresentation: key.derEncodedPublicKey)) != nil else {
            return false
        }
        return true
    }
}
