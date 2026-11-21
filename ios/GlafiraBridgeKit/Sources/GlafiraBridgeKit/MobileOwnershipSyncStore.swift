import CryptoKit
import Foundation

public struct MobileOwnershipTransferEnvelope: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let transferId: String
    public let nonce: String
    public let taskId: String
    public let projectId: String
    public let oldOwnerCoreId: String
    public let newOwnerCoreId: String
    public let oldOwnerGeneration: Int64
    public let newOwnerGeneration: Int64
    public let stateVersion: Int64
    public let payloadSha256: String
    public let idempotencyKey: String
    public let effectRisk: String
    public let approvalRef: String?
    public let issuedAt: String
    public let expiresAt: String
    public let signingKeyEpoch: Int64
    public let signingPublicKeySha256: String
    public let signatureAlgorithm: String
    public let signatureFormat: String
    public var signature: String

    /// Decode the exact flat wire object and reject unreviewed fields before Codable mapping.
    public static func decodeStrict(_ data: Data) throws -> MobileOwnershipTransferEnvelope {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == MobileOwnershipTransferValidation.transferKeys else {
            throw MobileOwnershipSyncError.invalidEnvelope
        }
        let decoded = try JSONDecoder().decode(MobileOwnershipTransferEnvelope.self, from: data)
        _ = try decoded.canonicalSigningData()
        guard (80...256).contains(decoded.signature.utf8.count),
              let signature = Data(base64Encoded: decoded.signature),
              signature.count == 64,
              signature.base64EncodedString() == decoded.signature else {
            throw MobileOwnershipSyncError.invalidEnvelope
        }
        return decoded
    }

    /// Exact v1 bytes covered by the old Core's signature. The signature itself is excluded.
    public func canonicalSigningData() throws -> Data {
        try MobileOwnershipTransferValidation.validateSemantics(self)
        let values = [
            String(schemaVersion), transferId, nonce, taskId, projectId,
            oldOwnerCoreId, newOwnerCoreId, String(oldOwnerGeneration),
            String(newOwnerGeneration), String(stateVersion), payloadSha256,
            idempotencyKey, effectRisk, approvalRef ?? "null", issuedAt,
            expiresAt, String(signingKeyEpoch), signingPublicKeySha256,
            signatureAlgorithm, signatureFormat
        ]
        let canonical = values.map { "\($0.utf16.count):\($0)" }.joined()
        guard let data = canonical.data(using: .utf8) else {
            throw MobileOwnershipSyncError.invalidEnvelope
        }
        return data
    }
}

public struct MobileOwnershipTrustedSigningKey: Equatable, Sendable {
    public let ownerCoreId: String
    public let signingKeyEpoch: Int64
    public let signingPublicKeySha256: String
    /// SubjectPublicKeyInfo DER bytes pinned by the authenticated Hub/Core identity.
    public let derEncodedPublicKey: Data

    public init(
        ownerCoreId: String,
        signingKeyEpoch: Int64,
        signingPublicKeySha256: String,
        derEncodedPublicKey: Data
    ) {
        self.ownerCoreId = ownerCoreId
        self.signingKeyEpoch = signingKeyEpoch
        self.signingPublicKeySha256 = signingPublicKeySha256
        self.derEncodedPublicKey = derEncodedPublicKey
    }
}

public struct MobileOwnershipProjection: Codable, Equatable, Sendable {
    public let taskId: String
    public let projectId: String
    public let ownerCoreId: String
    public let ownerGeneration: Int64
    public let stateVersion: Int64
    public let payloadSha256: String
    public let idempotencyKey: String
    public let effectRisk: String
    public let approvalRef: String?
    public let lastTransferId: String
    public let lastNonce: String
    public let updatedAtEpochMs: Int64
}

public enum MobileOwnershipSyncDisposition: Equatable, Sendable {
    case applied
    case exactDuplicate
}

public struct MobileOwnershipSyncResult: Equatable, Sendable {
    public let disposition: MobileOwnershipSyncDisposition
    public let projection: MobileOwnershipProjection
}

public enum MobileOwnershipSyncError: Error, Equatable, Sendable {
    case invalidEnvelope
    case outsideValidityWindow
    case untrustedSigningKey
    case invalidSignature
    case idempotencyConflict
    case previousOwnerMismatch
    case previousStateMismatch
    case invalidPersistedState
}

/// Durable owner-facing read model for signed task ownership changes.
///
/// Windows Hub remains authoritative. This iOS actor cannot sign a transfer, issue a
/// lease, or authorize execution; it can only advance a local projection after an exact
/// old-owner signature and monotonic generation transition have been verified.
public actor MobileOwnershipSyncStore {
    private struct PersistedEnvelope: Codable {
        var schemaVersion = 1
        var records: [MobileOwnershipProjection] = []
    }

    private let fileURL: URL
    private var records: [String: MobileOwnershipProjection]

    public init(directory: URL, fileManager: FileManager = .default) throws {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("mobile-ownership-projection-v1.json")
        if fileManager.fileExists(atPath: fileURL.path) {
            let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
            guard MobileOwnershipTransferValidation.validPersistedShape(data) else {
                throw MobileOwnershipSyncError.invalidPersistedState
            }
            let persisted = try JSONDecoder().decode(PersistedEnvelope.self, from: data)
            guard persisted.schemaVersion == 1, persisted.records.count <= 100_000 else {
                throw MobileOwnershipSyncError.invalidPersistedState
            }
            var loaded: [String: MobileOwnershipProjection] = [:]
            for projection in persisted.records {
                guard MobileOwnershipTransferValidation.valid(projection: projection) else {
                    throw MobileOwnershipSyncError.invalidPersistedState
                }
                let key = Self.key(taskId: projection.taskId, projectId: projection.projectId)
                guard loaded.updateValue(projection, forKey: key) == nil else {
                    throw MobileOwnershipSyncError.invalidPersistedState
                }
            }
            records = loaded
        } else {
            records = [:]
        }
    }

    public func projection(taskId: String, projectId: String) -> MobileOwnershipProjection? {
        records[Self.key(taskId: taskId, projectId: projectId)]
    }

    @discardableResult
    public func apply(
        _ transfer: MobileOwnershipTransferEnvelope,
        trustedKey: MobileOwnershipTrustedSigningKey,
        now: Date = Date()
    ) throws -> MobileOwnershipSyncResult {
        let canonical = try transfer.canonicalSigningData()
        guard let issuedAt = MobileOwnershipTransferValidation.date(transfer.issuedAt),
              let expiresAt = MobileOwnershipTransferValidation.date(transfer.expiresAt),
              now >= issuedAt, now < expiresAt else {
            throw MobileOwnershipSyncError.outsideValidityWindow
        }
        guard trustedKey.ownerCoreId == transfer.oldOwnerCoreId,
              trustedKey.signingKeyEpoch == transfer.signingKeyEpoch,
              trustedKey.signingPublicKeySha256 == transfer.signingPublicKeySha256,
              MobileOwnershipTransferValidation.sha256Hex(trustedKey.derEncodedPublicKey) ==
                transfer.signingPublicKeySha256 else {
            throw MobileOwnershipSyncError.untrustedSigningKey
        }
        guard let signatureBytes = Data(base64Encoded: transfer.signature),
              signatureBytes.base64EncodedString() == transfer.signature,
              signatureBytes.count == 64,
              let publicKey = try? P256.Signing.PublicKey(
                derRepresentation: trustedKey.derEncodedPublicKey
              ),
              let signature = try? P256.Signing.ECDSASignature(
                rawRepresentation: signatureBytes
              ),
              publicKey.isValidSignature(signature, for: canonical) else {
            throw MobileOwnershipSyncError.invalidSignature
        }

        let recordKey = Self.key(taskId: transfer.taskId, projectId: transfer.projectId)
        if let current = records[recordKey], current.lastTransferId == transfer.transferId {
            guard current.lastNonce == transfer.nonce, current.matchesTarget(transfer) else {
                throw MobileOwnershipSyncError.idempotencyConflict
            }
            return MobileOwnershipSyncResult(disposition: .exactDuplicate, projection: current)
        }
        if let current = records[recordKey] {
            guard current.ownerCoreId == transfer.oldOwnerCoreId,
                  current.ownerGeneration == transfer.oldOwnerGeneration else {
                throw MobileOwnershipSyncError.previousOwnerMismatch
            }
            guard current.stateVersion == transfer.stateVersion,
                  current.payloadSha256 == transfer.payloadSha256,
                  current.idempotencyKey == transfer.idempotencyKey,
                  current.effectRisk == transfer.effectRisk,
                  current.approvalRef == transfer.approvalRef else {
                throw MobileOwnershipSyncError.previousStateMismatch
            }
        }

        let projection = MobileOwnershipProjection(
            taskId: transfer.taskId,
            projectId: transfer.projectId,
            ownerCoreId: transfer.newOwnerCoreId,
            ownerGeneration: transfer.newOwnerGeneration,
            stateVersion: transfer.stateVersion,
            payloadSha256: transfer.payloadSha256,
            idempotencyKey: transfer.idempotencyKey,
            effectRisk: transfer.effectRisk,
            approvalRef: transfer.approvalRef,
            lastTransferId: transfer.transferId,
            lastNonce: transfer.nonce,
            updatedAtEpochMs: Int64(now.timeIntervalSince1970 * 1_000)
        )
        let previous = records.updateValue(projection, forKey: recordKey)
        do {
            try persist()
        } catch {
            if let previous {
                records[recordKey] = previous
            } else {
                records.removeValue(forKey: recordKey)
            }
            throw error
        }
        return MobileOwnershipSyncResult(disposition: .applied, projection: projection)
    }

    private func persist() throws {
        let ordered = records.values.sorted {
            ($0.projectId, $0.taskId) < ($1.projectId, $1.taskId)
        }
        let data = try JSONEncoder().encode(PersistedEnvelope(records: ordered))
        try data.write(to: fileURL, options: [.atomic, .completeFileProtection])
    }

    private static func key(taskId: String, projectId: String) -> String {
        "\(projectId)|\(taskId)"
    }
}

private extension MobileOwnershipProjection {
    func matchesTarget(_ transfer: MobileOwnershipTransferEnvelope) -> Bool {
        ownerCoreId == transfer.newOwnerCoreId &&
            ownerGeneration == transfer.newOwnerGeneration &&
            stateVersion == transfer.stateVersion &&
            payloadSha256 == transfer.payloadSha256 &&
            idempotencyKey == transfer.idempotencyKey &&
            effectRisk == transfer.effectRisk && approvalRef == transfer.approvalRef
    }
}

private enum MobileOwnershipTransferValidation {
    static let transferKeys: Set<String> = [
        "schemaVersion", "transferId", "nonce", "taskId", "projectId", "oldOwnerCoreId",
        "newOwnerCoreId", "oldOwnerGeneration", "newOwnerGeneration", "stateVersion",
        "payloadSha256", "idempotencyKey", "effectRisk", "approvalRef", "issuedAt",
        "expiresAt", "signingKeyEpoch", "signingPublicKeySha256", "signatureAlgorithm",
        "signatureFormat", "signature"
    ]
    private static let projectionKeys: Set<String> = [
        "taskId", "projectId", "ownerCoreId", "ownerGeneration", "stateVersion",
        "payloadSha256", "idempotencyKey", "effectRisk", "approvalRef", "lastTransferId",
        "lastNonce", "updatedAtEpochMs"
    ]
    private static let risks = [
        "READ_ONLY", "DRAFT", "EXTERNAL_SIDE_EFFECT", "FINANCIAL", "DESTRUCTIVE"
    ]
    private static let highRisks = ["EXTERNAL_SIDE_EFFECT", "FINANCIAL", "DESTRUCTIVE"]

    static func validateSemantics(_ value: MobileOwnershipTransferEnvelope) throws {
        guard value.schemaVersion == 1,
              [value.transferId, value.nonce, value.taskId, value.projectId,
               value.oldOwnerCoreId, value.newOwnerCoreId, value.idempotencyKey]
                .allSatisfy(validIdentifier),
              value.oldOwnerCoreId != value.newOwnerCoreId,
              value.oldOwnerGeneration > 0,
              value.oldOwnerGeneration < Int64.max,
              value.newOwnerGeneration == value.oldOwnerGeneration + 1,
              value.stateVersion >= 0,
              validDigest(value.payloadSha256),
              value.signingKeyEpoch > 0,
              validDigest(value.signingPublicKeySha256),
              value.signatureAlgorithm == "ECDSA_P256_SHA256",
              value.signatureFormat == "IEEE_P1363_FIXED_64",
              risks.contains(value.effectRisk),
              date(value.issuedAt) != nil,
              let expires = date(value.expiresAt),
              let issued = date(value.issuedAt), expires > issued else {
            throw MobileOwnershipSyncError.invalidEnvelope
        }
        if highRisks.contains(value.effectRisk) {
            guard let approval = value.approvalRef, validIdentifier(approval) else {
                throw MobileOwnershipSyncError.invalidEnvelope
            }
        } else if let approval = value.approvalRef, !validIdentifier(approval) {
            throw MobileOwnershipSyncError.invalidEnvelope
        }
    }

    static func valid(projection: MobileOwnershipProjection) -> Bool {
        [projection.taskId, projection.projectId, projection.ownerCoreId,
         projection.idempotencyKey, projection.lastTransferId, projection.lastNonce]
            .allSatisfy(validIdentifier) &&
            projection.ownerGeneration > 0 && projection.stateVersion >= 0 &&
            projection.updatedAtEpochMs > 0 && validDigest(projection.payloadSha256) &&
            risks.contains(projection.effectRisk) &&
            (projection.approvalRef == nil || validIdentifier(projection.approvalRef!)) &&
            (!highRisks.contains(projection.effectRisk) || projection.approvalRef != nil)
    }

    static func validPersistedShape(_ data: Data) -> Bool {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(root.keys) == ["schemaVersion", "records"],
              root["schemaVersion"] as? Int == 1,
              let rows = root["records"] as? [[String: Any]], rows.count <= 100_000 else {
            return false
        }
        return rows.allSatisfy { Set($0.keys) == projectionKeys }
    }

    static func date(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let parsed = fractional.date(from: value) { return parsed }
        let wholeSeconds = ISO8601DateFormatter()
        wholeSeconds.formatOptions = [.withInternetDateTime]
        return wholeSeconds.date(from: value)
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func validIdentifier(_ value: String) -> Bool {
        (8...128).contains(value.utf16.count) && value.unicodeScalars.allSatisfy {
            (48...57).contains($0.value) || (65...90).contains($0.value) ||
                (97...122).contains($0.value) || $0 == "-" || $0 == "_" || $0 == "."
        }
    }

    private static func validDigest(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy {
            (48...57).contains($0) || (97...102).contains($0)
        }
    }
}
