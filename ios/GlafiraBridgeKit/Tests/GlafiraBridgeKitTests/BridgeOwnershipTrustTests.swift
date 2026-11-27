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
    ) throws -> BridgeOwnershipTrustedSigningKey {
        try BridgeOwnershipTrustedSigningKey(
            ownerCoreId: ownerCoreId,
            signingKeyEpoch: epoch,
            signingPublicKeySHA256: fingerprint ?? self.fingerprint,
            derEncodedPublicKey: publicKey ?? self.publicKey
        )
    }

    func testAcceptsCanonicalP256TrustAnchor() throws {
        XCTAssertTrue(BridgeOwnershipTrustValidation.isValid(try key()))
        XCTAssertNoThrow(try key().validate())
    }

    func testRejectsFingerprintSubstitutionAndNoncanonicalHex() throws {
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(
            try key(fingerprint: String(repeating: "f", count: 64))
        ))
        XCTAssertTrue(BridgeOwnershipTrustValidation.isCanonicalFingerprint(String(repeating: "a", count: 64)))
        XCTAssertFalse(BridgeOwnershipTrustValidation.isCanonicalFingerprint(String(repeating: "A", count: 64)))
        XCTAssertFalse(BridgeOwnershipTrustValidation.isCanonicalFingerprint("g" + String(repeating: "0", count: 63)))
    }

    func testRejectsInvalidIdentityEpochAndKeyEncoding() throws {
        XCTAssertThrowsError(try key(ownerCoreId: "short"))
        XCTAssertThrowsError(try key(ownerCoreId: "owner core 0001"))
        XCTAssertThrowsError(try key(epoch: 0))

        let invalidDER = Data(repeating: 0x5A, count: publicKey.count)
        let matchingFingerprint = SHA256.hash(data: invalidDER).map { String(format: "%02x", $0) }.joined()
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(
            try key(fingerprint: matchingFingerprint, publicKey: invalidDER)
        ))
    }

    func testRejectsOversizedDERBeforeCryptographicParsing() throws {
        let oversizedDER = Data(repeating: 0x5A, count: BridgeOwnershipTrustValidation.maximumDEREncodedPublicKeyBytes + 1)
        let matchingFingerprint = SHA256.hash(data: oversizedDER).map { String(format: "%02x", $0) }.joined()
        XCTAssertFalse(BridgeOwnershipTrustValidation.isValid(
            try key(fingerprint: matchingFingerprint, publicKey: oversizedDER)
        ))
        XCTAssertThrowsError(try BridgeOwnershipTrustedSigningKey(
            ownerCoreId: "owner-core-0001",
            signingKeyEpoch: 7,
            signingPublicKeySHA256: matchingFingerprint,
            derEncodedPublicKey: oversizedDER
        ))
    }
}
