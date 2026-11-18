import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class OwnerSecurityTests: XCTestCase {
    func testCredentialRequiresValidDeviceIdAndAtLeast256BitSecret() throws {
        let secret = Data(repeating: 0x5A, count: 32)
        XCTAssertNoThrow(try BridgeDeviceCredential(deviceId: "device-0001", hmacSecret: secret))

        XCTAssertThrowsError(
            try BridgeDeviceCredential(deviceId: "bad id", hmacSecret: secret)
        ) { error in
            XCTAssertEqual(error as? BridgeCredentialStoreError, .invalidDeviceIdentifier)
        }

        XCTAssertThrowsError(
            try BridgeDeviceCredential(deviceId: "device-0001", hmacSecret: Data(repeating: 0, count: 31))
        ) { error in
            XCTAssertEqual(error as? BridgeCredentialStoreError, .invalidSecret)
        }
    }

    func testCredentialRejectsUnicodeSupplementaryIdentifier() {
        XCTAssertThrowsError(
            try BridgeDeviceCredential(
                deviceId: "device-😀01",
                hmacSecret: Data(repeating: 0x01, count: 32)
            )
        )
    }

    func testAuthenticatorReportsAvailabilityWithoutPrompt() {
        let authenticator = OwnerBiometricAuthenticator()
        _ = authenticator.isAvailable()
    }

    func testEmptyReasonIsRejectedBeforeSystemPrompt() async {
        let authenticator = OwnerBiometricAuthenticator()
        do {
            try await authenticator.authenticate(reason: "   ")
            XCTFail("Expected empty reason to be rejected")
        } catch {
            XCTAssertEqual(error as? OwnerAuthenticationError, .failed)
        }
    }
}
