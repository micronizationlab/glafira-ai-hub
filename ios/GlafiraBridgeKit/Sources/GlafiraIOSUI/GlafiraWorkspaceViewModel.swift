import Combine
import Foundation
import GlafiraBridgeKit

@MainActor
final class GlafiraWorkspaceViewModel: ObservableObject {
    @Published private(set) var projects: [MobileWorkspaceProjectSummary] = []
    @Published private(set) var status = "Локальное рабочее пространство готовится…"

    private let store: MobileWorkspaceStore?

    init() {
        do {
            let directory = try MobileWorkspaceStore.applicationSupportDirectory()
            store = try MobileWorkspaceStore(directory: directory)
            status = "Локальный кэш готов. Ожидается синхронизация с Windows Hub."
        } catch {
            store = nil
            status = "Локальное рабочее пространство недоступно"
        }
    }

    func reload() async {
        guard let store else {
            projects = []
            return
        }
        projects = await store.projectSummaries()
        if projects.isEmpty {
            status = "Нет разрешённых проектов. Синхронизируйте их через Windows Hub."
        } else {
            let conflicts = projects.reduce(0) { $0 + $1.conflictCount }
            status = conflicts == 0
                ? "Проекты доступны офлайн"
                : "Есть конфликты офлайн-изменений: \(conflicts)"
        }
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
}
