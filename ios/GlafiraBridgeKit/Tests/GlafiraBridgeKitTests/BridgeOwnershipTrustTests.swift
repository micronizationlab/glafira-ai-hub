import CryptoKit
import XCTest
@testable import GlafiraBridgeKit

final class BridgeOwnershipTrustTests: XCTestCase {
    private let privateKey = P256.Signing.PrivateKey()

    private var publicKey: Data { privateKey.publicKey.derRepresentation }
    private var fingerprint: String {
        SHA256.hash(data: publicKey).map { String(format: "%02x", $0) }.joined()
    }

    private func key(
        ownerCoreId: String = "owner-core-0001",
        epoch: Int64 = 7,
        fingerprint: String? = nil,
        publicKey: Data? = nil
    ) -> BridgeOwnershipTrustedSigningKey {
        BridgeOwnershipTrustedSigningKey(
            ownerCoreId: ownerCoreId,
            signingKeyEpoch: epoch,
            signingPublicKeySHA256: fingerprint ?? self.fingerprint,
            derEncodedPublicKey: publicKey ?? self.publicKey
        )
    }

    func testAcceptsCanonicalP256TrustAnchor() {
        XCTAssertTrue(BridgeOwnershipTrustValidation.isValid(key()))
    }

    func testRejectsFingerprintSubstitutionAndNoncanonicalHex() {
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(
            key(fingerprint: String(repeating: "0", count: 64))
        ))
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(
            key(fingerprint: fingerprint.uppercased())
        ))
    }

    func testRejectsInvalidIdentityEpochAndKeyEncoding() {
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(key(ownerCoreId: "short")))
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(key(ownerCoreId: "owner core 0001")))
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(key(epoch: 0)))
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(
            key(publicKey: Data(repeating: 0x5A, count: publicKey.count))
        ))
    }
}
