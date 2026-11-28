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
        isValid(
            ownerCoreId: key.ownerCoreId,
            signingKeyEpoch: key.signingKeyEpoch,
            signingPublicKeySHA256: key.signingPublicKeySHA256,
            derEncodedPublicKey: key.derEncodedPublicKey
        )
    }

    /// The parser seam lets tests prove that the size limit rejects input before cryptographic parsing.
    static func isValid(
        ownerCoreId: String,
        signingKeyEpoch: Int64,
        signingPublicKeySHA256: String,
        derEncodedPublicKey: Data,
        publicKeyParser: (Data) -> Bool = {
            (try? P256.Signing.PublicKey(derRepresentation: $0)) != nil
        }
    ) -> Bool {
        guard (8...128).contains(ownerCoreId.utf8.count),
              ownerCoreId.allSatisfy({
                  $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == ".")
              }),
              signingKeyEpoch > 0,
              isCanonicalFingerprint(signingPublicKeySHA256),
              (1...maximumDEREncodedPublicKeyBytes).contains(derEncodedPublicKey.count),
              SHA256.hash(data: derEncodedPublicKey).map({ String(format: "%02x", $0) }).joined() ==
                signingPublicKeySHA256,
              publicKeyParser(derEncodedPublicKey) else {
            return false
        }
        return true
    }
}
