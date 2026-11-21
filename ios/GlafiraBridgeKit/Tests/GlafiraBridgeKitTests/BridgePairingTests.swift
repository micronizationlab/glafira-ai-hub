import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class BridgePairingTests: XCTestCase {
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)
    private let pairingCode = Data(repeating: 0x2A, count: 32).base64URLForTest()

    private func bundle(extra: [String: Any] = [:]) throws -> Data {
        var object: [String: Any] = [
            "version": 1,
            "agentId": "ios-device-0001",
            "endpoint": endpoint,
            "tlsCertificateSha256": pin,
            "ticketId": "0123456789abcdef0123456789abcdef",
            "pairingCode": pairingCode,
            "scopes": ["ownership.read", "project.read"],
            "expiresAtEpochMs": 2_000_000
        ]
        extra.forEach { object[$0.key] = $0.value }
        return try JSONSerialization.data(withJSONObject: object)
    }

    func testParsesExactWindowsDesktopHubInvitation() throws {
        let invitation = try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(), nowEpochMs: 1_950_000
        )
        XCTAssertEqual(invitation.endpoint, endpoint)
        XCTAssertEqual(invitation.agentId, "ios-device-0001")
        XCTAssertEqual(invitation.ticketId, "0123456789abcdef0123456789abcdef")
        XCTAssertEqual(invitation.scopes, ["ownership.read", "project.read"])
    }

    func testRejectsUnknownFieldsExpiredTicketAndNonHubPath() throws {
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(extra: ["untrusted": true]), nowEpochMs: 1_950_000
        ))
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(), nowEpochMs: 2_000_000
        ))
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(extra: ["endpoint": "https://192.168.1.20:8443/bridge/v1/requests"]),
            nowEpochMs: 1_950_000
        ))
    }

    func testEnrollmentResponseYieldsOnlyCorrelatedCredential() throws {
        let invitation = try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(), nowEpochMs: 1_950_000
        )
        let response = BridgePairingEnrollmentResponse(
            version: 1,
            status: "ENROLLED",
            errorCode: nil,
            agentId: invitation.agentId,
            enrollmentId: invitation.ticketId,
            desktopHubId: "desktop-hub-0001",
            endpoint: invitation.endpoint,
            tlsCertificateSha256: invitation.certificateSHA256,
            sharedSecretBase64: Data(repeating: 0x5C, count: 32).base64EncodedString(),
            scopes: ["project.read"],
            enrolledAtEpochMs: 1_940_000,
            expiresAtEpochMs: 3_000_000
        )
        let credential = try response.credential(matching: invitation, nowEpochMs: 1_950_000)
        XCTAssertEqual(credential.deviceId, invitation.agentId)
        XCTAssertEqual(credential.hmacSecret, Data(repeating: 0x5C, count: 32))

        let escalated = BridgePairingEnrollmentResponse(
            version: 1, status: "ENROLLED", errorCode: nil,
            agentId: invitation.agentId, enrollmentId: invitation.ticketId,
            desktopHubId: "desktop-hub-0001", endpoint: invitation.endpoint,
            tlsCertificateSha256: invitation.certificateSHA256,
            sharedSecretBase64: Data(repeating: 0x5C, count: 32).base64EncodedString(),
            scopes: ["admin.root"], enrolledAtEpochMs: 1_940_000,
            expiresAtEpochMs: 3_000_000
        )
        XCTAssertThrowsError(try escalated.credential(matching: invitation, nowEpochMs: 1_950_000))
    }

    func testEnrollmentDecoderRejectsUnknownResponseFields() throws {
        let response: [String: Any] = [
            "version": 1, "status": "FAILED", "errorCode": "PAIRING_REJECTED",
            "agentId": NSNull(), "enrollmentId": NSNull(), "desktopHubId": NSNull(),
            "endpoint": NSNull(), "tlsCertificateSha256": NSNull(),
            "sharedSecretBase64": NSNull(), "scopes": [],
            "enrolledAtEpochMs": NSNull(), "expiresAtEpochMs": NSNull(),
            "unexpected": "value"
        ]
        XCTAssertThrowsError(try BridgePairingEnrollmentResponse.decodeStrict(
            JSONSerialization.data(withJSONObject: response)
        ))
    }

    func testTransportRequiresExactWindowsHubEndpointAndPin() {
        XCTAssertNoThrow(try PinnedBridgeHTTPSClient(origin: endpoint, certificateSHA256: pin))
        XCTAssertThrowsError(try PinnedBridgeHTTPSClient(
            origin: "https://192.168.1.20:8443", certificateSHA256: pin
        ))
        XCTAssertThrowsError(try PinnedBridgeHTTPSClient(
            origin: "http://192.168.1.20:8443/bridge/v1", certificateSHA256: pin
        ))
    }
}

private extension Data {
    func base64URLForTest() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}
