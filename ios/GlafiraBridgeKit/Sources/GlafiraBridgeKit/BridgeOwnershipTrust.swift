import Foundation
import CryptoKit

/// Public ownership-signing identity pinned by an owner-created Windows Hub invitation.
/// Later sync responses cannot replace this trust anchor.
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
    ) {
        self.ownerCoreId = ownerCoreId
        self.signingKeyEpoch = signingKeyEpoch
        self.signingPublicKeySHA256 = signingPublicKeySHA256
        self.derEncodedPublicKey = derEncodedPublicKey
    }
}

enum BridgeOwnershipTrustValidation {
    static func isValid(_ key: BridgeOwnershipTrustedSigningKey) -> Bool {
        guard (8...128).contains(key.ownerCoreId.utf8.count),
              key.ownerCoreId.allSatisfy({
                  $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == ".")
              }),
              key.signingKeyEpoch > 0,
              key.signingPublicKeySHA256.utf8.count == 64,
              key.signingPublicKeySHA256.allSatisfy({ $0.isHexDigit && !$0.isUppercase }),
              SHA256.hash(data: key.derEncodedPublicKey).map({ String(format: "%02x", $0) }).joined() ==
                key.signingPublicKeySHA256,
              (try? P256.Signing.PublicKey(derRepresentation: key.derEncodedPublicKey)) != nil else {
            return false
        }
        return true
    }
}
