import XCTest
@testable import GlafiraBridgeKit

final class BridgeHubBindingTests: XCTestCase {
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)
    private let enrolledAt: Int64 = 1_700_000_000_000
    private let oneHour: Int64 = 60 * 60 * 1_000

    private func binding(
        endpoint: String? = nil,
        pin: String? = nil,
        scopes: [String] = ["project.read", "ownership.read"],
        enrolledAt: Int64? = nil,
        expiresAt: Int64? = nil
    ) throws -> BridgeHubBinding {
        try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint ?? self.endpoint,
            certificateSHA256: pin ?? self.pin,
            scopes: scopes,
            enrolledAtEpochMs: enrolledAt ?? self.enrolledAt,
            expiresAtEpochMs: expiresAt ?? self.enrolledAt + oneHour
        )
    }

    func testAcceptsAndCanonicalizesWindowsHubBinding() throws {
        let value = try binding()
        XCTAssertEqual(value.scopes, ["ownership.read", "project.read"])
        XCTAssertEqual(value.endpoint, endpoint)
        XCTAssertEqual(value.certificateSHA256, pin)
        XCTAssertEqual(value.transportOrigin, "https://192.168.1.20:8443")
    }

    func testCanonicalizesSchemeAndHostAndProvidesTransportOrigin() throws {
        let value = try binding(endpoint: "HTTPS://192.168.1.20:8443/bridge/v1")
        XCTAssertEqual(value.endpoint, endpoint)
        XCTAssertEqual(value.transportOrigin, "https://192.168.1.20:8443")
    }

    func testAcceptsAndCanonicalizesIPv6UniqueLocalHubEndpoint() throws {
        let value = try binding(endpoint: "HTTPS://[FD12:3456:789A::1]:8443/bridge/v1")
        XCTAssertEqual(value.endpoint, "https://[fd12:3456:789a::1]:8443/bridge/v1")
        XCTAssertEqual(value.transportOrigin, "https://[fd12:3456:789a::1]:8443")
    }

    func testRejectsPublicLoopbackAndHostnameEndpoints() {
        for invalid in [
            "https://8.8.8.8:8443/bridge/v1",
            "https://127.0.0.1:8443/bridge/v1",
            "https://localhost:8443/bridge/v1",
            "https://example.com:8443/bridge/v1",
            "http://192.168.1.20:8443/bridge/v1"
        ] {
            XCTAssertThrowsError(try binding(endpoint: invalid), "Unexpectedly accepted (invalid)")
        }
    }

    func testRejectsWrongPathAndNoncanonicalPin() {
        XCTAssertThrowsError(try binding(endpoint: "https://192.168.1.20:8443/bridge/v1/requests"))
        XCTAssertThrowsError(try binding(pin: pin.uppercased()))
        let nonASCIIHexWith64UTF8Bytes = String(repeating: "a", count: 61) + "Ａ"
        XCTAssertEqual(nonASCIIHexWith64UTF8Bytes.utf8.count, 64)
        XCTAssertThrowsError(try binding(pin: nonASCIIHexWith64UTF8Bytes))
    }

    func testRejectsScopesOutsideWindowsEnrollmentPolicy() {
        XCTAssertThrowsError(try binding(scopes: ["desktop.task.delete"]))
        XCTAssertThrowsError(try binding(scopes: ["project.read", "project.read"]))
        XCTAssertThrowsError(try binding(scopes: []))
    }

    func testAcceptsWindowsSupportedScopes() throws {
        let supported = [
            "approval.request", "conversation.append", "conversation.read",
            "desktop.task.claim", "desktop.task.result", "ownership.read", "project.read"
        ]
        let value = try binding(scopes: supported)
        XCTAssertEqual(value.scopes, supported.sorted())
    }

    func testRejectsTimestampOutsideWindowsDateTimeOffsetRange() {
        XCTAssertThrowsError(try binding(enrolledAt: 253_402_300_800_000))
        XCTAssertThrowsError(try binding(expiresAt: 253_402_300_800_000))
    }

    func testRequiresWindowsCredentialLifetimeBetweenOneHourAnd365Days() throws {
        XCTAssertNoThrow(try binding(expiresAt: enrolledAt + oneHour))
        XCTAssertNoThrow(try binding(expiresAt: enrolledAt + 365 * 24 * 60 * 60 * 1_000))
        XCTAssertThrowsError(try binding(expiresAt: enrolledAt + oneHour - 1))
        XCTAssertThrowsError(try binding(expiresAt: enrolledAt + 365 * 24 * 60 * 60 * 1_000 + 1))
    }

    func testDecodingCannotBypassBindingValidation() throws {
        let invalid = """
        {
          "desktopHubId": "desktop-hub-0001",
          "endpoint": "https://8.8.8.8:8443/bridge/v1",
          "certificateSHA256": "(pin)",
          "scopes": ["project.read"],
          "enrolledAtEpochMs": (enrolledAt),
          "expiresAtEpochMs": (enrolledAt + oneHour)
        }
        """.data(using: .utf8)!
        XCTAssertThrowsError(try JSONDecoder().decode(BridgeHubBinding.self, from: invalid))
    }

    func testPersistenceRoundTripsCanonicalBinding() throws {
        let original = try binding(endpoint: "HTTPS://192.168.1.20:8443/bridge/v1")
        let encoded = try BridgeHubBindingPersistence.encode(original)
        XCTAssertLessThanOrEqual(encoded.count, BridgeHubBindingPersistence.maximumPayloadBytes)
        let decoded = try BridgeHubBindingPersistence.decode(encoded)
        XCTAssertEqual(decoded, original)
        XCTAssertEqual(decoded.endpoint, endpoint)
    }

    func testPersistenceRejectsUnknownEnvelopeAndBindingFields() throws {
        let encoded = try BridgeHubBindingPersistence.encode(binding())
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        envelope["unexpected"] = true
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope)))

        var nested = try XCTUnwrap(envelope["binding"] as? [String: Any])
        nested["unexpectedBindingField"] = "must-not-be-ignored"
        envelope.removeValue(forKey: "unexpected")
        envelope["binding"] = nested
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope)))
    }

    func testPersistenceRejectsOversizedAndTooDeepPayloads() throws {
        let oversized = Data(repeating: 0x20, count: BridgeHubBindingPersistence.maximumPayloadBytes + 1)
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(oversized))
        XCTAssertThrowsError(try BridgeHubBindingPersistence.validatePayloadSize(oversized))

        let nested = "{\"schemaVersion\":1,\"binding\":" +
            String(repeating: "[", count: BridgeHubBindingPersistence.maximumJSONNestingDepth + 1) +
            "0" +
            String(repeating: "]", count: BridgeHubBindingPersistence.maximumJSONNestingDepth + 1) +
            "}"
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(nested.utf8)))
    }

    func testPersistenceMapsMalformedJSONAndUnsupportedVersionToBindingError() throws {
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data("{".utf8)))
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(
            with: BridgeHubBindingPersistence.encode(binding())
        ) as? [String: Any])
        envelope["schemaVersion"] = 99
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope)))
    }

    func testPersistenceDecodeCanonicalizesEndpointBeforeReturning() throws {
        let encoded = try BridgeHubBindingPersistence.encode(binding())
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        var nested = try XCTUnwrap(envelope["binding"] as? [String: Any])
        nested["endpoint"] = "HTTPS://192.168.1.20:8443/bridge/v1"
        envelope["binding"] = nested
        let decoded = try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope))
        XCTAssertEqual(decoded.endpoint, endpoint)
        XCTAssertEqual(decoded.transportOrigin, "https://192.168.1.20:8443")
    }

}
