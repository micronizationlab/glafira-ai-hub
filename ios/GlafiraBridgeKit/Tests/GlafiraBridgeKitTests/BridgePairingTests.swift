import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class BridgePairingTests: XCTestCase {
    func testPairingProofUsesAndroidCompatibleHmacConstruction() throws {
        let capability = Data(repeating: 0x2A, count: 32)
        let invitation = BridgePairingInvitation(
            origin: "https://192.168.1.20:47321",
            certificateSHA256: String(repeating: "a", count: 64),
            ticketId: "0123456789abcdef",
            pairingCapability: capability.base64URLEncodedStringForTest(),
            expiresAtEpochMs: 2_000_000
        )

        let submission = try BridgePairing.makeSubmission(
            invitation: invitation,
            deviceLabel: "GLAFIRA iPhone",
            requestedScopes: ["project.read"],
            nowEpochMs: 1_950_000
        )

        XCTAssertEqual(submission.ticketId, invitation.ticketId)
        XCTAssertEqual(submission.platform, .ios)
        XCTAssertEqual(submission.proofHex.count, 64)
        XCTAssertEqual(submission.clientNonce.count, 43)
        XCTAssertEqual(submission.requestedScopes, ["project.read"])
    }

    func testPairingRejectsExpiredInvitation() {
        let invitation = BridgePairingInvitation(
            origin: "https://192.168.1.20:47321",
            certificateSHA256: String(repeating: "a", count: 64),
            ticketId: "0123456789abcdef",
            pairingCapability: Data(repeating: 0x01, count: 32).base64URLEncodedStringForTest(),
            expiresAtEpochMs: 1_000
        )
        XCTAssertThrowsError(try BridgePairing.makeSubmission(
            invitation: invitation,
            deviceLabel: "iPhone",
            requestedScopes: [],
            nowEpochMs: 1_001
        ))
    }

    func testPairingRejectsDuplicateScopesAndInvalidCapability() {
        let validCapability = Data(repeating: 0x01, count: 32).base64URLEncodedStringForTest()
        let invitation = BridgePairingInvitation(
            origin: "https://192.168.1.20:47321",
            certificateSHA256: String(repeating: "a", count: 64),
            ticketId: "0123456789abcdef",
            pairingCapability: validCapability,
            expiresAtEpochMs: 2_000_000
        )
        XCTAssertThrowsError(try BridgePairing.makeSubmission(
            invitation: invitation,
            deviceLabel: "iPhone",
            requestedScopes: ["project.read", "project.read"],
            nowEpochMs: 1_950_000
        ))
    }

    func testTransportRejectsPlainHTTPAndMalformedPin() {
        XCTAssertThrowsError(try PinnedBridgeHTTPSClient(
            origin: "http://192.168.1.20:47321",
            certificateSHA256: String(repeating: "a", count: 64)
        ))
        XCTAssertThrowsError(try PinnedBridgeHTTPSClient(
            origin: "https://192.168.1.20:47321",
            certificateSHA256: "not-a-fingerprint"
        ))
    }
}

private extension Data {
    func base64URLEncodedStringForTest() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
