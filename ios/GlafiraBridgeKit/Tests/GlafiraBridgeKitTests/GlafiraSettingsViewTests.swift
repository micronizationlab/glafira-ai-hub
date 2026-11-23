import XCTest
@testable import GlafiraBridgeKit
@testable import GlafiraIOSUI

final class GlafiraSettingsViewTests: XCTestCase {
    func testPairedDeviceExposesNonSecretHubStatusAndAbbreviatedPin() throws {
        let state = try MobileHubPairingState.paired(
            deviceId: "ios-device-0001",
            desktopHubId: "desktop-hub-0001",
            endpoint: "https://192.168.1.20:8443/bridge/v1",
            certificateSHA256: String(repeating: "a", count: 32) + String(repeating: "b", count: 32),
            expiresAtEpochMs: 2_000_000,
            nowEpochMs: 1_900_000
        )

        let row = GlafiraPairingSettingsRow(state)

        XCTAssertEqual(row.deviceId, "ios-device-0001")
        XCTAssertEqual(row.desktopHubId, "desktop-hub-0001")
        XCTAssertEqual(row.endpoint, "https://192.168.1.20:8443/bridge/v1")
        XCTAssertEqual(row.certificatePinSummary, "aaaaaaaa…bbbbbbbb")
        XCTAssertEqual(row.statusTitle, "Подключено")
        XCTAssertFalse(row.requiresAttention)
    }

    func testExpiredAndRevokedDevicesAreCountedAsRequiringAttention() throws {
        let expired = try MobileHubPairingState.paired(
            deviceId: "ios-device-expired",
            desktopHubId: "desktop-hub-0001",
            endpoint: "https://192.168.1.20:8443/bridge/v1",
            certificateSHA256: String(repeating: "c", count: 64),
            expiresAtEpochMs: 1_800_000,
            nowEpochMs: 1_900_000
        )
        let revoked = MobileHubPairingState.disconnected(
            .revoked,
            deviceId: "ios-device-revoked"
        )

        let summary = GlafiraSettingsSummary(
            pairingStates: [expired, revoked],
            pendingOperationCount: 3,
            conflictCount: 1
        )

        XCTAssertEqual(summary.activePairingCount, 0)
        XCTAssertEqual(summary.attentionPairingCount, 2)
        XCTAssertEqual(summary.pendingOperationCount, 3)
        XCTAssertEqual(summary.conflictCount, 1)
        XCTAssertEqual(GlafiraPairingSettingsRow(expired).statusTitle, "Срок credential истёк")
        XCTAssertEqual(GlafiraPairingSettingsRow(revoked).statusTitle, "Локально отозвано")
    }

    func testInvalidShortPinIsNeverRenderedAsTrustedFingerprint() {
        let invalid = MobileHubPairingState.disconnected(.invalid)

        let row = GlafiraPairingSettingsRow(invalid)

        XCTAssertNil(row.certificatePinSummary)
        XCTAssertEqual(row.statusTitle, "Ошибка состояния")
        XCTAssertTrue(row.requiresAttention)
    }
}
