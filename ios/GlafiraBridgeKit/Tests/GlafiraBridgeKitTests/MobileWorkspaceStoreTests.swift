import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class MobileWorkspaceStoreTests: XCTestCase {
    private var directory: URL!
    private let encryptionKey = Data(repeating: 0xA5, count: 32)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("glafira-mobile-workspace-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if directory != nil {
            try? FileManager.default.removeItem(at: directory)
        }
    }

    func testOfflineMessageAndOutboxSurviveRepositoryRestart() async throws {
        let first = try store()
        try await first.replaceAuthorizedProjects([project("project-001", name: "Клиенты")])
        let entry = try await first.appendOfflineMessage(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            text: "Подготовь сводку",
            expectedRemoteVersion: nil,
            createdAtEpochMs: 1_800_000_000_000
        )

        let reopened = try store()
        let outbox = await reopened.pendingOutbox()
        let conversation = try await reopened.conversation(
            projectId: "project-001",
            conversationId: "conversation-001"
        )

        XCTAssertEqual(outbox.map(\.operationId), [entry.operationId])
        XCTAssertEqual(conversation?.messages.map(\.text), ["Подготовь сводку"])
        XCTAssertEqual(conversation?.messages.first?.origin, .ownerOffline)
    }

    func testExactAcknowledgementCommitsMessageAndAdvancesRemoteVersion() async throws {
        let store = try store()
        try await store.replaceAuthorizedProjects([project("project-001", name: "Клиенты")])
        let entry = try await store.appendOfflineMessage(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            text: "Проверь статус",
            expectedRemoteVersion: nil,
            createdAtEpochMs: 1_800_000_000_000
        )

        try await store.acknowledge(operationId: entry.operationId, newRemoteVersion: "server-v1")

        let afterAcknowledgement = await store.pendingOutbox()
        XCTAssertTrue(afterAcknowledgement.isEmpty)
        let conversation = try await store.conversation(
            projectId: "project-001",
            conversationId: "conversation-001"
        )
        XCTAssertEqual(conversation?.remoteVersion, "server-v1")
        XCTAssertEqual(conversation?.messages.first?.origin, .remote)
    }

    func testRemoteGenerationChangeRequiresExplicitOwnerResolution() async throws {
        let store = try store()
        try await store.replaceAuthorizedProjects([project("project-001", name: "Клиенты")])
        _ = try await store.applyRemoteConversation(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            remoteVersion: "server-v1",
            messages: [remoteMessage("remote-001", text: "Исходное состояние")]
        )
        let entry = try await store.appendOfflineMessage(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            text: "Офлайн-изменение",
            expectedRemoteVersion: "server-v1",
            createdAtEpochMs: 1_800_000_000_100
        )

        let result = try await store.applyRemoteConversation(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            remoteVersion: "server-v2",
            messages: [remoteMessage("remote-002", text: "Изменено на другом узле")]
        )

        XCTAssertTrue(result.requiresOwnerResolution)
        XCTAssertEqual(result.pendingOperationIds, [entry.operationId])
        let conflictedOutbox = await store.pendingOutbox()
        XCTAssertEqual(conflictedOutbox.first?.state, .requiresOwnerResolution)
        do {
            try await store.acknowledge(operationId: entry.operationId, newRemoteVersion: "server-v3")
            XCTFail("Conflict must not be acknowledged without an owner decision")
        } catch {
            XCTAssertEqual(error as? MobileWorkspaceStoreError, .operationRequiresOwnerResolution)
        }

        try await store.resolveConflict(
            operationId: entry.operationId,
            choice: .rebaseOntoCurrentRemoteVersion
        )
        let rebasedOutbox = await store.pendingOutbox()
        let rebased = try XCTUnwrap(rebasedOutbox.first)
        XCTAssertEqual(rebased.state, .pending)
        XCTAssertEqual(rebased.baseRemoteVersion, "server-v2")
    }

    func testProjectRevocationDeletesItsCacheAndPendingWrites() async throws {
        let store = try store()
        try await store.replaceAuthorizedProjects([
            project("project-001", name: "Первый"),
            project("project-002", name: "Второй")
        ])
        _ = try await store.appendOfflineMessage(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            text: "Не переносить после отзыва",
            expectedRemoteVersion: nil,
            createdAtEpochMs: 1_800_000_000_000
        )

        try await store.replaceAuthorizedProjects([project("project-002", name: "Второй")])

        let remainingOutbox = await store.pendingOutbox()
        let remainingProjects = await store.projectSummaries()
        XCTAssertTrue(remainingOutbox.isEmpty)
        XCTAssertEqual(remainingProjects.map(\.id), ["project-002"])
        do {
            _ = try await store.conversation(
                projectId: "project-001",
                conversationId: "conversation-001"
            )
            XCTFail("Revoked project must be inaccessible")
        } catch {
            XCTAssertEqual(error as? MobileWorkspaceStoreError, .unauthorizedProject)
        }
    }

    func testDuplicateRemoteOperationIdsFailClosedWithoutReplacingCurrentConversation() async throws {
        let store = try store()
        try await store.replaceAuthorizedProjects([project("project-001", name: "Клиенты")])
        _ = try await store.applyRemoteConversation(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            remoteVersion: "server-v1",
            messages: [remoteMessage("remote-001", text: "Допустимое сообщение")]
        )
        do {
            _ = try await store.applyRemoteConversation(
                projectId: "project-001",
                conversationId: "conversation-001",
                title: "ГЛАФИРА",
                remoteVersion: "server-v2",
                messages: [
                    remoteMessage("remote-duplicate", text: "Первое"),
                    remoteMessage("remote-duplicate", text: "Второе")
                ]
            )
            XCTFail("Duplicate remote operation IDs must fail closed")
        } catch {
            XCTAssertEqual(error as? MobileWorkspaceStoreError, .invalidConversation)
        }

        let conversation = try await store.conversation(
            projectId: "project-001",
            conversationId: "conversation-001"
        )
        XCTAssertEqual(conversation?.remoteVersion, "server-v1")
        XCTAssertEqual(conversation?.messages.map(\.text), ["Допустимое сообщение"])
    }

    func testExactRemoteEchoAcknowledgesOutboxWithoutReplayingTheWrite() async throws {
        let store = try store()
        try await store.replaceAuthorizedProjects([project("project-001", name: "Клиенты")])
        _ = try await store.applyRemoteConversation(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            remoteVersion: "server-v1",
            messages: [remoteMessage("remote-001", text: "Исходное состояние")]
        )
        let entry = try await store.appendOfflineMessage(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            text: "Уже принято Hub",
            expectedRemoteVersion: "server-v1",
            createdAtEpochMs: 1_800_000_000_100
        )

        let applied = try await store.applyRemoteConversation(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            remoteVersion: "server-v2",
            messages: [
                remoteMessage("remote-001", text: "Исходное состояние"),
                MobileConversationMessage(
                    id: entry.operationId,
                    text: entry.text,
                    isOwner: true,
                    createdAtEpochMs: entry.createdAtEpochMs,
                    origin: .remote
                )
            ]
        )

        XCTAssertFalse(applied.requiresOwnerResolution)
        XCTAssertTrue(applied.pendingOperationIds.isEmpty)
        let outbox = await store.pendingOutbox()
        XCTAssertTrue(outbox.isEmpty)
        let conversation = try await store.conversation(
            projectId: "project-001",
            conversationId: "conversation-001"
        )
        XCTAssertEqual(conversation?.messages.filter { $0.id == entry.operationId }.count, 1)
        XCTAssertEqual(conversation?.messages.first { $0.id == entry.operationId }?.origin, .remote)
    }

    func testPersistedWorkspaceIsAuthenticatedCiphertext() async throws {
        let store = try store()
        try await store.replaceAuthorizedProjects([project("project-001", name: "Секретный проект")])
        _ = try await store.appendOfflineMessage(
            projectId: "project-001",
            conversationId: "conversation-001",
            title: "ГЛАФИРА",
            text: "Конфиденциальный офлайн-текст",
            expectedRemoteVersion: nil,
            createdAtEpochMs: 1_800_000_000_000
        )

        let sealedURL = directory.appendingPathComponent("mobile-workspace-v1.sealed")
        let bytes = try Data(contentsOf: sealedURL)
        let encoded = String(decoding: bytes, as: UTF8.self)
        XCTAssertFalse(encoded.contains("Секретный проект"))
        XCTAssertFalse(encoded.contains("Конфиденциальный офлайн-текст"))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent("mobile-workspace-v1.json").path
        ))
    }

    func testWrongKeyFailsClosedWithoutReplacingCiphertext() async throws {
        let store = try store()
        try await store.replaceAuthorizedProjects([project("project-001", name: "Клиенты")])
        let sealedURL = directory.appendingPathComponent("mobile-workspace-v1.sealed")
        let before = try Data(contentsOf: sealedURL)

        XCTAssertThrowsError(try MobileWorkspaceStore(
            directory: directory,
            encryptionKey: Data(repeating: 0xB6, count: 32)
        )) { error in
            XCTAssertEqual(error as? MobileWorkspaceStoreError, .invalidPersistedState)
        }
        XCTAssertEqual(try Data(contentsOf: sealedURL), before)
    }

    func testLegacyPlaintextIsMigratedOnceToSealedStorage() async throws {
        let legacy = """
        {"schemaVersion":1,"projects":{"project-001":{"id":"project-001","name":"Legacy","revision":"project-v1","updatedAtEpochMs":1800000000000}},"conversations":{},"outbox":[]}
        """
        let legacyURL = directory.appendingPathComponent("mobile-workspace-v1.json")
        try Data(legacy.utf8).write(to: legacyURL, options: [.atomic])

        let migrated = try store()
        let projectIds = await migrated.projectSummaries().map(\.id)
        XCTAssertEqual(projectIds, ["project-001"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyURL.path))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent("mobile-workspace-v1.sealed").path
        ))
    }

    private func store() throws -> MobileWorkspaceStore {
        try MobileWorkspaceStore(directory: directory, encryptionKey: encryptionKey)
    }

    private func project(_ id: String, name: String) -> MobileWorkspaceProject {
        MobileWorkspaceProject(
            id: id,
            name: name,
            revision: "project-v1",
            updatedAtEpochMs: 1_800_000_000_000
        )
    }

    private func remoteMessage(_ id: String, text: String) -> MobileConversationMessage {
        MobileConversationMessage(
            id: id,
            text: text,
            isOwner: false,
            createdAtEpochMs: 1_800_000_000_000,
            origin: .remote
        )
    }
}
