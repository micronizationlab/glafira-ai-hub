import Foundation

public enum MobileWorkspaceOutboxConsumerError: Error, Equatable, Sendable {
    case invalidPairingState
    case credentialBindingMismatch
    case conversationAppendScopeMissing
    case invalidRequest
    case invalidResponse
}

public enum MobileWorkspaceOutboxOutcome: Equatable, Sendable {
    case noPendingWork
    case synchronized(MobileOutboxEntry, newRemoteVersion: String, duplicate: Bool)
    case requiresOwnerResolution(MobileOutboxEntry, currentRemoteVersion: String?)
    case unavailable(MobileOutboxEntry, errorCode: String?)
}

/// Sends one durable iOS conversation write to the authoritative Windows Hub.
/// A local message is removed from the outbox only after an exact correlated Hub
/// acknowledgement. Conflicts are fenced for an explicit owner decision and are
/// never silently rebased.
public actor MobileWorkspaceOutboxConsumer {
    public typealias CredentialLoader = @Sendable (String, String) throws -> BridgeDeviceCredential
    public typealias Transport = @Sendable (
        String,
        String,
        BridgeRequest,
        BridgeDeviceCredential
    ) async throws -> BridgeResponse
    public typealias RequestIdentifier = @Sendable () -> String

    private let store: MobileWorkspaceStore
    private let credentialLoader: CredentialLoader
    private let transport: Transport
    private let requestIdentifier: RequestIdentifier

    public init(
        store: MobileWorkspaceStore,
        credentialStore: KeychainBridgeCredentialStore
    ) {
        self.store = store
        self.credentialLoader = { deviceId, reason in
            try credentialStore.load(deviceId: deviceId, reason: reason)
        }
        self.transport = { endpoint, pin, request, credential in
            let client = try PinnedBridgeHTTPSClient(
                origin: endpoint,
                certificateSHA256: pin
            )
            return try await client.send(request, credential: credential)
        }
        self.requestIdentifier = {
            "ios-workspace-request-" + UUID().uuidString.lowercased()
        }
    }

    public init(
        store: MobileWorkspaceStore,
        credentialLoader: @escaping CredentialLoader,
        transport: @escaping Transport,
        requestIdentifier: @escaping RequestIdentifier = {
            "ios-workspace-request-" + UUID().uuidString.lowercased()
        }
    ) {
        self.store = store
        self.credentialLoader = credentialLoader
        self.transport = transport
        self.requestIdentifier = requestIdentifier
    }

    public func consumeNext(
        pairingState: MobileHubPairingState,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async throws -> MobileWorkspaceOutboxOutcome {
        guard nowEpochMs > 0 else {
            throw MobileWorkspaceOutboxConsumerError.invalidPairingState
        }
        let current = pairingState.evaluated(at: nowEpochMs)
        guard current.status == .paired,
              let deviceId = current.deviceId,
              let desktopHubId = current.desktopHubId,
              let endpoint = current.endpoint,
              let certificateSHA256 = current.certificateSHA256,
              let pairingExpiry = current.expiresAtEpochMs,
              pairingExpiry > nowEpochMs else {
            throw MobileWorkspaceOutboxConsumerError.invalidPairingState
        }

        let pending = await store.pendingOutbox()
            .filter { $0.state == .pending }
        guard let entry = pending.first else { return .noPendingWork }

        guard let baseRemoteVersion = entry.baseRemoteVersion,
              Self.isCanonicalPositiveVersion(baseRemoteVersion) else {
            try await store.requireOwnerResolution(operationId: entry.operationId)
            return .requiresOwnerResolution(entry, currentRemoteVersion: nil)
        }

        let credential = try credentialLoader(
            deviceId,
            "Подтвердите отправку сообщения GLAFIRA"
        )
        guard credential.deviceId == deviceId,
              let binding = credential.hubBinding,
              binding.desktopHubId == desktopHubId,
              binding.endpoint == endpoint,
              binding.certificateSHA256 == certificateSHA256.lowercased(),
              binding.expiresAtEpochMs == pairingExpiry,
              binding.enrolledAtEpochMs <= nowEpochMs,
              binding.expiresAtEpochMs > nowEpochMs else {
            throw MobileWorkspaceOutboxConsumerError.credentialBindingMismatch
        }
        guard binding.scopes.contains("conversation.append") else {
            throw MobileWorkspaceOutboxConsumerError.conversationAppendScopeMissing
        }

        let (expiresAtEpochMs, overflow) = nowEpochMs.addingReportingOverflow(60_000)
        guard !overflow else { throw MobileWorkspaceOutboxConsumerError.invalidRequest }
        let request = BridgeRequest(
            requestId: requestIdentifier(),
            idempotencyKey: entry.operationId,
            deviceId: deviceId,
            projectId: entry.projectId,
            operation: .conversationAppend,
            issuedAtEpochMs: nowEpochMs,
            expiresAtEpochMs: expiresAtEpochMs,
            payload: [
                "conversationId": entry.conversationId,
                "messageText": entry.text,
                "baseRemoteVersion": baseRemoteVersion
            ]
        )
        guard BridgeProtocol.validate(request, nowEpochMs: nowEpochMs) == nil else {
            throw MobileWorkspaceOutboxConsumerError.invalidRequest
        }

        let response = try await transport(
            endpoint,
            certificateSHA256,
            request,
            credential
        )
        guard response.protocolVersion == BridgeProtocol.currentVersion,
              response.requestId == request.requestId else {
            throw MobileWorkspaceOutboxConsumerError.invalidResponse
        }

        if response.status == .accepted || response.status == .succeeded {
            guard response.errorCode == nil,
                  response.result["operationId"] == entry.operationId,
                  response.result["conversationId"] == entry.conversationId,
                  let version = response.result["version"],
                  Self.isCanonicalPositiveVersion(version),
                  let duplicateText = response.result["duplicate"],
                  let duplicate = Self.parseBoolean(duplicateText) else {
                throw MobileWorkspaceOutboxConsumerError.invalidResponse
            }
            try await store.acknowledge(
                operationId: entry.operationId,
                newRemoteVersion: version
            )
            return .synchronized(entry, newRemoteVersion: version, duplicate: duplicate)
        }

        if response.status == .failed,
           response.errorCode == "WORKSPACE_VERSION_CONFLICT" {
            let currentVersion = response.result["currentVersion"]
            guard currentVersion == nil || Self.isCanonicalPositiveVersion(currentVersion!) else {
                throw MobileWorkspaceOutboxConsumerError.invalidResponse
            }
            try await store.requireOwnerResolution(operationId: entry.operationId)
            return .requiresOwnerResolution(entry, currentRemoteVersion: currentVersion)
        }
        if response.status == .failed,
           response.errorCode == "WORKSPACE_IDEMPOTENCY_CONFLICT" {
            try await store.requireOwnerResolution(operationId: entry.operationId)
            return .requiresOwnerResolution(entry, currentRemoteVersion: nil)
        }
        return .unavailable(entry, errorCode: response.errorCode)
    }

    private static func isCanonicalPositiveVersion(_ value: String) -> Bool {
        value.first.map { $0 >= "1" && $0 <= "9" } == true &&
            value.allSatisfy { $0 >= "0" && $0 <= "9" } &&
            Int64(value).map { $0 > 0 } == true
    }

    private static func parseBoolean(_ value: String) -> Bool? {
        switch value {
        case "true": return true
        case "false": return false
        default: return nil
        }
    }
}
