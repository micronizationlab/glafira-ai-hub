import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class BridgeCredentialGrantTests: XCTestCase {
    private let secret = Data(repeating: 0x6B, count: 32)

    private func grant(
        approvalId: String = "approval-0001",
        ticketId: String = "ticket-00000001",
        scopes: [String] = ["project.read"],
        secret: Data? = nil,
        issuedAt: Int64 = 1_950_000,
        expiresAt: Int64 = 2_200_000
    ) -> OwnerApprovedBridgeCredentialGrant {
        OwnerApprovedBridgeCredentialGrant(
            approvalId: approvalId,
            ticketId: ticketId,
            deviceId: "ios-device-0001",
            hmacSecretBase64URL: (secret ?? self.secret).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: ""),
            approvedScopes: scopes,
            issuedAtEpochMs: issuedAt,
            expiresAtEpochMs: expiresAt
        )
    }

    func testValidOwnerGrantProducesCredentialWithoutPersistingIt() throws {
        let credential = try BridgeCredentialGrantImporter.validate(
            grant(), expectedApprovalId: "approval-0001",
            expectedTicketId: "ticket-00000001",
            requestedScopes: ["project.read", "conversation.read"],
            nowEpochMs: 2_000_000
        )
        XCTAssertEqual(credential.deviceId, "ios-device-0001")
        XCTAssertEqual(credential.hmacSecret, secret)
    }

    func testGrantMustMatchOwnerApprovalAndPairingTicket() {
        XCTAssertThrowsError(try BridgeCredentialGrantImporter.validate(
            grant(), expectedApprovalId: "different-approval",
            expectedTicketId: "ticket-00000001",
            requestedScopes: ["project.read"], nowEpochMs: 2_000_000
        ))
        XCTAssertThrowsError(try BridgeCredentialGrantImporter.validate(
            grant(), expectedApprovalId: "approval-0001",
            expectedTicketId: "different-ticket",
            requestedScopes: ["project.read"], nowEpochMs: 2_000_000
        ))
    }

    func testGrantCannotEscalateScopesOrDuplicateThem() {
        XCTAssertThrowsError(try BridgeCredentialGrantImporter.validate(
            grant(scopes: ["admin.root"]), expectedApprovalId: "approval-0001",
            expectedTicketId: "ticket-00000001",
            requestedScopes: ["project.read"], nowEpochMs: 2_000_000
        ))
        XCTAssertThrowsError(try BridgeCredentialGrantImporter.validate(
            grant(scopes: ["project.read", "project.read"]), expectedApprovalId: "approval-0001",
            expectedTicketId: "ticket-00000001",
            requestedScopes: ["project.read"], nowEpochMs: 2_000_000
        ))
    }

    func testGrantRejectsExpiredOrOverlongCredential() {
        XCTAssertThrowsError(try BridgeCredentialGrantImporter.validate(
            grant(expiresAt: 1_999_999), expectedApprovalId: "approval-0001",
            expectedTicketId: "ticket-00000001",
            requestedScopes: ["project.read"], nowEpochMs: 2_000_000
        ))
        XCTAssertThrowsError(try BridgeCredentialGrantImporter.validate(
            grant(expiresAt: 3_000_001), expectedApprovalId: "approval-0001",
            expectedTicketId: "ticket-00000001",
            requestedScopes: ["project.read"], nowEpochMs: 2_000_000
        ))
    }

    func testGrantRejectsShortHmacSecret() {
        XCTAssertThrowsError(try BridgeCredentialGrantImporter.validate(
            grant(secret: Data(repeating: 0x01, count: 31)),
            expectedApprovalId: "approval-0001",
            expectedTicketId: "ticket-00000001",
            requestedScopes: ["project.read"], nowEpochMs: 2_000_000
        ))
    }
}
