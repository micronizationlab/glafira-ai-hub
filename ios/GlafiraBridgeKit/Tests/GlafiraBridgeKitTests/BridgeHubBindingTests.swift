import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class BridgeHubBindingTests: XCTestCase {
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)

    private func binding() throws -> BridgeHubBinding {
        try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint,
            certificateSHA256: pin,
            scopes: ["project.read", "ownership.read"],
            enrolledAtEpochMs: 1_000,
            expiresAtEpochMs: 2_000
        )
    }

    func testAcceptsAndCanonicalizesWindowsHubBinding() throws {
        let binding = try binding()
        XCTAssertEqual(binding.scopes, ["ownership.read", "project.read"])
        XCTAssertEqual(binding.endpoint, endpoint)
        XCTAssertEqual(binding.certificateSHA256, pin)
    }

    func testRejectsWrongEndpointAndNoncanonicalPin() {
        XCTAssertThrowsError(try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: "https://192.168.1.20:8443/bridge/v1/requests",
            certificateSHA256: pin,
            scopes: ["project.read"],
            enrolledAtEpochMs: 1_000,
            expiresAtEpochMs: 2_000
        ))
        XCTAssertThrowsError(try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint,
            certificateSHA256: pin.uppercased(),
            scopes: ["project.read"],
            enrolledAtEpochMs: 1_000,
            expiresAtEpochMs: 2_000
        ))
    }

    func testRejectsDuplicateScopesAndInvalidLifetime() {
        XCTAssertThrowsError(try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint,
            certificateSHA256: pin,
            scopes: ["project.read", "project.read"],
            enrolledAtEpochMs: 1_000,
            expiresAtEpochMs: 2_000
        ))
        XCTAssertThrowsError(try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint,
            certificateSHA256: pin,
            scopes: ["project.read"],
            enrolledAtEpochMs: 2_000,
            expiresAtEpochMs: 2_000
        ))
    }

    func testPersistenceRoundTripRejectsUnknownFields() throws {
        let expected = try binding()
        let data = try BridgeHubBindingPersistence.encode(expected)
        XCTAssertEqual(try BridgeHubBindingPersistence.decode(data), expected)

        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object["unexpected"] = true
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(
            JSONSerialization.data(withJSONObject: object)
        ))
        object.removeValue(forKey: "unexpected")
        var nested = try XCTUnwrap(object["binding"] as? [String: Any])
        nested["unexpected"] = true
        object["binding"] = nested
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(
            JSONSerialization.data(withJSONObject: object)
        ))
    }
}
