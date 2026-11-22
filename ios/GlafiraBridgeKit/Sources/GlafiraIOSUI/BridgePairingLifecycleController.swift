import Foundation
import GlafiraBridgeKit

enum BridgeLocalPairingAction: Equatable, Sendable {
    case unpair
    case revoke
}

enum BridgeLocalPairingActionResult: Equatable, Sendable {
    case completed
    case credentialChangeFailed
    case queueCancellationFailed
    case credentialAndQueueCancellationFailed
}

protocol BridgePairingCredentialLifecycleStore: AnyObject {
    func pairingStates(nowEpochMs: Int64) throws -> [MobileHubPairingState]
    func unpair(deviceId: String, ownerConfirmationReason: String) async throws
    func localRevoke(deviceId: String, ownerConfirmationReason: String) async throws
}

protocol BridgeOwnershipReadQueueCancelling: Sendable {
    func cancelAll(deviceId: String) async throws
}

extension KeychainBridgeCredentialStore: BridgePairingCredentialLifecycleStore {
    func unpair(deviceId: String, ownerConfirmationReason: String) async throws {
        try await delete(
            deviceId: deviceId,
            ownerConfirmationReason: ownerConfirmationReason
        )
    }

    func localRevoke(deviceId: String, ownerConfirmationReason: String) async throws {
        try await revoke(
            deviceId: deviceId,
            ownerConfirmationReason: ownerConfirmationReason
        )
    }
}

struct BridgeOwnershipReadQueueCanceller: BridgeOwnershipReadQueueCancelling {
    func cancelAll(deviceId: String) async throws {
        let directory = try MobileOwnershipReadQueue.applicationSupportDirectory()
        let queue = try MobileOwnershipReadQueue(directory: directory)
        try await queue.cancelAll(deviceId: deviceId)
    }
}

/// Coordinates local credential lifecycle with only the ownership-read queue.
/// Conversation outbox storage is intentionally not a dependency of this type.
struct BridgePairingLifecycleController {
    private let store: any BridgePairingCredentialLifecycleStore
    private let queueCanceller: any BridgeOwnershipReadQueueCancelling

    init(
        store: any BridgePairingCredentialLifecycleStore,
        queueCanceller: any BridgeOwnershipReadQueueCancelling
    ) {
        self.store = store
        self.queueCanceller = queueCanceller
    }

    static func live() -> BridgePairingLifecycleController {
        BridgePairingLifecycleController(
            store: KeychainBridgeCredentialStore(),
            queueCanceller: BridgeOwnershipReadQueueCanceller()
        )
    }

    func pairingStates(
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) throws -> [MobileHubPairingState] {
        try store.pairingStates(nowEpochMs: nowEpochMs)
    }

    func perform(
        _ action: BridgeLocalPairingAction,
        deviceId: String
    ) async -> BridgeLocalPairingActionResult {
        do {
            switch action {
            case .unpair:
                try await store.unpair(
                    deviceId: deviceId,
                    ownerConfirmationReason: "Подтвердите локальное отключение Windows Hub"
                )
            case .revoke:
                try await store.localRevoke(
                    deviceId: deviceId,
                    ownerConfirmationReason: "Подтвердите локальный отзыв доверия Windows Hub"
                )
            }
        } catch {
            // Fail closed: queued ownership reads are cancelled even if Keychain cleanup reports
            // an error. Network-time admission will independently reject missing/revoked state.
            do {
                try await queueCanceller.cancelAll(deviceId: deviceId)
                return .credentialChangeFailed
            } catch {
                return .credentialAndQueueCancellationFailed
            }
        }

        do {
            try await queueCanceller.cancelAll(deviceId: deviceId)
            return .completed
        } catch {
            return .queueCancellationFailed
        }
    }
}
