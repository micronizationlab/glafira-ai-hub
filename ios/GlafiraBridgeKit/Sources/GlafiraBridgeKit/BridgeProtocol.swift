import Foundation
import CryptoKit

public enum BridgeOperation: String, Codable, CaseIterable, Sendable {
    case projectRead = "PROJECT_READ"
    case conversationRead = "CONVERSATION_READ"
    case conversationAppend = "CONVERSATION_APPEND"
    case approvalRequest = "APPROVAL_REQUEST"
    case ownershipTransferRead = "OWNERSHIP_TRANSFER_READ"
}

public enum BridgeStatus: String, Codable, Sendable {
    case accepted = "ACCEPTED"
    case pendingApproval = "PENDING_APPROVAL"
    case succeeded = "SUCCEEDED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"
}

public struct BridgeRequest: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let requestId: String
    public let idempotencyKey: String
    public let deviceId: String
    public let projectId: String
    public let operation: BridgeOperation
    public let issuedAtEpochMs: Int64
    public let expiresAtEpochMs: Int64
    public let payload: [String: String]
    public var signatureHex: String

    public init(
        protocolVersion: Int = BridgeProtocol.currentVersion,
        requestId: String,
        idempotencyKey: String,
        deviceId: String,
        projectId: String,
        operation: BridgeOperation,
        issuedAtEpochMs: Int64,
        expiresAtEpochMs: Int64,
        payload: [String: String] = [:],
        signatureHex: String = ""
    ) {
        self.protocolVersion = protocolVersion
        self.requestId = requestId
        self.idempotencyKey = idempotencyKey
        self.deviceId = deviceId
        self.projectId = projectId
        self.operation = operation
        self.issuedAtEpochMs = issuedAtEpochMs
        self.expiresAtEpochMs = expiresAtEpochMs
        self.payload = payload
        self.signatureHex = signatureHex
    }
}

public struct BridgeResponse: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let requestId: String
    public let status: BridgeStatus
    public let result: [String: String]
    public let errorCode: String?

    public init(
        protocolVersion: Int,
        requestId: String,
        status: BridgeStatus,
        result: [String: String] = [:],
        errorCode: String? = nil
    ) {
        self.protocolVersion = protocolVersion
        self.requestId = requestId
        self.status = status
        self.result = result
        self.errorCode = errorCode
    }
}

public enum BridgeValidationError: Error, Equatable, Sendable {
    case unsupportedVersion
    case invalidIdentifier
    case invalidTimeWindow
    case expiredRequest
    case requestTooFarInFuture
    case payloadTooLarge
    case unknownPayloadField
    case missingPayloadField
    case invalidPayloadValue
    case invalidSecret
    case invalidSignature
}

public enum BridgeProtocol {
    public static let currentVersion = 1
    public static let maximumTTLMilliseconds: Int64 = 5 * 60 * 1_000
    public static let maximumFutureSkewMilliseconds: Int64 = 30 * 1_000
    public static let maximumPayloadUTF16Units = 16_384

    private static let requiredFields: [BridgeOperation: Set<String>] = [
        .projectRead: [],
        .conversationRead: ["conversationId"],
        .conversationAppend: ["conversationId", "messageText"],
        .approvalRequest: ["toolId", "actionSummary"],
        .ownershipTransferRead: ["transferId"]
    ]

    public static func validate(
        _ request: BridgeRequest,
        nowEpochMs: Int64
    ) -> BridgeValidationError? {
        guard request.protocolVersion == currentVersion else { return .unsupportedVersion }

        guard [request.requestId, request.idempotencyKey, request.deviceId]
            .allSatisfy(isValidIdentifier),
              request.projectId == "default" || isValidIdentifier(request.projectId) else {
            return .invalidIdentifier
        }

        let (ttl, overflow) = request.expiresAtEpochMs.subtractingReportingOverflow(request.issuedAtEpochMs)
        guard !overflow,
              request.issuedAtEpochMs > 0,
              request.expiresAtEpochMs > request.issuedAtEpochMs,
              ttl <= maximumTTLMilliseconds else { return .invalidTimeWindow }

        let (latestIssuedAt, futureOverflow) = nowEpochMs.addingReportingOverflow(maximumFutureSkewMilliseconds)
        guard futureOverflow || request.issuedAtEpochMs <= latestIssuedAt else {
            return .requestTooFarInFuture
        }
        guard request.expiresAtEpochMs > nowEpochMs else { return .expiredRequest }

        let payloadUnits = request.payload.reduce(0) {
            $0 + $1.key.utf16.count + $1.value.utf16.count
        }
        guard payloadUnits <= maximumPayloadUTF16Units else { return .payloadTooLarge }

        let allowed = requiredFields[request.operation, default: []]
        guard request.payload.keys.allSatisfy(allowed.contains) else { return .unknownPayloadField }
        guard allowed.isSubset(of: Set(request.payload.keys)) else { return .missingPayloadField }
        guard request.payload.values.allSatisfy({
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            $0.utf16.count <= maximumPayloadUTF16Units
        }) else { return .invalidPayloadValue }

        if request.operation == .ownershipTransferRead,
           !isValidIdentifier(request.payload["transferId"] ?? "") {
            return .invalidPayloadValue
        }

        return nil
    }

    public static func sign(_ request: BridgeRequest, secret: Data) throws -> BridgeRequest {
        guard secret.count >= 32 else { throw BridgeValidationError.invalidSecret }
        var signed = request
        signed.signatureHex = HMAC<SHA256>.authenticationCode(
            for: canonicalBytes(request),
            using: SymmetricKey(data: secret)
        ).map { String(format: "%02x", $0) }.joined()
        return signed
    }

    public static func verifySignature(_ request: BridgeRequest, secret: Data) -> Bool {
        guard secret.count >= 32,
              let supplied = decodeHex(request.signatureHex),
              supplied.count == 32 else { return false }
        let unsigned = HMAC<SHA256>.isValidAuthenticationCode(
            supplied,
            authenticating: canonicalBytes(request),
            using: SymmetricKey(data: secret)
        )
        return unsigned
    }

    /// Matches Kotlin's length-prefixed UTF-16 canonical envelope exactly.
    public static func canonicalBytes(_ request: BridgeRequest) -> Data {
        var fields = [
            String(request.protocolVersion),
            request.requestId,
            request.idempotencyKey,
            request.deviceId,
            request.projectId,
            request.operation.rawValue,
            String(request.issuedAtEpochMs),
            String(request.expiresAtEpochMs)
        ]
        for key in request.payload.keys.sorted() {
            fields.append(key)
            fields.append(request.payload[key] ?? "")
        }
        let canonical = fields.map { "\($0.utf16.count):\($0)" }.joined()
        return Data(canonical.utf8)
    }

    private static func isValidIdentifier(_ value: String) -> Bool {
        let units = value.utf16.count
        guard (8...128).contains(units) else { return false }
        return value.unicodeScalars.allSatisfy {
            (0x30...0x39).contains($0.value) ||
            (0x41...0x5A).contains($0.value) ||
            (0x61...0x7A).contains($0.value) ||
            $0 == "-" || $0 == "_" || $0 == "."
        }
    }

    private static func decodeHex(_ value: String) -> Data? {
        guard value.utf8.count == 64 else { return nil }
        var bytes = Data()
        var index = value.startIndex
        for _ in 0..<32 {
            let next = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        return bytes
    }
}
