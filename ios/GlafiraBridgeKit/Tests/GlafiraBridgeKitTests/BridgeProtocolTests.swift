import CryptoKit
import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class BridgeProtocolTests: XCTestCase {
    private func request(
        operation: BridgeOperation = .projectRead,
        payload: [String: String] = [:],
        issuedAt: Int64 = 1_000_000,
        expiresAt: Int64 = 1_120_000
    ) -> BridgeRequest {
        BridgeRequest(
            requestId: "request-0001",
            idempotencyKey: "idem-000001",
            deviceId: "device-0001",
            projectId: "project-001",
            operation: operation,
            issuedAtEpochMs: issuedAt,
            expiresAtEpochMs: expiresAt,
            payload: payload
        )
    }

    func testKotlinGoldenHMACVector() throws {
        let secret = Data((0..<32).map(UInt8.init))
        let signed = try BridgeProtocol.sign(request(), secret: secret)
        XCTAssertEqual(
            signed.signatureHex,
            "537850d376d0f6fc202a414619c83a99902ea36d262d29009f0bc1d6b15bb741"
        )
        XCTAssertTrue(BridgeProtocol.verifySignature(signed, secret: secret))
    }

    func testSignatureRejectsTamperingAndShortSecret() throws {
        let secret = Data((0..<32).map(UInt8.init))
        let signed = try BridgeProtocol.sign(request(), secret: secret)
        var altered = signed
        altered.projectId = "project-002"
        XCTAssertFalse(BridgeProtocol.verifySignature(altered, secret: secret))
        XCTAssertFalse(BridgeProtocol.verifySignature(signed, secret: Data(repeating: 0, count: 16)))
    }

    func testAcceptsProjectReadAndRejectsUnsupportedVersion() {
        XCTAssertNil(BridgeProtocol.validate(request(), nowEpochMs: 1_050_000))
        var unsupported = request()
        unsupported = BridgeRequest(
            protocolVersion: 2,
            requestId: unsupported.requestId,
            idempotencyKey: unsupported.idempotencyKey,
            deviceId: unsupported.deviceId,
            projectId: unsupported.projectId,
            operation: unsupported.operation,
            issuedAtEpochMs: unsupported.issuedAtEpochMs,
            expiresAtEpochMs: unsupported.expiresAtEpochMs
        )
        XCTAssertEqual(BridgeProtocol.validate(unsupported, nowEpochMs: 1_050_000), .unsupportedVersion)
    }

    func testRejectsExpiredAndOverlongRequests() {
        XCTAssertEqual(
            BridgeProtocol.validate(request(expiresAt: 1_040_000), nowEpochMs: 1_040_000),
            .expiredRequest
        )
        XCTAssertEqual(
            BridgeProtocol.validate(request(expiresAt: 1_400_001), nowEpochMs: 1_050_000),
            .invalidTimeWindow
        )
    }

    func testOperationPayloadAllowlistAndRequiredFields() {
        let unknown = request(payload: ["shellCommand": "not-allowed"])
        XCTAssertEqual(BridgeProtocol.validate(unknown, nowEpochMs: 1_050_000), .unknownPayloadField)

        let missing = request(operation: .conversationAppend, payload: ["conversationId": "conversation-01"])
        XCTAssertEqual(BridgeProtocol.validate(missing, nowEpochMs: 1_050_000), .missingPayloadField)

        let valid = request(
            operation: .conversationAppend,
            payload: [
                "conversationId": "conversation-01",
                "messageText": "Проверь статус проекта"
            ]
        )
        XCTAssertNil(BridgeProtocol.validate(valid, nowEpochMs: 1_050_000))
    }

    func testUTF16LengthMatchesKotlinForSupplementaryCharacters() {
        let value = String(repeating: "😀", count: 8_193)
        let tooLarge = request(
            operation: .conversationRead,
            payload: ["conversationId": value]
        )
        XCTAssertEqual(BridgeProtocol.validate(tooLarge, nowEpochMs: 1_050_000), .payloadTooLarge)
    }

    func testRejectsMalformedIdentifiersAndBlankValues() {
        let malformed = BridgeRequest(
            requestId: "request-0001",
            idempotencyKey: "idem-000001",
            deviceId: "bad id",
            projectId: "project-001",
            operation: .projectRead,
            issuedAtEpochMs: 1_000_000,
            expiresAtEpochMs: 1_120_000
        )
        XCTAssertEqual(BridgeProtocol.validate(malformed, nowEpochMs: 1_050_000), .invalidIdentifier)

        let blank = request(
            operation: .conversationRead,
            payload: ["conversationId": "  "]
        )
        XCTAssertEqual(BridgeProtocol.validate(blank, nowEpochMs: 1_050_000), .invalidPayloadValue)
    }
}
