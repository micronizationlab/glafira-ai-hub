import Combine
import Foundation
import GlafiraBridgeKit

enum WorkspaceSyncAttempt: Equatable, Sendable {
    case synchronized
    case noPendingWork
    case requiresOwnerResolution
    case unavailable
    case retryPending
    case blocked
}

@MainActor
final class GlafiraWorkspaceViewModel: ObservableObject {
    typealias PairingLoader = (Int64) throws -> [MobileHubPairingState]
    typealias ProjectSnapshot = () async -> [MobileWorkspaceProjectSummary]
    typealias Consume = (MobileHubPairingState, Int64) async throws -> MobileWorkspaceOutboxOutcome

    @Published private(set) var projects: [MobileWorkspaceProjectSummary] = []
    @Published private(set) var pairings: [MobileHubPairingState] = []
    @Published private(set) var attempts: [String: WorkspaceSyncAttempt] = [:]
    @Published private(set) var syncingDeviceIds: Set<String> = []
    @Published private(set) var status = "Локальное рабочее пространство готовится…"

    private let store: MobileWorkspaceStore?
    private let pairingLoader: PairingLoader
    private let projectSnapshot: ProjectSnapshot
    private let consume: Consume
    private let bootstrapFailure: Bool

    init() {
        do {
            let directory = try MobileWorkspaceStore.applicationSupportDirectory()
            let workspaceStore = try MobileWorkspaceStore(directory: directory)
            let consumer = MobileWorkspaceOutboxConsumer(
                store: workspaceStore,
                credentialStore: KeychainBridgeCredentialStore()
            )
            store = workspaceStore
            pairingLoader = { nowEpochMs in
                try KeychainBridgeCredentialStore().pairingStates(nowEpochMs: nowEpochMs)
            }
            projectSnapshot = { await workspaceStore.projectSummaries() }
            consume = { pairing, nowEpochMs in
                try await consumer.consumeNext(
                    pairingState: pairing,
                    nowEpochMs: nowEpochMs
                )
            }
            bootstrapFailure = false
            status = "Локальный кэш готов. Ожидается синхронизация с Windows Hub."
        } catch {
            store = nil
            pairingLoader = { _ in [] }
            projectSnapshot = { [] }
            consume = { _, _ in throw WorkspaceSyncViewModelError.unavailable }
            bootstrapFailure = true
            status = "Локальное рабочее пространство недоступно"
        }
    }

    init(
        store: MobileWorkspaceStore? = nil,
        pairingLoader: @escaping PairingLoader,
        projectSnapshot: @escaping ProjectSnapshot,
        consume: @escaping Consume
    ) {
        self.store = store
        self.pairingLoader = pairingLoader
        self.projectSnapshot = projectSnapshot
        self.consume = consume
        bootstrapFailure = false
    }

    func reload(
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async {
        guard !bootstrapFailure else {
            projects = []
            pairings = []
            return
        }
        do {
            pairings = try pairingLoader(nowEpochMs)
            projects = await projectSnapshot()
            status = workspaceStatus()
        } catch {
            pairings = []
            projects = await projectSnapshot()
            status = "Не удалось проверить сопряжение с Windows Hub"
        }
    }

    func syncNext(
        deviceId: String,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async {
        guard !syncingDeviceIds.contains(deviceId) else { return }
        guard pendingCount > 0 else {
            attempts[deviceId] = .noPendingWork
            status = "Сообщений для отправки нет"
            return
        }
        guard let pairing = pairings.first(where: {
            $0.deviceId == deviceId && $0.evaluated(at: nowEpochMs).status == .paired
        }) else {
            attempts[deviceId] = .blocked
            status = "Отправка заблокирована: восстановите сопряжение с Windows Hub"
            return
        }

        syncingDeviceIds.insert(deviceId)
        defer { syncingDeviceIds.remove(deviceId) }
        do {
            let outcome = try await consume(pairing, nowEpochMs)
            let completedStatus: String
            switch outcome {
            case .synchronized(_, _, let duplicate):
                attempts[deviceId] = .synchronized
                completedStatus = duplicate
                    ? "Windows Hub подтвердил ранее принятую операцию"
                    : "Следующее сообщение подтверждено Windows Hub"
            case .requiresOwnerResolution(_, _):
                attempts[deviceId] = .requiresOwnerResolution
                completedStatus = "Конфликт версии: откройте диалог и выберите решение владельца"
            case .unavailable(_, _):
                attempts[deviceId] = .unavailable
                completedStatus = "Windows Hub отклонил отправку; сообщение сохранено для явного повтора"
            case .noPendingWork:
                attempts[deviceId] = .noPendingWork
                completedStatus = "Сообщений для отправки нет"
            }
            await reload(nowEpochMs: nowEpochMs)
            status = completedStatus
        } catch {
            attempts[deviceId] = .retryPending
            status = retryStatus(error)
        }
    }

    var pendingCount: Int {
        projects.reduce(0) { $0 + $1.pendingCount }
    }

    var conflictCount: Int {
        projects.reduce(0) { $0 + $1.conflictCount }
    }

    func conversations(projectId: String) async -> [MobileConversationSummary] {
        guard let store else { return [] }
        return (try? await store.conversationSummaries(projectId: projectId)) ?? []
    }

    func conversation(projectId: String, conversationId: String) async -> MobileConversation? {
        guard let store else { return nil }
        return try? await store.conversation(projectId: projectId, conversationId: conversationId)
    }

    func outbox(projectId: String, conversationId: String) async -> [MobileOutboxEntry] {
        guard let store else { return [] }
        return await store.pendingOutbox().filter {
            $0.projectId == projectId && $0.conversationId == conversationId
        }
    }

    func queueOfflineMessage(
        projectId: String,
        conversationId: String,
        title: String,
        remoteVersion: String?,
        text: String
    ) async -> String? {
        guard let store else { return "Локальное хранилище недоступно" }
        do {
            _ = try await store.appendOfflineMessage(
                projectId: projectId,
                conversationId: conversationId,
                title: title,
                text: text,
                expectedRemoteVersion: remoteVersion,
                createdAtEpochMs: Int64(Date().timeIntervalSince1970 * 1_000)
            )
            await reload()
            return nil
        } catch MobileWorkspaceStoreError.operationRequiresOwnerResolution {
            return "Сначала разрешите конфликт версии"
        } catch MobileWorkspaceStoreError.staleLocalVersion {
            return "Диалог изменился. Обновите его перед отправкой"
        } catch {
            return "Не удалось сохранить сообщение в офлайн-очередь"
        }
    }

    func resolveConflict(operationId: String, choice: MobileConflictChoice) async -> String? {
        guard let store else { return "Локальное хранилище недоступно" }
        do {
            try await store.resolveConflict(operationId: operationId, choice: choice)
            await reload()
            return nil
        } catch {
            return "Не удалось применить решение конфликта"
        }
    }

    private func workspaceStatus() -> String {
        if projects.isEmpty {
            return "Нет разрешённых проектов. Синхронизируйте их через Windows Hub."
        }
        if conflictCount > 0 {
            return "Есть конфликты офлайн-изменений: \(conflictCount)"
        }
        if pendingCount > 0 {
            return "Ожидают явной отправки владельцем: \(pendingCount)"
        }
        return "Проекты доступны офлайн"
    }

    private func retryStatus(_ error: Error) -> String {
        if error as? BridgeCredentialStoreError == .authenticationCancelled {
            return "Отправка отменена владельцем; сообщение осталось в очереди"
        }
        switch error as? MobileWorkspaceOutboxConsumerError {
        case .invalidPairingState, .credentialBindingMismatch,
             .conversationAppendScopeMissing:
            return "Проверка доверия не пройдена; сообщение сохранено до нового сопряжения"
        case .invalidRequest, .invalidResponse:
            return "Неподтверждённый запрос или ответ; сообщение сохранено для безопасного повтора"
        case nil:
            return "Windows Hub недоступен; сообщение сохранено для явного повтора"
        }
    }
}

private enum WorkspaceSyncViewModelError: Error {
    case unavailable
}
