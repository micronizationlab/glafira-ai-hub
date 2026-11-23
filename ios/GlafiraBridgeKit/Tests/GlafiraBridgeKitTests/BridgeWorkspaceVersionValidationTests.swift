import XCTest
@testable import GlafiraBridgeKit

final class BridgeWorkspaceVersionValidationTests: XCTestCase {
    func testConversationAppendAcceptsCanonicalPositiveRemoteVersion() {
        XCTAssertNil(BridgeProtocol.validate(request(version: "1"), nowEpochMs: 1_050_000))
        XCTAssertEqual(
            BridgeProtocol.validate(request(version: "0"), nowEpochMs: 1_050_000),
            .invalidPayloadValue
        )
        XCTAssertEqual(
            BridgeProtocol.validate(request(version: "01"), nowEpochMs: 1_050_000),
            .invalidPayloadValue
        )
    }

    func testLegacyAppendEnvelopeStillValidatesBeforeServerCASAdmission() {
        XCTAssertNil(BridgeProtocol.validate(request(version: nil), nowEpochMs: 1_050_000))
    }

    private func request(version: String?) -> BridgeRequest {
        var payload = [
            "conversationId": "conversation-0001",
            "messageText": "Проверить состояние"
        ]
        if let version { payload["baseRemoteVersion"] = version }
        return BridgeRequest(
            requestId: "request-0001",
            idempotencyKey: "idem-000001",
            deviceId: "device-0001",
            projectId: "project-0001",
            operation: .conversationAppend,
            issuedAtEpochMs: 1_000_000,
            expiresAtEpochMs: 1_120_000,
            payload: payload
        )
    }
}
