import Combine
import Foundation
import GlafiraBridgeKit

struct OwnershipPendingRow: Equatable, Identifiable, Sendable {
    let id: String
    let deviceId: String
    let projectId: String
    let enqueuedAtEpochMs: Int64
}

struct OwnershipProjectionRow: Equatable, Identifiable, Sendable {
    let id: String
    let projectId: String
    let taskId: String
    let ownerCoreId: String
    let ownerGeneration: Int64
    let stateVersion: Int64
    let updatedAtEpochMs: Int64
}

enum OwnershipSyncAttempt: Equatable, Sendable {
    case synchronized
    case unavailable
    case retryPending
    case blocked
}

/// Owner-facing read-side coordinator. It cannot enqueue transfers, sign ownership changes,
/// issue leases, or touch the conversation outbox. The Windows Hub remains authoritative.
@MainActor
final class OwnershipSyncViewModel: ObservableObject {
    typealias PairingLoader = (Int64) throws -> [MobileHubPairingState]
    typealias QueueSnapshot = () async throws -> [MobileOwnershipReadWork]
    typealias ProjectionSnapshot = () async -> [MobileOwnershipProjection]
    typealias Consume = (MobileHubPairingState, Int64) async throws -> MobileOwnershipReadOutcome

    @Published private(set) var pairings: [MobileHubPairingState] = []
    @Published private(set) var pending: [OwnershipPendingRow] = []
    @Published private(set) var projections: [OwnershipProjectionRow] = []
    @Published private(set) var attempts: [String: OwnershipSyncAttempt] = [:]
    @Published private(set) var syncingDeviceIds: Set<String> = []
    @Published private(set) var status = "Проверяем локальное состояние владения…"

    private let pairingLoader: PairingLoader
    private let queueSnapshot: QueueSnapshot
    private let projectionSnapshot: ProjectionSnapshot
    private let consume: Consume
    private let bootstrapFailure: Bool

    init() {
        do {
            let directory = try MobileOwnershipReadQueue.applicationSupportDirectory()
            let queue = try MobileOwnershipReadQueue(directory: directory)
            let syncStore = try MobileOwnershipSyncStore(directory: directory)
            let consumer = MobileOwnershipReadConsumer(
                queue: queue,
                syncStore: syncStore,
                credentialStore: KeychainBridgeCredentialStore()
            )
            pairingLoader = { nowEpochMs in
                try KeychainBridgeCredentialStore().pairingStates(nowEpochMs: nowEpochMs)
            }
            queueSnapshot = { try await queue.snapshot() }
            projectionSnapshot = { await syncStore.projections() }
            consume = { pairing, nowEpochMs in
                try await consumer.consumeNext(
                    pairingState: pairing,
                    nowEpochMs: nowEpochMs
                )
            }
            bootstrapFailure = false
        } catch {
            pairingLoader = { _ in [] }
            queueSnapshot = { [] }
            projectionSnapshot = { [] }
            consume = { _, _ in throw OwnershipSyncViewModelError.unavailable }
            bootstrapFailure = true
            status = "Локальное состояние владения недоступно"
        }
    }

    init(
        pairingLoader: @escaping PairingLoader,
        queueSnapshot: @escaping QueueSnapshot,
        projectionSnapshot: @escaping ProjectionSnapshot,
        consume: @escaping Consume
    ) {
        self.pairingLoader = pairingLoader
        self.queueSnapshot = queueSnapshot
        self.projectionSnapshot = projectionSnapshot
        self.consume = consume
        bootstrapFailure = false
    }

    func reload(
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async {
        guard !bootstrapFailure else { return }
        do {
            let loadedPairings = try pairingLoader(nowEpochMs)
            let work = try await queueSnapshot()
            let stored = await projectionSnapshot()
            pairings = loadedPairings
            pending = work.map {
                OwnershipPendingRow(
                    id: $0.id,
                    deviceId: $0.deviceId,
                    projectId: $0.projectId,
                    enqueuedAtEpochMs: $0.enqueuedAtEpochMs
                )
            }
            projections = stored.map {
                OwnershipProjectionRow(
                    id: "\($0.projectId)|\($0.taskId)",
                    projectId: $0.projectId,
                    taskId: $0.taskId,
                    ownerCoreId: $0.ownerCoreId,
                    ownerGeneration: $0.ownerGeneration,
                    stateVersion: $0.stateVersion,
                    updatedAtEpochMs: $0.updatedAtEpochMs
                )
            }
            status = statusText()
        } catch {
            pairings = []
            pending = []
            projections = []
            status = "Не удалось прочитать локальное состояние владения"
        }
    }

    func syncNext(
        deviceId: String,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async {
        guard !syncingDeviceIds.contains(deviceId),
              pending.contains(where: { $0.deviceId == deviceId }) else { return }
        guard let pairing = pairings.first(where: {
            $0.deviceId == deviceId && $0.evaluated(at: nowEpochMs).status == .paired
        }) else {
            attempts[deviceId] = .blocked
            status = "Повтор заблокирован: восстановите сопряжение с Windows Hub"
            return
        }

        syncingDeviceIds.insert(deviceId)
        defer { syncingDeviceIds.remove(deviceId) }
        do {
            let outcome = try await consume(pairing, nowEpochMs)
            let completedStatus: String?
            switch outcome {
            case .synchronized:
                attempts[deviceId] = .synchronized
                completedStatus = "Состояние владения проверено и сохранено"
            case .unavailable:
                attempts[deviceId] = .unavailable
                completedStatus = "Windows Hub подтвердил: перенос владения пока недоступен"
            case .noPendingWork:
                attempts.removeValue(forKey: deviceId)
                completedStatus = nil
            }
            await reload(nowEpochMs: nowEpochMs)
            if let completedStatus { status = completedStatus }
        } catch {
            // Consumer applies a verified projection before acknowledging the exact queue item.
            // Every failure therefore leaves durable work available for an explicit safe retry.
            attempts[deviceId] = .retryPending
            status = retryStatus(error)
        }
    }

    func pendingCount(deviceId: String) -> Int {
        pending.filter { $0.deviceId == deviceId }.count
    }

    private func statusText() -> String {
        if !pending.isEmpty {
            return "Ожидают безопасного чтения через Windows Hub: \(pending.count)"
        }
        if !projections.isEmpty {
            return "Проверенное состояние владения доступно офлайн"
        }
        return "Изменений владения для синхронизации нет"
    }

    private func retryStatus(_ error: Error) -> String {
        switch error as? MobileOwnershipReadConsumerError {
        case .invalidPairingState, .credentialBindingMismatch,
             .ownershipReadScopeMissing, .ownershipTrustAnchorMissing:
            return "Проверка доверия не пройдена; запись сохранена для повтора после нового сопряжения"
        case .invalidRequest:
            return "Запрос отклонён локальной проверкой; запись сохранена для безопасного повтора"
        case nil:
            return "Windows Hub недоступен; запись сохранена для безопасного повтора"
        }
    }
}

private enum OwnershipSyncViewModelError: Error {
    case unavailable
}
