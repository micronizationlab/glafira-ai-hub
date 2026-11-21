import Foundation

public struct MobileWorkspaceProject: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let revision: String
    public let updatedAtEpochMs: Int64

    public init(id: String, name: String, revision: String, updatedAtEpochMs: Int64) {
        self.id = id
        self.name = name
        self.revision = revision
        self.updatedAtEpochMs = updatedAtEpochMs
    }
}

public enum MobileMessageOrigin: String, Codable, Equatable, Sendable {
    case remote
    case ownerOffline
}

public struct MobileConversationMessage: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let text: String
    public let isOwner: Bool
    public let createdAtEpochMs: Int64
    public var origin: MobileMessageOrigin

    public init(
        id: String,
        text: String,
        isOwner: Bool,
        createdAtEpochMs: Int64,
        origin: MobileMessageOrigin = .remote
    ) {
        self.id = id
        self.text = text
        self.isOwner = isOwner
        self.createdAtEpochMs = createdAtEpochMs
        self.origin = origin
    }
}

public struct MobileConversation: Codable, Equatable, Identifiable, Sendable {
    public var id: String { conversationId }
    public let projectId: String
    public let conversationId: String
    public var title: String
    public var remoteVersion: String?
    public var messages: [MobileConversationMessage]

    public init(
        projectId: String,
        conversationId: String,
        title: String,
        remoteVersion: String?,
        messages: [MobileConversationMessage]
    ) {
        self.projectId = projectId
        self.conversationId = conversationId
        self.title = title
        self.remoteVersion = remoteVersion
        self.messages = messages
    }
}

public enum MobileOutboxState: String, Codable, Equatable, Sendable {
    case pending
    case requiresOwnerResolution
}

public struct MobileOutboxEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: String { operationId }
    public let operationId: String
    public let projectId: String
    public let conversationId: String
    public let text: String
    public var baseRemoteVersion: String?
    public let createdAtEpochMs: Int64
    public var state: MobileOutboxState
}

public struct MobileWorkspaceProjectSummary: Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let revision: String
    public let conversationCount: Int
    public let pendingCount: Int
    public let conflictCount: Int
}

public struct MobileConversationSummary: Equatable, Identifiable, Sendable {
    public var id: String { conversationId }
    public let conversationId: String
    public let title: String
    public let messageCount: Int
    public let pendingCount: Int
    public let conflictCount: Int
}

public struct MobileRemoteApplyResult: Equatable, Sendable {
    public let requiresOwnerResolution: Bool
    public let pendingOperationIds: [String]
}

public enum MobileConflictChoice: Sendable {
    case discardLocalMessage
    case rebaseOntoCurrentRemoteVersion
}

public enum MobileWorkspaceStoreError: Error, Equatable, Sendable {
    case invalidIdentifier
    case invalidProject
    case invalidConversation
    case invalidMessage
    case unauthorizedProject
    case staleLocalVersion
    case operationNotFound
    case operationRequiresOwnerResolution
    case invalidPersistedState
}

/// Durable offline cache/outbox for the native iOS client.
///
/// Windows Hub remains authoritative. A remote generation change never silently
/// replays an offline write: the exact outbox entry is fenced and exposed for an
/// explicit owner decision.
public actor MobileWorkspaceStore {
    private struct Envelope: Codable {
        var schemaVersion = 1
        var projects: [String: MobileWorkspaceProject] = [:]
        var conversations: [String: MobileConversation] = [:]
        var outbox: [MobileOutboxEntry] = []
    }

    private let directory: URL
    private let fileURL: URL
    private let fileManager: FileManager
    private var envelope: Envelope

    public init(directory: URL, fileManager: FileManager = .default) throws {
        self.directory = directory
        self.fileURL = directory.appendingPathComponent("mobile-workspace-v1.json", isDirectory: false)
        self.fileManager = fileManager
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: fileURL.path) {
            let bytes = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
            let decoded = try JSONDecoder().decode(Envelope.self, from: bytes)
            guard decoded.schemaVersion == 1, Self.isValid(decoded) else {
                throw MobileWorkspaceStoreError.invalidPersistedState
            }
            self.envelope = decoded
        } else {
            self.envelope = Envelope()
        }
    }

    public static func applicationSupportDirectory(fileManager: FileManager = .default) throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return base.appendingPathComponent("GLAFIRA/MobileWorkspace", isDirectory: true)
    }

    public func replaceAuthorizedProjects(_ projects: [MobileWorkspaceProject]) throws {
        guard projects.count <= 2_000,
              Set(projects.map(\.id)).count == projects.count,
              projects.allSatisfy(Self.validProject) else {
            throw MobileWorkspaceStoreError.invalidProject
        }
        let authorized = Set(projects.map(\.id))
        envelope.projects = Dictionary(uniqueKeysWithValues: projects.map { ($0.id, $0) })
        envelope.conversations = envelope.conversations.filter { authorized.contains($0.value.projectId) }
        envelope.outbox.removeAll { !authorized.contains($0.projectId) }
        try persist()
    }

    @discardableResult
    public func appendOfflineMessage(
        projectId: String,
        conversationId: String,
        title: String,
        text: String,
        expectedRemoteVersion: String?,
        createdAtEpochMs: Int64
    ) throws -> MobileOutboxEntry {
        guard envelope.projects[projectId] != nil else {
            throw MobileWorkspaceStoreError.unauthorizedProject
        }
        guard Self.validIdentifier(conversationId), Self.validLabel(title, maximum: 160) else {
            throw MobileWorkspaceStoreError.invalidConversation
        }
        guard Self.validMessage(text), createdAtEpochMs > 0 else {
            throw MobileWorkspaceStoreError.invalidMessage
        }
        guard !envelope.outbox.contains(where: {
            $0.projectId == projectId && $0.conversationId == conversationId &&
                $0.state == .requiresOwnerResolution
        }) else {
            throw MobileWorkspaceStoreError.operationRequiresOwnerResolution
        }
        let key = Self.conversationKey(projectId: projectId, conversationId: conversationId)
        var conversation = envelope.conversations[key] ?? MobileConversation(
            projectId: projectId,
            conversationId: conversationId,
            title: title,
            remoteVersion: expectedRemoteVersion,
            messages: []
        )
        guard conversation.remoteVersion == expectedRemoteVersion else {
            throw MobileWorkspaceStoreError.staleLocalVersion
        }
        let operationId = "ios-\(UUID().uuidString.lowercased())"
        let entry = MobileOutboxEntry(
            operationId: operationId,
            projectId: projectId,
            conversationId: conversationId,
            text: text,
            baseRemoteVersion: expectedRemoteVersion,
            createdAtEpochMs: createdAtEpochMs,
            state: .pending
        )
        conversation.title = title
        conversation.messages.append(MobileConversationMessage(
            id: operationId,
            text: text,
            isOwner: true,
            createdAtEpochMs: createdAtEpochMs,
            origin: .ownerOffline
        ))
        envelope.conversations[key] = conversation
        envelope.outbox.append(entry)
        try persist()
        return entry
    }

    @discardableResult
    public func applyRemoteConversation(
        projectId: String,
        conversationId: String,
        title: String,
        remoteVersion: String,
        messages: [MobileConversationMessage]
    ) throws -> MobileRemoteApplyResult {
        guard envelope.projects[projectId] != nil else {
            throw MobileWorkspaceStoreError.unauthorizedProject
        }
        guard Self.validIdentifier(conversationId), Self.validLabel(title, maximum: 160),
              Self.validVersion(remoteVersion), messages.count <= 10_000,
              messages.allSatisfy(Self.validMessageRecord),
              Set(messages.map(\.id)).count == messages.count else {
            throw MobileWorkspaceStoreError.invalidConversation
        }
        let relatedIndices = envelope.outbox.indices.filter {
            envelope.outbox[$0].projectId == projectId &&
                envelope.outbox[$0].conversationId == conversationId
        }
        let remoteById = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
        var committedOperationIds = Set<String>()
        var requiresResolution = false
        for index in relatedIndices {
            let entry = envelope.outbox[index]
            if let committed = remoteById[entry.operationId] {
                if committed.isOwner && committed.text == entry.text {
                    committedOperationIds.insert(entry.operationId)
                } else {
                    envelope.outbox[index].state = .requiresOwnerResolution
                    requiresResolution = true
                }
            } else if entry.baseRemoteVersion != remoteVersion {
                envelope.outbox[index].state = .requiresOwnerResolution
                requiresResolution = true
            }
        }
        let pendingEntries = relatedIndices.map { envelope.outbox[$0] }
            .filter { !committedOperationIds.contains($0.operationId) }
        envelope.outbox.removeAll { committedOperationIds.contains($0.operationId) }
        let remoteIds = Set(messages.map(\.id))
        let pendingMessages = pendingEntries.compactMap { entry -> MobileConversationMessage? in
            guard !remoteIds.contains(entry.operationId) else { return nil }
            return MobileConversationMessage(
                id: entry.operationId,
                text: entry.text,
                isOwner: true,
                createdAtEpochMs: entry.createdAtEpochMs,
                origin: .ownerOffline
            )
        }
        let key = Self.conversationKey(projectId: projectId, conversationId: conversationId)
        envelope.conversations[key] = MobileConversation(
            projectId: projectId,
            conversationId: conversationId,
            title: title,
            remoteVersion: remoteVersion,
            messages: messages.map {
                MobileConversationMessage(
                    id: $0.id,
                    text: $0.text,
                    isOwner: $0.isOwner,
                    createdAtEpochMs: $0.createdAtEpochMs,
                    origin: .remote
                )
            } + pendingMessages
        )
        try persist()
        return MobileRemoteApplyResult(
            requiresOwnerResolution: requiresResolution,
            pendingOperationIds: pendingEntries.map(\.operationId)
        )
    }

    public func acknowledge(operationId: String, newRemoteVersion: String) throws {
        guard Self.validVersion(newRemoteVersion),
              let index = envelope.outbox.firstIndex(where: { $0.operationId == operationId }) else {
            throw MobileWorkspaceStoreError.operationNotFound
        }
        let entry = envelope.outbox[index]
        guard entry.state == .pending else {
            throw MobileWorkspaceStoreError.operationRequiresOwnerResolution
        }
        let key = Self.conversationKey(projectId: entry.projectId, conversationId: entry.conversationId)
        guard var conversation = envelope.conversations[key] else {
            throw MobileWorkspaceStoreError.invalidPersistedState
        }
        conversation.remoteVersion = newRemoteVersion
        if let messageIndex = conversation.messages.firstIndex(where: { $0.id == operationId }) {
            conversation.messages[messageIndex].origin = .remote
        }
        envelope.conversations[key] = conversation
        envelope.outbox.remove(at: index)
        try persist()
    }

    public func resolveConflict(operationId: String, choice: MobileConflictChoice) throws {
        guard let index = envelope.outbox.firstIndex(where: { $0.operationId == operationId }) else {
            throw MobileWorkspaceStoreError.operationNotFound
        }
        let entry = envelope.outbox[index]
        guard entry.state == .requiresOwnerResolution else {
            throw MobileWorkspaceStoreError.operationRequiresOwnerResolution
        }
        let key = Self.conversationKey(projectId: entry.projectId, conversationId: entry.conversationId)
        guard var conversation = envelope.conversations[key] else {
            throw MobileWorkspaceStoreError.invalidPersistedState
        }
        switch choice {
        case .discardLocalMessage:
            conversation.messages.removeAll { $0.id == operationId }
            envelope.conversations[key] = conversation
            envelope.outbox.remove(at: index)
        case .rebaseOntoCurrentRemoteVersion:
            envelope.outbox[index].baseRemoteVersion = conversation.remoteVersion
            envelope.outbox[index].state = .pending
        }
        try persist()
    }

    public func projectSummaries() -> [MobileWorkspaceProjectSummary] {
        envelope.projects.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            .map { project in
                let conversations = envelope.conversations.values.filter { $0.projectId == project.id }
                let outbox = envelope.outbox.filter { $0.projectId == project.id }
                return MobileWorkspaceProjectSummary(
                    id: project.id,
                    name: project.name,
                    revision: project.revision,
                    conversationCount: conversations.count,
                    pendingCount: outbox.filter { $0.state == .pending }.count,
                    conflictCount: outbox.filter { $0.state == .requiresOwnerResolution }.count
                )
            }
    }

    public func conversationSummaries(projectId: String) throws -> [MobileConversationSummary] {
        guard envelope.projects[projectId] != nil else {
            throw MobileWorkspaceStoreError.unauthorizedProject
        }
        return envelope.conversations.values.filter { $0.projectId == projectId }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
            .map { conversation in
                let outbox = envelope.outbox.filter {
                    $0.projectId == projectId && $0.conversationId == conversation.conversationId
                }
                return MobileConversationSummary(
                    conversationId: conversation.conversationId,
                    title: conversation.title,
                    messageCount: conversation.messages.count,
                    pendingCount: outbox.filter { $0.state == .pending }.count,
                    conflictCount: outbox.filter { $0.state == .requiresOwnerResolution }.count
                )
            }
    }

    public func conversation(projectId: String, conversationId: String) throws -> MobileConversation? {
        guard envelope.projects[projectId] != nil else {
            throw MobileWorkspaceStoreError.unauthorizedProject
        }
        return envelope.conversations[Self.conversationKey(projectId: projectId, conversationId: conversationId)]
    }

    public func pendingOutbox() -> [MobileOutboxEntry] {
        envelope.outbox.sorted {
            if $0.createdAtEpochMs != $1.createdAtEpochMs {
                return $0.createdAtEpochMs < $1.createdAtEpochMs
            }
            return $0.operationId < $1.operationId
        }
    }

    private func persist() throws {
        guard Self.isValid(envelope) else { throw MobileWorkspaceStoreError.invalidPersistedState }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let bytes = try encoder.encode(envelope)
        try bytes.write(to: fileURL, options: [.atomic])
#if os(iOS)
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: fileURL.path
        )
#endif
    }

    private static func isValid(_ value: Envelope) -> Bool {
        let operationIds = value.outbox.map(\.operationId)
        return value.schemaVersion == 1 && value.projects.count <= 2_000 &&
            value.projects.allSatisfy { key, project in key == project.id && validProject(project) } &&
            value.conversations.count <= 20_000 && value.conversations.allSatisfy { key, conversation in
                key == conversationKey(projectId: conversation.projectId, conversationId: conversation.conversationId) &&
                    value.projects[conversation.projectId] != nil && validConversation(conversation)
            } && value.outbox.count <= 20_000 && Set(operationIds).count == operationIds.count &&
            value.outbox.allSatisfy { entry in
                let key = conversationKey(projectId: entry.projectId, conversationId: entry.conversationId)
                value.projects[entry.projectId] != nil && validIdentifier(entry.operationId) &&
                    validIdentifier(entry.conversationId) && validMessage(entry.text) && entry.createdAtEpochMs > 0 &&
                    value.conversations[key]?.messages.contains {
                        $0.id == entry.operationId && $0.text == entry.text &&
                            $0.isOwner && $0.origin == .ownerOffline
                    } == true
            }
    }

    private static func validProject(_ project: MobileWorkspaceProject) -> Bool {
        validIdentifier(project.id) && validLabel(project.name, maximum: 160) &&
            validVersion(project.revision) && project.updatedAtEpochMs > 0
    }

    private static func validConversation(_ conversation: MobileConversation) -> Bool {
        validIdentifier(conversation.projectId) && validIdentifier(conversation.conversationId) &&
            validLabel(conversation.title, maximum: 160) &&
            (conversation.remoteVersion == nil || validVersion(conversation.remoteVersion!)) &&
            conversation.messages.count <= 10_000 &&
            Set(conversation.messages.map(\.id)).count == conversation.messages.count &&
            conversation.messages.allSatisfy(validMessageRecord)
    }

    private static func validMessageRecord(_ message: MobileConversationMessage) -> Bool {
        validIdentifier(message.id) && validMessage(message.text) && message.createdAtEpochMs > 0
    }

    private static func validMessage(_ value: String) -> Bool {
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.utf16.count <= 16_384
    }

    private static func validVersion(_ value: String) -> Bool {
        validLabel(value, maximum: 128)
    }

    private static func validIdentifier(_ value: String) -> Bool {
        !value.isEmpty && value.utf16.count <= 128 && value.unicodeScalars.allSatisfy {
            !CharacterSet.controlCharacters.contains($0) && $0.value != 47 && $0.value != 92
        }
    }

    private static func validLabel(_ value: String, maximum: Int) -> Bool {
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            value.utf16.count <= maximum &&
            value.unicodeScalars.allSatisfy { !CharacterSet.controlCharacters.contains($0) }
    }

    private static func conversationKey(projectId: String, conversationId: String) -> String {
        "\(projectId)\u{001F}\(conversationId)"
    }
}
