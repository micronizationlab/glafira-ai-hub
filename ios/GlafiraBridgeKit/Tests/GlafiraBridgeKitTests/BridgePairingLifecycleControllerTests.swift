import XCTest
@testable import GlafiraBridgeKit
@testable import GlafiraIOSUI

final class BridgePairingLifecycleControllerTests: XCTestCase {
    func testUnpairAndRevokeRouteToCredentialStoreAndCancelOnlySelectedDeviceQueue() async throws {
        let paired = try MobileHubPairingState.paired(
            deviceId: "ios-device-0001",
            desktopHubId: "desktop-hub-0001",
            endpoint: "https://192.168.1.20:8443/bridge/v1",
            certificateSHA256: String(repeating: "a", count: 64),
            expiresAtEpochMs: 2_000_000,
            nowEpochMs: 1_900_000
        )
        let store = FakeCredentialLifecycleStore(states: [paired])
        let canceller = RecordingQueueCanceller()
        let controller = BridgePairingLifecycleController(
            store: store,
            queueCanceller: canceller
        )

        XCTAssertEqual(try controller.pairingStates(nowEpochMs: 1_900_000), [paired])
        let unpairResult = await controller.perform(.unpair, deviceId: "ios-device-0001")
        let revokeResult = await controller.perform(.revoke, deviceId: "ios-device-0002")
        let cancelledDeviceIds = await canceller.cancelledDeviceIds()
        XCTAssertEqual(unpairResult, .completed)
        XCTAssertEqual(revokeResult, .completed)
        XCTAssertEqual(store.actions, [
            "unpair:ios-device-0001",
            "revoke:ios-device-0002"
        ])
        XCTAssertEqual(cancelledDeviceIds, ["ios-device-0001", "ios-device-0002"])
    }

    func testCredentialFailureStillCancelsOwnershipQueueFailClosed() async {
        let store = FakeCredentialLifecycleStore(states: [], shouldFail: true)
        let canceller = RecordingQueueCanceller()
        let controller = BridgePairingLifecycleController(
            store: store,
            queueCanceller: canceller
        )

        let result = await controller.perform(.revoke, deviceId: "ios-device-0001")
        let cancelledDeviceIds = await canceller.cancelledDeviceIds()
        XCTAssertEqual(result, .credentialChangeFailed)
        XCTAssertEqual(cancelledDeviceIds, ["ios-device-0001"])
    }

    func testCredentialAndQueueFailureIsReportedWithoutTouchingConversationOutbox() async {
        let store = FakeCredentialLifecycleStore(states: [], shouldFail: true)
        let canceller = RecordingQueueCanceller(shouldFail: true)
        let controller = BridgePairingLifecycleController(
            store: store,
            queueCanceller: canceller
        )

        let result = await controller.perform(.unpair, deviceId: "ios-device-0001")

        XCTAssertEqual(result, .credentialAndQueueCancellationFailed)
    }
}

private final class FakeCredentialLifecycleStore: BridgePairingCredentialLifecycleStore {
    private let states: [MobileHubPairingState]
    private let shouldFail: Bool
    private(set) var actions: [String] = []

    init(states: [MobileHubPairingState], shouldFail: Bool = false) {
        self.states = states
        self.shouldFail = shouldFail
    }

    func pairingStates(nowEpochMs: Int64) throws -> [MobileHubPairingState] {
        states.map { $0.evaluated(at: nowEpochMs) }
    }

    func unpair(deviceId: String, ownerConfirmationReason: String) async throws {
        actions.append("unpair:\(deviceId)")
        if shouldFail { throw FakeFailure.rejected }
    }

    func localRevoke(deviceId: String, ownerConfirmationReason: String) async throws {
        actions.append("revoke:\(deviceId)")
        if shouldFail { throw FakeFailure.rejected }
    }
}

private actor RecordingQueueCanceller: BridgeOwnershipReadQueueCancelling {
    private var deviceIds: [String] = []
    private let shouldFail: Bool

    init(shouldFail: Bool = false) {
        self.shouldFail = shouldFail
    }

    func cancelAll(deviceId: String) async throws {
        if shouldFail { throw FakeFailure.rejected }
        deviceIds.append(deviceId)
    }

    func cancelledDeviceIds() -> [String] {
        deviceIds
    }
}

private enum FakeFailure: Error {
    case rejected
}
