import Foundation
import CryptoKit

public enum MobileHubPairingStatus: String, Codable, Equatable, Sendable {
    case unpaired
    case paired
    case expired
    case revoked
    case invalid
}

/// Non-secret lifecycle snapshot used to admit read-only ownership synchronization.
public struct MobileHubPairingState: Codable, Equatable, Sendable {
    public let status: MobileHubPairingStatus
    public let deviceId: String?
    public let desktopHubId: String?
    public let endpoint: String?
    public let certificateSHA256: String?
    public let expiresAtEpochMs: Int64?

    public static func paired(
        deviceId: String,
        desktopHubId: String,
        endpoint: String,
        certificateSHA256: String,
        expiresAtEpochMs: Int64,
        nowEpochMs: Int64
    ) throws -> MobileHubPairingState {
        guard MobileOwnershipReadQueueValidation.validIdentifier(deviceId),
              MobileOwnershipReadQueueValidation.validIdentifier(desktopHubId),
              MobileOwnershipReadQueueValidation.validEndpoint(endpoint),
              MobileOwnershipReadQueueValidation.validPin(certificateSHA256),
              expiresAtEpochMs > 0, nowEpochMs > 0 else {
            throw MobileOwnershipReadQueueError.invalidPairingState
        }
        return MobileHubPairingState(
            status: expiresAtEpochMs > nowEpochMs ? .paired : .expired,
            deviceId: deviceId,
            desktopHubId: desktopHubId,
            endpoint: endpoint,
            certificateSHA256: certificateSHA256.lowercased(),
            expiresAtEpochMs: expiresAtEpochMs
        )
    }

    public static func disconnected(_ status: MobileHubPairingStatus) -> MobileHubPairingState {
        precondition(status != .paired && status != .expired)
        return MobileHubPairingState(
            status: status,
            deviceId: nil,
            desktopHubId: nil,
            endpoint: nil,
            certificateSHA256: nil,
            expiresAtEpochMs: nil
        )
    }

    public func evaluated(at nowEpochMs: Int64) -> MobileHubPairingState {
        guard status == .paired, let expiry = expiresAtEpochMs, expiry <= nowEpochMs else {
            return self
        }
        return MobileHubPairingState(
            status: .expired,
            deviceId: deviceId,
            desktopHubId: desktopHubId,
            endpoint: endpoint,
            certificateSHA256: certificateSHA256,
            expiresAtEpochMs: expiresAtEpochMs
        )
    }
}

public extension BridgePairingEnrollmentResponse {
    /// Builds lifecycle metadata only after the existing strict invitation/response correlation.
    func pairingState(
        matching invitation: BridgePairingInvitation,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) throws -> MobileHubPairingState {
        _ = try credential(matching: invitation, nowEpochMs: nowEpochMs)
        guard let desktopHubId, let endpoint, let tlsCertificateSha256,
              let expiresAtEpochMs else {
            throw BridgeInvitationError.invalidEnrollmentResponse
        }
        return try .paired(
            deviceId: invitation.agentId,
            desktopHubId: desktopHubId,
            endpoint: endpoint,
            certificateSHA256: tlsCertificateSha256,
            expiresAtEpochMs: expiresAtEpochMs,
            nowEpochMs: nowEpochMs
        )
    }
}

public struct MobileOwnershipReadWork: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let deviceId: String
    public let projectId: String
    public let transferId: String
    public let enqueuedAtEpochMs: Int64
}

public enum MobileOwnershipReadScheduleResult: Equatable, Sendable {
    case enqueued(MobileOwnershipReadWork)
    case alreadyQueued(MobileOwnershipReadWork)
    case blocked(MobileHubPairingStatus)
}

public enum MobileOwnershipReadQueueError: Error, Equatable, Sendable {
    case invalidPairingState
    case invalidIdentifier
    case invalidTime
    case queueLimitExceeded
    case invalidPersistedState
}

///
/// Durable queue only for ownership-transfer reads. It is intentionally separate from the
/// conversation-write outbox and never grants lease issuance or transfer-signing authority.
///
public actor MobileOwnershipReadQueue {
    private struct Envelope: Codable {
        var schemaVersion = 1
        var entries: [MobileOwnershipReadWork] = []
    }

    private let fileURL: URL
    private let fileManager: FileManager
    private var envelope: Envelope

    public init(directory: URL, fileManager: FileManager = .default) throws {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        self.fileURL = directory.appendingPathComponent(
            "mobile-ownership-read-queue-v1.json",
            isDirectory: false
        )
        self.fileManager = fileManager
        if fileManager.fileExists(atPath: fileURL.path) {
            let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
            guard (1...1_048_576).contains(data.count),
                  Self.validPersistedShape(data),
                  let decoded = try? JSONDecoder().decode(Envelope.self, from: data),
                  Self.valid(decoded) else {
                throw MobileOwnershipReadQueueError.invalidPersistedState
            }
            envelope = decoded
        } else {
            envelope = Envelope()
        }
    }

    public static func applicationSupportDirectory(
        fileManager: FileManager = .default
    ) throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return base.appendingPathComponent("GLAFIRA/OwnershipReads", isDirectory: true)
    }

    @discardableResult
    public func schedule(
        pairingState: MobileHubPairingState,
        projectId: String,
        transferId: String,
        nowEpochMs: Int64
    ) throws -> MobileOwnershipReadScheduleResult {
        guard nowEpochMs > 0 else { throw MobileOwnershipReadQueueError.invalidTime }
        guard MobileOwnershipReadQueueValidation.validProjectId(projectId),
              MobileOwnershipReadQueueValidation.validIdentifier(transferId) else {
            throw MobileOwnershipReadQueueError.invalidIdentifier
        }
        let current = pairingState.evaluated(at: nowEpochMs)
        guard current.status == .paired else { return .blocked(current.status) }
        guard let deviceId = current.deviceId,
              let desktopHubId = current.desktopHubId,
              let endpoint = current.endpoint,
              let certificateSHA256 = current.certificateSHA256,
              let expiresAtEpochMs = current.expiresAtEpochMs,
              MobileOwnershipReadQueueValidation.validIdentifier(deviceId),
              MobileOwnershipReadQueueValidation.validIdentifier(desktopHubId),
              MobileOwnershipReadQueueValidation.validEndpoint(endpoint),
              MobileOwnershipReadQueueValidation.validPin(certificateSHA256),
              expiresAtEpochMs > nowEpochMs else {
            throw MobileOwnershipReadQueueError.invalidPairingState
        }
        let identifier = Self.workIdentifier(
            deviceId: deviceId,
            projectId: projectId,
            transferId: transferId
        )
        if let existing = envelope.entries.first(where: { $0.id == identifier }) {
            return .alreadyQueued(existing)
        }
        guard envelope.entries.count < 2_000 else {
            throw MobileOwnershipReadQueueError.queueLimitExceeded
        }
        let work = MobileOwnershipReadWork(
            id: identifier,
            deviceId: deviceId,
            projectId: projectId,
            transferId: transferId,
            enqueuedAtEpochMs: nowEpochMs
        )
        envelope.entries.append(work)
        try persist()
        return .enqueued(work)
    }

    /// Returns work only while the same paired device remains valid immediately before network.
    public func pending(
        pairingState: MobileHubPairingState,
        nowEpochMs: Int64
    ) throws -> [MobileOwnershipReadWork] {
        guard nowEpochMs > 0 else { throw MobileOwnershipReadQueueError.invalidTime }
        let current = pairingState.evaluated(at: nowEpochMs)
        guard current.status == .paired else { return [] }
        guard let deviceId = current.deviceId,
              let desktopHubId = current.desktopHubId,
              let endpoint = current.endpoint,
              let certificateSHA256 = current.certificateSHA256,
              let expiresAtEpochMs = current.expiresAtEpochMs,
              MobileOwnershipReadQueueValidation.validIdentifier(deviceId),
              MobileOwnershipReadQueueValidation.validIdentifier(desktopHubId),
              MobileOwnershipReadQueueValidation.validEndpoint(endpoint),
              MobileOwnershipReadQueueValidation.validPin(certificateSHA256),
              expiresAtEpochMs > nowEpochMs else {
            throw MobileOwnershipReadQueueError.invalidPairingState
        }
        return envelope.entries.filter { $0.deviceId == deviceId }.sorted {
            if $0.enqueuedAtEpochMs != $1.enqueuedAtEpochMs {
                return $0.enqueuedAtEpochMs < $1.enqueuedAtEpochMs
            }
            return $0.id < $1.id
        }
    }

    /// Cancels only ownership reads for this device; the ordinary mobile outbox is untouched.
    public func cancelAll(deviceId: String) throws {
        guard MobileOwnershipReadQueueValidation.validIdentifier(deviceId) else {
            throw MobileOwnershipReadQueueError.invalidIdentifier
        }
        let originalCount = envelope.entries.count
        envelope.entries.removeAll { $0.deviceId == deviceId }
        if envelope.entries.count != originalCount { try persist() }
    }

    private func persist() throws {
        guard Self.valid(envelope) else {
            throw MobileOwnershipReadQueueError.invalidPersistedState
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(envelope)
        try data.write(to: fileURL, options: [.atomic])
#if os(iOS)
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: fileURL.path
        )
#endif
    }

    private static func valid(_ value: Envelope) -> Bool {
        value.schemaVersion == 1 && value.entries.count <= 2_000 &&
            Set(value.entries.map(\.id)).count == value.entries.count &&
            value.entries.allSatisfy { entry in
                MobileOwnershipReadQueueValidation.validIdentifier(entry.deviceId) &&
                    MobileOwnershipReadQueueValidation.validProjectId(entry.projectId) &&
                    MobileOwnershipReadQueueValidation.validIdentifier(entry.transferId) &&
                    entry.enqueuedAtEpochMs > 0 && entry.id == workIdentifier(
                        deviceId: entry.deviceId,
                        projectId: entry.projectId,
                        transferId: entry.transferId
                    )
            }
    }

    private static func validPersistedShape(_ data: Data) -> Bool {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set(["schemaVersion", "entries"]),
              let entries = object["entries"] as? [[String: Any]],
              entries.allSatisfy({ entry in
                  Set(entry.keys) == Set([
                      "id", "deviceId", "projectId", "transferId", "enqueuedAtEpochMs"
                  ])
              }) else {
            return false
        }
        return true
    }

    private static func workIdentifier(
        deviceId: String,
        projectId: String,
        transferId: String
    ) -> String {
        let canonical = "\(deviceId.utf16.count):\(deviceId)" +
            "\(projectId.utf16.count):\(projectId)" +
            "\(transferId.utf16.count):\(transferId)"
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return "ios-ownership-read-" + digest.map { String(format: "%02x", $0) }.joined()
    }
}

enum MobileOwnershipReadQueueValidation {
    static func validIdentifier(_ value: String) -> Bool {
        (8...128).contains(value.utf16.count) && value.unicodeScalars.allSatisfy {
            (0x30...0x39).contains($0.value) || (0x41...0x5A).contains($0.value) ||
                (0x61...0x7A).contains($0.value) || $0 == "-" || $0 == "_" || $0 == "."
        }
    }

    static func validProjectId(_ value: String) -> Bool {
        value == "default" || validIdentifier(value)
    }

    static func validPin(_ value: String) -> Bool {
        value.utf8.count == 64 && value.allSatisfy(\.isHexDigit)
    }

    static func validEndpoint(_ value: String) -> Bool {
        guard let url = URL(string: value), url.scheme?.lowercased() == "https",
              url.host != nil, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil, url.path == "/bridge/v1" else {
            return false
        }
        return url.port == nil || (1...65_535).contains(url.port!)
    }
}
