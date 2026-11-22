import XCTest
@testable import GlafiraBridgeKit

final class MobileOwnershipReadQueueTests: XCTestCase {
    private var directory: URL!
    private let now: Int64 = 1_800_000_000_000

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    func testOnlyPairedStateEnqueuesAndExpiryRecheckBlocksPendingNetwork() async throws {
        let queue = try MobileOwnershipReadQueue(directory: directory)
        let paired = try state(expiresAt: now + 60_000)
        let result = try await queue.schedule(
            pairingState: paired,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now
        )
        guard case .enqueued(let work) = result else { return XCTFail("Expected enqueue") }
        XCTAssertFalse(work.id.contains("project-0001"))
        XCTAssertFalse(work.id.contains("transfer-0001"))
        let pending = try await queue.pending(pairingState: paired, nowEpochMs: now)
        let expiredPending = try await queue.pending(
            pairingState: paired,
            nowEpochMs: now + 60_000
        )
        let ownerFacingSnapshot = try await queue.snapshot(deviceId: "ios-device-0001")
        XCTAssertEqual(pending, [work])
        XCTAssertEqual(expiredPending, [])
        XCTAssertEqual(ownerFacingSnapshot, [work])
        await XCTAssertThrowsErrorAsync {
            _ = try await queue.snapshot(deviceId: "bad")
        } verify: { error in
            XCTAssertEqual(error as? MobileOwnershipReadQueueError, .invalidIdentifier)
        }

        for status in [
            MobileHubPairingStatus.unpaired, .revoked, .invalid
        ] {
            let disconnected = MobileHubPairingState.disconnected(
                status, deviceId: "ios-device-0001"
            )
            XCTAssertEqual(disconnected.deviceId, "ios-device-0001")
            let blocked = try await queue.schedule(
                pairingState: disconnected,
                projectId: "project-0002",
                transferId: "transfer-0002",
                nowEpochMs: now
            )
            XCTAssertEqual(blocked, .blocked(status))
        }
    }

    func testQueueIsDurableIdempotentAndRejectsCorruptPersistedState() async throws {
        let paired = try state(expiresAt: now + 60_000)
        let first = try MobileOwnershipReadQueue(directory: directory)
        let enqueued = try await first.schedule(
            pairingState: paired,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now
        )
        guard case .enqueued(let work) = enqueued else { return XCTFail("Expected enqueue") }
        let duplicate = try await first.schedule(
            pairingState: paired,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now + 1
        )
        XCTAssertEqual(duplicate, .alreadyQueued(work))

        let reopened = try MobileOwnershipReadQueue(directory: directory)
        let reopenedPending = try await reopened.pending(pairingState: paired, nowEpochMs: now)
        XCTAssertEqual(reopenedPending, [work])

        let file = directory.appendingPathComponent("mobile-ownership-read-queue-v1.json")
        try Data("{\"schemaVersion\":1,\"entries\":[],\"extra\":true}".utf8)
            .write(to: file, options: .atomic)
        XCTAssertThrowsError(try MobileOwnershipReadQueue(directory: directory)) { error in
            XCTAssertEqual(error as? MobileOwnershipReadQueueError, .invalidPersistedState)
        }
    }

    func testScopedCancellationDoesNotTouchAnotherDeviceQueue() async throws {
        let firstState = try state(deviceId: "ios-device-0001", expiresAt: now + 60_000)
        let secondState = try state(deviceId: "ios-device-0002", expiresAt: now + 60_000)
        let queue = try MobileOwnershipReadQueue(directory: directory)
        _ = try await queue.schedule(
            pairingState: firstState,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now
        )
        _ = try await queue.schedule(
            pairingState: secondState,
            projectId: "project-0002",
            transferId: "transfer-0002",
            nowEpochMs: now + 1
        )

        try await queue.cancelAll(deviceId: "ios-device-0001")

        let firstPending = try await queue.pending(pairingState: firstState, nowEpochMs: now + 2)
        let secondPending = try await queue.pending(pairingState: secondState, nowEpochMs: now + 2)
        XCTAssertEqual(firstPending, [])
        XCTAssertEqual(secondPending.count, 1)
    }

    func testCompletionIsExactIdempotentAndCannotRemoveReenqueuedWork() async throws {
        let paired = try state(expiresAt: now + 60_000)
        let queue = try MobileOwnershipReadQueue(directory: directory)
        let scheduled = try await queue.schedule(
            pairingState: paired,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now
        )
        guard case .enqueued(let first) = scheduled else { return XCTFail("Expected enqueue") }
        try await queue.complete(first)
        try await queue.complete(first)

        let rescheduled = try await queue.schedule(
            pairingState: paired,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now + 1
        )
        guard case .enqueued(let second) = rescheduled else { return XCTFail("Expected re-enqueue") }
        XCTAssertEqual(first.id, second.id)
        XCTAssertNotEqual(first.enqueuedAtEpochMs, second.enqueuedAtEpochMs)

        try await queue.complete(first)
        let pending = try await queue.pending(pairingState: paired, nowEpochMs: now + 2)
        XCTAssertEqual(pending, [second])
    }

    private func state(
        deviceId: String = "ios-device-0001",
        expiresAt: Int64
    ) throws -> MobileHubPairingState {
        try .paired(
            deviceId: deviceId,
            desktopHubId: "desktop-hub-0001",
            endpoint: "https://hub.example.test:8443/bridge/v1",
            certificateSHA256: String(repeating: "a", count: 64),
            expiresAtEpochMs: expiresAt,
            nowEpochMs: now
        )
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: () async throws -> T,
    verify: (Error) -> Void
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw")
    } catch {
        verify(error)
    }
}
