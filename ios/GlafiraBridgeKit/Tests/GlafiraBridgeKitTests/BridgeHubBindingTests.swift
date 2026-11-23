import XCTest
@testable import GlafiraBridgeKit

final class BridgeHubBindingTests: XCTestCase {
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)

    func testAcceptsAndCanonicalizesWindowsHubBinding() throws {
        let binding = try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint,
            certificateSHA256: pin,
            scopes: ["project.read", "ownership.read"],
            enrolledAtEpochMs: 1_000,
            expiresAtEpochMs: 2_000
        )
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
}
