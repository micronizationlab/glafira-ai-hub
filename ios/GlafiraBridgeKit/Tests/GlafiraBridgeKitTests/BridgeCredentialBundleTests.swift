import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class BridgeCredentialBundleTests: XCTestCase {
    private let origin = "https://192.168.1.20:47321"
    private let pin = String(repeating: "a", count: 64)
    private let secret = Data(repeating: 0x5C, count: 32)

    private func bundle(
        origin: String? = nil,
        pin: String? = nil,
        scopes: [String] = ["project.read"],
        secret: Data? = nil,
        extra: [String: Any] = [:]
    ) throws -> Data {
        var object: [String: Any] = [
            "format": "GLAFIRA_BRIDGE_CREDENTIAL_V1",
            "origin": origin ?? self.origin,
            "approvalId": "approval-1234567890",
            "ticketId": "ticket-1234567890",
            "deviceId": "ios-device-0001",
            "hmacSecretBase64Url": (secret ?? self.secret).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: ""),
            "grantedScopes": scopes,
            "serverCertificateSha256": pin ?? self.pin
        ]
        extra.forEach { object[$0.key] = $0.value }
        return try JSONSerialization.data(withJSONObject: object)
    }

    func testParsesExactAndroidOwnerBundle() throws {
        let parsed = try OwnerApprovedBridgeCredentialBundle.parse(
            bundle(), expectedOrigin: origin,
            expectedCertificateSHA256: pin,
            expectedApprovalId: "approval-1234567890",
            expectedTicketId: "ticket-1234567890",
            requestedScopes: ["project.read", "conversation.read"]
        )
        XCTAssertEqual(parsed.origin, origin)
        XCTAssertEqual(parsed.deviceId, "ios-device-0001")
        XCTAssertEqual(parsed.hmacSecret, secret)
        XCTAssertEqual(parsed.grantedScopes, ["project.read"])
    }

    func testRejectsOriginOrCertificatePinMismatch() throws {
        XCTAssertThrowsError(try OwnerApprovedBridgeCredentialBundle.parse(
            bundle(), expectedOrigin: "https://192.168.1.21:47321",
            expectedCertificateSHA256: pin,
            expectedApprovalId: "approval-1234567890", expectedTicketId: "ticket-1234567890", requestedScopes: ["project.read"]
        ))
        XCTAssertThrowsError(try OwnerApprovedBridgeCredentialBundle.parse(
            bundle(), expectedOrigin: origin,
            expectedCertificateSHA256: String(repeating: "b", count: 64),
            expectedApprovalId: "approval-1234567890", expectedTicketId: "ticket-1234567890",
            requestedScopes: ["project.read"]
        ))
    }

    func testRejectsScopeEscalationAndDuplicates() throws {
        XCTAssertThrowsError(try OwnerApprovedBridgeCredentialBundle.parse(
            bundle(scopes: ["admin.root"]), expectedOrigin: origin,
            expectedCertificateSHA256: pin, expectedApprovalId: "approval-1234567890", expectedTicketId: "ticket-1234567890", requestedScopes: ["project.read"]
        ))
        XCTAssertThrowsError(try OwnerApprovedBridgeCredentialBundle.parse(
            bundle(scopes: ["project.read", "project.read"]), expectedOrigin: origin,
            expectedCertificateSHA256: pin, requestedScopes: ["project.read"]
        ))
    }

    func testRejectsMalformedSecretAndUnknownFields() throws {
        XCTAssertThrowsError(try OwnerApprovedBridgeCredentialBundle.parse(
            bundle(secret: Data(repeating: 0x01, count: 31)),
            expectedOrigin: origin, expectedCertificateSHA256: pin,
            expectedApprovalId: "approval-1234567890", expectedTicketId: "ticket-1234567890",
            requestedScopes: ["project.read"]
        ))
        XCTAssertThrowsError(try OwnerApprovedBridgeCredentialBundle.parse(
            bundle(extra: ["unexpected": "value"]),
            expectedOrigin: origin, expectedCertificateSHA256: pin,
            expectedApprovalId: "approval-1234567890", expectedTicketId: "ticket-1234567890",
            requestedScopes: ["project.read"]
        ))
    }
}
