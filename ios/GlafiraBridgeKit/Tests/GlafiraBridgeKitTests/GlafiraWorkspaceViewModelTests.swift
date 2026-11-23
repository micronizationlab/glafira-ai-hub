import XCTest
@testable import GlafiraBridgeKit
@testable import GlafiraIOSUI

@MainActor
final class GlafiraWorkspaceViewModelTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000

    func testReloadPublishesPairingAndPendingWithoutSending() async throws {
        let fixture = WorkspaceViewModelFixture(summary: summary(pending: 1, conflicts: 0))
        let pairing = try pairingState()
        let model = GlafiraWorkspaceViewModel(
            pairingLoader: { _ in [pairing] },
            projectSnapshot: { await fixture.snapshot() },
            consume: { _, _ in XCTFail("Reload must not send"); return .noPendingWork }
        )

        await model.reload(nowEpochMs: now)

        XCTAssertEqual(model.pairings, [pairing])
        XCTAssertEqual(model.pendingCount, 1)
        XCTAssertEqual(model.status, "Ожидают явной отправки владельцем: 1")
        let consumeCount = await fixture.consumeCount()
        XCTAssertEqual(consumeCount, 0)
    }

    func testOwnerTriggeredSuccessConsumesExactlyOneAndRefreshesSummary() async throws {
        let pairing = try pairingState()
        let entry = outboxEntry()
        let fixture = WorkspaceViewModelFixture(summary: summary(pending: 1, conflicts: 0))
        let model = GlafiraWorkspaceViewModel(
            pairingLoader: { _ in [pairing] },
            projectSnapshot: { await fixture.snapshot() },
            consume: { suppliedPairing, suppliedNow in
                XCTAssertEqual(suppliedPairing, pairing)
                XCTAssertEqual(suppliedNow, self.now)
                return await fixture.synchronize(entry: entry)
            }
        )
        await model.reload(nowEpochMs: now)

        await model.syncNext(deviceId: "ios-device-0001", nowEpochMs: now)

        let consumeCount = await fixture.consumeCount()
        XCTAssertEqual(consumeCount, 1)
        XCTAssertEqual(model.pendingCount, 0)
        XCTAssertEqual(model.attempts["ios-device-0001"], .synchronized)
        XCTAssertEqual(model.status, "Следующее сообщение подтверждено Windows Hub")
        XCTAssertFalse(model.syncingDeviceIds.contains("ios-device-0001"))
    }

    func testConflictIsRefreshedAndPresentedForOwnerResolution() async throws {
        let pairing = try pairingState()
        let entry = outboxEntry()
        let fixture = WorkspaceViewModelFixture(summary: summary(pending: 1, conflicts: 0))
        let model = GlafiraWorkspaceViewModel(
            pairingLoader: { _ in [pairing] },
            projectSnapshot: { await fixture.snapshot() },
            consume: { _, _ in await fixture.conflict(entry: entry) }
        )
        await model.reload(nowEpochMs: now)

        await model.syncNext(deviceId: "ios-device-0001", nowEpochMs: now)

        XCTAssertEqual(model.pendingCount, 0)
        XCTAssertEqual(model.conflictCount, 1)
        XCTAssertEqual(model.attempts["ios-device-0001"], .requiresOwnerResolution)
        XCTAssertEqual(
            model.status,
            "Конфликт версии: откройте диалог и выберите решение владельца"
        )
    }

    func testExpiredPairingBlocksBeforeConsumerAndKeepsPending() async throws {
        let active = try pairingState()
        let expired = active.evaluated(at: now + 60_001)
        let fixture = WorkspaceViewModelFixture(summary: summary(pending: 1, conflicts: 0))
        let model = GlafiraWorkspaceViewModel(
            pairingLoader: { _ in [expired] },
            projectSnapshot: { await fixture.snapshot() },
            consume: { _, _ in XCTFail("Expired pairing must block before send"); return .noPendingWork }
        )
        await model.reload(nowEpochMs: now + 60_001)

        await model.syncNext(deviceId: "ios-device-0001", nowEpochMs: now + 60_001)

        let consumeCount = await fixture.consumeCount()
        XCTAssertEqual(consumeCount, 0)
        XCTAssertEqual(model.pendingCount, 1)
        XCTAssertEqual(model.attempts["ios-device-0001"], .blocked)
        XCTAssertTrue(model.status.contains("восстановите сопряжение"))
    }

    func testTransportFailureKeepsPendingForExplicitRetry() async throws {
        let pairing = try pairingState()
        let fixture = WorkspaceViewModelFixture(summary: summary(pending: 1, conflicts: 0))
        let model = GlafiraWorkspaceViewModel(
            pairingLoader: { _ in [pairing] },
            projectSnapshot: { await fixture.snapshot() },
            consume: { _, _ in try await fixture.failTransport() }
        )
        await model.reload(nowEpochMs: now)

        await model.syncNext(deviceId: "ios-device-0001", nowEpochMs: now)

        let consumeCount = await fixture.consumeCount()
        XCTAssertEqual(consumeCount, 1)
        XCTAssertEqual(model.pendingCount, 1)
        XCTAssertEqual(model.attempts["ios-device-0001"], .retryPending)
        XCTAssertEqual(
            model.status,
            "Windows Hub недоступен; сообщение сохранено для явного повтора"
        )
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

    private func summary(pending: Int, conflicts: Int) -> MobileWorkspaceProjectSummary {
        MobileWorkspaceProjectSummary(
            id: "project-0001",
            name: "Project One",
            revision: "7",
            conversationCount: 1,
            pendingCount: pending,
            conflictCount: conflicts
        )
    }

    private func outboxEntry() -> MobileOutboxEntry {
        MobileOutboxEntry(
            operationId: "ios-message-0001",
            projectId: "project-0001",
            conversationId: "conversation-0001",
            text: "hello",
            baseRemoteVersion: "7",
            createdAtEpochMs: now,
            state: .pending
        )
    }
}

private actor WorkspaceViewModelFixture {
    private var summary: MobileWorkspaceProjectSummary
    private var consumed = 0

    init(summary: MobileWorkspaceProjectSummary) {
        self.summary = summary
    }

    func snapshot() -> [MobileWorkspaceProjectSummary] {
        [summary]
    }

    func consumeCount() -> Int {
        consumed
    }

    func synchronize(entry: MobileOutboxEntry) -> MobileWorkspaceOutboxOutcome {
        consumed += 1
        summary = MobileWorkspaceProjectSummary(
            id: summary.id,
            name: summary.name,
            revision: summary.revision,
            conversationCount: summary.conversationCount,
            pendingCount: 0,
            conflictCount: 0
        )
        return .synchronized(entry, newRemoteVersion: "8", duplicate: false)
    }

    func conflict(entry: MobileOutboxEntry) -> MobileWorkspaceOutboxOutcome {
        consumed += 1
        summary = MobileWorkspaceProjectSummary(
            id: summary.id,
            name: summary.name,
            revision: summary.revision,
            conversationCount: summary.conversationCount,
            pendingCount: 0,
            conflictCount: 1
        )
        return .requiresOwnerResolution(entry, currentRemoteVersion: "8")
    }

    func failTransport() throws -> MobileWorkspaceOutboxOutcome {
        consumed += 1
        throw WorkspaceViewModelTestError.transport
    }
}

private enum WorkspaceViewModelTestError: Error {
    case transport
}
