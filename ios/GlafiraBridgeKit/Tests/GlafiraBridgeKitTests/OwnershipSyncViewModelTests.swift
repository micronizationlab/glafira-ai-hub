import XCTest
@testable import GlafiraBridgeKit
@testable import GlafiraIOSUI

@MainActor
final class OwnershipSyncViewModelTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000

    func testReloadPublishesOnlyOwnerSafeRowsAndKeepsExpiredWorkVisible() async throws {
        let work = pendingWork()
        let projection = ownershipProjection()
        let expired = try MobileHubPairingState.paired(
            deviceId: work.deviceId,
            desktopHubId: "desktop-hub-0001",
            endpoint: "https://hub.example.test:8443/bridge/v1",
            certificateSHA256: String(repeating: "a", count: 64),
            expiresAtEpochMs: now,
            nowEpochMs: now - 1
        )
        let model = OwnershipSyncViewModel(
            pairingLoader: { _ in [expired.evaluated(at: self.now)] },
            queueSnapshot: { [work] },
            projectionSnapshot: { [projection] },
            consume: { _, _ in XCTFail("Reload must not perform network"); return .noPendingWork }
        )

        await model.reload(nowEpochMs: now)

        XCTAssertEqual(model.pending.count, 1)
        XCTAssertEqual(model.projections.count, 1)
        XCTAssertEqual(model.pairings.first?.status, .expired)
        XCTAssertEqual(
            Set(Mirror(reflecting: model.pending[0]).children.compactMap(\.label)),
            ["id", "deviceId", "projectId", "enqueuedAtEpochMs"]
        )
        XCTAssertEqual(
            Set(Mirror(reflecting: model.projections[0]).children.compactMap(\.label)),
            ["id", "projectId", "taskId", "ownerCoreId", "ownerGeneration",
             "stateVersion", "updatedAtEpochMs"]
        )
    }

    func testTransportFailureLeavesDurableWorkMarkedForSafeRetry() async throws {
        let work = pendingWork()
        let pairing = try pairingState()
        let model = OwnershipSyncViewModel(
            pairingLoader: { _ in [pairing] },
            queueSnapshot: { [work] },
            projectionSnapshot: { [] },
            consume: { _, _ in throw TestFailure.transport }
        )
        await model.reload(nowEpochMs: now)

        await model.syncNext(deviceId: work.deviceId, nowEpochMs: now)

        XCTAssertEqual(model.pending.count, 1)
        XCTAssertEqual(model.attempts[work.deviceId], .retryPending)
        XCTAssertTrue(model.status.contains("сохранена"))
    }

    func testVerifiedOutcomeReloadsAppliedProjectionAndClearsPendingPresentation() async throws {
        let fixture = OwnershipFixture(work: pendingWork(), projection: ownershipProjection())
        let pairing = try pairingState()
        let model = OwnershipSyncViewModel(
            pairingLoader: { _ in [pairing] },
            queueSnapshot: { await fixture.workSnapshot() },
            projectionSnapshot: { await fixture.projectionSnapshot() },
            consume: { _, _ in try await fixture.consume() }
        )
        await model.reload(nowEpochMs: now)
        XCTAssertEqual(model.pending.count, 1)

        await model.syncNext(deviceId: "ios-device-0001", nowEpochMs: now)

        XCTAssertEqual(model.pending, [])
        XCTAssertEqual(model.projections.map(\.ownerCoreId), ["desktop-core-01"])
        XCTAssertEqual(model.attempts["ios-device-0001"], .synchronized)
        XCTAssertEqual(model.status, "Состояние владения проверено и сохранено")
    }

    private func pairingState() throws -> MobileHubPairingState {
        try .paired(
            deviceId: "ios-device-0001",
            desktopHubId: "desktop-hub-0001",
            endpoint: "https://hub.example.test:8443/bridge/v1",
            certificateSHA256: String(repeating: "a", count: 64),
            expiresAtEpochMs: now + 60_000,
            nowEpochMs: now
        )
    }

    private func pendingWork() -> MobileOwnershipReadWork {
        MobileOwnershipReadWork(
            id: "ios-ownership-read-" + String(repeating: "b", count: 64),
            deviceId: "ios-device-0001",
            projectId: "project-0001",
            transferId: "transfer-0001",
            enqueuedAtEpochMs: now
        )
    }

    private func ownershipProjection() -> MobileOwnershipProjection {
        MobileOwnershipProjection(
            taskId: "task-000001",
            projectId: "project-0001",
            ownerCoreId: "desktop-core-01",
            ownerGeneration: 8,
            stateVersion: 3,
            payloadSha256: String(repeating: "c", count: 64),
            idempotencyKey: "idempotency-0001",
            effectRisk: "READ_ONLY",
            approvalRef: nil,
            lastTransferId: "transfer-0001",
            lastNonce: "nonce-000001",
            updatedAtEpochMs: now
        )
    }
}

private actor OwnershipFixture {
    private var work: MobileOwnershipReadWork?
    private let projection: MobileOwnershipProjection

    init(work: MobileOwnershipReadWork, projection: MobileOwnershipProjection) {
        self.work = work
        self.projection = projection
    }

    func workSnapshot() -> [MobileOwnershipReadWork] {
        work.map { [$0] } ?? []
    }

    func projectionSnapshot() -> [MobileOwnershipProjection] {
        work == nil ? [projection] : []
    }

    func consume() throws -> MobileOwnershipReadOutcome {
        guard let current = work else { return .noPendingWork }
        work = nil
        return .synchronized(
            current,
            MobileOwnershipSyncResult(disposition: .applied, projection: projection)
        )
    }
}

private enum TestFailure: Error {
    case transport
}
