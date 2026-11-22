import Foundation

public enum MobileOwnershipReadConsumerError: Error, Equatable, Sendable {
    case invalidPairingState
    case credentialBindingMismatch
    case ownershipReadScopeMissing
    case ownershipTrustAnchorMissing
    case invalidRequest
}

public enum MobileOwnershipReadOutcome: Equatable, Sendable {
    case noPendingWork
    case unavailable(MobileOwnershipReadWork)
    case synchronized(MobileOwnershipReadWork, MobileOwnershipSyncResult)
}

/// Executes only authenticated, read-only ownership work for the currently paired iOS device.
/// Windows Hub remains authoritative: this consumer cannot issue leases, sign transfers, or
/// authorize execution. Conversation writes use a separate outbox and are never touched here.
public actor MobileOwnershipReadConsumer {
    public typealias CredentialLoader = @Sendable (String, String) throws -> BridgeDeviceCredential
    public typealias Transport = @Sendable (
        String,
        String,
        BridgeRequest,
        BridgeDeviceCredential
    ) async throws -> BridgeResponse
    public typealias RequestIdentifier = @Sendable () -> String

    private let queue: MobileOwnershipReadQueue
    private let syncStore: MobileOwnershipSyncStore
    private let credentialLoader: CredentialLoader
    private let transport: Transport
    private let requestIdentifier: RequestIdentifier

    public init(
        queue: MobileOwnershipReadQueue,
        syncStore: MobileOwnershipSyncStore,
        credentialStore: KeychainBridgeCredentialStore
    ) {
        self.queue = queue
        self.syncStore = syncStore
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
            "ios-ownership-request-" + UUID().uuidString.lowercased()
        }
    }

    public init(
        queue: MobileOwnershipReadQueue,
        syncStore: MobileOwnershipSyncStore,
        credentialLoader: @escaping CredentialLoader,
        transport: @escaping Transport,
        requestIdentifier: @escaping RequestIdentifier = {
            "ios-ownership-request-" + UUID().uuidString.lowercased()
        }
    ) {
        self.queue = queue
        self.syncStore = syncStore
        self.credentialLoader = credentialLoader
        self.transport = transport
        self.requestIdentifier = requestIdentifier
    }

    public func consumeNext(
        pairingState: MobileHubPairingState,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async throws -> MobileOwnershipReadOutcome {
        guard nowEpochMs > 0 else { throw MobileOwnershipReadConsumerError.invalidPairingState }
        let current = pairingState.evaluated(at: nowEpochMs)
        guard current.status == .paired,
              let deviceId = current.deviceId,
              let desktopHubId = current.desktopHubId,
              let endpoint = current.endpoint,
              let certificateSHA256 = current.certificateSHA256,
              let pairingExpiry = current.expiresAtEpochMs,
              pairingExpiry > nowEpochMs else {
            throw MobileOwnershipReadConsumerError.invalidPairingState
        }
        let pending = try await queue.pending(
            pairingState: current,
            nowEpochMs: nowEpochMs
        )
        guard let work = pending.first else { return .noPendingWork }

        let credential = try credentialLoader(
            deviceId,
            "Подтвердите чтение состояния владения GLAFIRA"
        )
        guard credential.deviceId == deviceId,
              let binding = credential.hubBinding,
              binding.desktopHubId == desktopHubId,
              binding.endpoint == endpoint,
              binding.certificateSHA256 == certificateSHA256.lowercased(),
              binding.expiresAtEpochMs == pairingExpiry,
              binding.enrolledAtEpochMs <= nowEpochMs,
              binding.expiresAtEpochMs > nowEpochMs else {
            throw MobileOwnershipReadConsumerError.credentialBindingMismatch
        }
        guard binding.scopes.contains("ownership.read") else {
            throw MobileOwnershipReadConsumerError.ownershipReadScopeMissing
        }
        guard credential.ownershipTrustedSigningKey != nil else {
            throw MobileOwnershipReadConsumerError.ownershipTrustAnchorMissing
        }

        let (expiresAtEpochMs, overflow) = nowEpochMs.addingReportingOverflow(60_000)
        guard !overflow else { throw MobileOwnershipReadConsumerError.invalidRequest }
        let request = BridgeRequest(
            requestId: requestIdentifier(),
            idempotencyKey: work.id,
            deviceId: work.deviceId,
            projectId: work.projectId,
            operation: .ownershipTransferRead,
            issuedAtEpochMs: nowEpochMs,
            expiresAtEpochMs: expiresAtEpochMs,
            payload: ["transferId": work.transferId]
        )
        guard BridgeProtocol.validate(request, nowEpochMs: nowEpochMs) == nil else {
            throw MobileOwnershipReadConsumerError.invalidRequest
        }

        let response = try await transport(
            endpoint,
            certificateSHA256,
            request,
            credential
        )
        let result = try await syncStore.applyBridgeResponse(
            response,
            for: request,
            pairedCredential: credential,
            now: Date(timeIntervalSince1970: Double(nowEpochMs) / 1_000)
        )
        try await queue.complete(work)
        if let result { return .synchronized(work, result) }
        return .unavailable(work)
    }
}
