import Foundation

public enum MobileOwnershipDiscoveryError: Error, Equatable, Sendable {
    case invalidPairingState
    case credentialBindingMismatch
    case ownershipReadScopeMissing
    case ownershipTrustAnchorMissing
    case invalidRequest
    case invalidHubResponse
}

public struct MobileOwnershipDiscoveryResult: Equatable, Sendable {
    public let discoveredCount: Int
    public let enqueuedCount: Int
    public let alreadyKnownCount: Int

    public init(discoveredCount: Int, enqueuedCount: Int, alreadyKnownCount: Int) {
        self.discoveredCount = discoveredCount
        self.enqueuedCount = enqueuedCount
        self.alreadyKnownCount = alreadyKnownCount
    }
}

/// Discovers transfer IDs only from the authenticated Windows Hub for one already-known
/// project, then creates exact durable read work. It cannot accept an owner-entered transfer
/// ID, sign a transfer, issue a lease, or mutate a project.
public actor MobileOwnershipDiscoveryClient {
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
        credentialLoader = { deviceId, reason in
            try credentialStore.load(deviceId: deviceId, reason: reason)
        }
        transport = { endpoint, pin, request, credential in
            let client = try PinnedBridgeHTTPSClient(
                origin: endpoint,
                certificateSHA256: pin
            )
            return try await client.send(request, credential: credential)
        }
        requestIdentifier = {
            "ios-ownership-discover-" + UUID().uuidString.lowercased()
        }
    }

    public init(
        queue: MobileOwnershipReadQueue,
        syncStore: MobileOwnershipSyncStore,
        credentialLoader: @escaping CredentialLoader,
        transport: @escaping Transport,
        requestIdentifier: @escaping RequestIdentifier = {
            "ios-ownership-discover-" + UUID().uuidString.lowercased()
        }
    ) {
        self.queue = queue
        self.syncStore = syncStore
        self.credentialLoader = credentialLoader
        self.transport = transport
        self.requestIdentifier = requestIdentifier
    }

    public func discover(
        pairingState: MobileHubPairingState,
        projectId: String,
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) async throws -> MobileOwnershipDiscoveryResult {
        guard nowEpochMs > 0 else { throw MobileOwnershipDiscoveryError.invalidPairingState }
        let current = pairingState.evaluated(at: nowEpochMs)
        guard current.status == .paired,
              let deviceId = current.deviceId,
              let desktopHubId = current.desktopHubId,
              let endpoint = current.endpoint,
              let certificateSHA256 = current.certificateSHA256,
              let pairingExpiry = current.expiresAtEpochMs,
              pairingExpiry > nowEpochMs else {
            throw MobileOwnershipDiscoveryError.invalidPairingState
        }

        let credential = try credentialLoader(
            deviceId,
            "Подтвердите поиск изменений владения GLAFIRA"
        )
        guard credential.deviceId == deviceId,
              let binding = credential.hubBinding,
              binding.desktopHubId == desktopHubId,
              binding.endpoint == endpoint,
              binding.certificateSHA256 == certificateSHA256.lowercased(),
              binding.expiresAtEpochMs == pairingExpiry,
              binding.enrolledAtEpochMs <= nowEpochMs,
              binding.expiresAtEpochMs > nowEpochMs else {
            throw MobileOwnershipDiscoveryError.credentialBindingMismatch
        }
        guard binding.scopes.contains("ownership.read") else {
            throw MobileOwnershipDiscoveryError.ownershipReadScopeMissing
        }
        guard credential.ownershipTrustedSigningKey != nil else {
            throw MobileOwnershipDiscoveryError.ownershipTrustAnchorMissing
        }

        let (expiresAtEpochMs, overflow) = nowEpochMs.addingReportingOverflow(60_000)
        guard !overflow else { throw MobileOwnershipDiscoveryError.invalidRequest }
        let request = BridgeRequest(
            requestId: requestIdentifier(),
            idempotencyKey: requestIdentifier(),
            deviceId: deviceId,
            projectId: projectId,
            operation: .ownershipTransferDiscover,
            issuedAtEpochMs: nowEpochMs,
            expiresAtEpochMs: expiresAtEpochMs
        )
        guard BridgeProtocol.validate(request, nowEpochMs: nowEpochMs) == nil else {
            throw MobileOwnershipDiscoveryError.invalidRequest
        }

        let response = try await transport(endpoint, certificateSHA256, request, credential)
        let transferIds = try Self.decodeTransferIds(
            response,
            request: request,
            nowEpochMs: nowEpochMs
        )
        let known = Set((await syncStore.projections(projectId: projectId)).map(\.lastTransferId))
        var enqueued = 0
        var alreadyKnown = 0
        for transferId in transferIds {
            if known.contains(transferId) {
                alreadyKnown += 1
                continue
            }
            switch try await queue.schedule(
                pairingState: current,
                projectId: projectId,
                transferId: transferId,
                nowEpochMs: nowEpochMs
            ) {
            case .enqueued:
                enqueued += 1
            case .alreadyQueued:
                alreadyKnown += 1
            case .blocked:
                throw MobileOwnershipDiscoveryError.invalidPairingState
            }
        }
        return MobileOwnershipDiscoveryResult(
            discoveredCount: transferIds.count,
            enqueuedCount: enqueued,
            alreadyKnownCount: alreadyKnown
        )
    }

    private static func decodeTransferIds(
        _ response: BridgeResponse,
        request: BridgeRequest,
        nowEpochMs: Int64
    ) throws -> [String] {
        guard response.protocolVersion == BridgeProtocol.currentVersion,
              response.requestId == request.requestId,
              response.status == .accepted,
              response.errorCode == nil,
              Set(response.result.keys) == Set(["transferIds"]),
              let encoded = response.result["transferIds"],
              encoded.utf8.count <= 32_768,
              let data = encoded.data(using: .utf8),
              let transferIds = try? JSONDecoder().decode([String].self, from: data),
              transferIds.count <= 100,
              Set(transferIds).count == transferIds.count else {
            throw MobileOwnershipDiscoveryError.invalidHubResponse
        }
        for transferId in transferIds {
            let read = BridgeRequest(
                requestId: request.requestId,
                idempotencyKey: request.idempotencyKey,
                deviceId: request.deviceId,
                projectId: request.projectId,
                operation: .ownershipTransferRead,
                issuedAtEpochMs: request.issuedAtEpochMs,
                expiresAtEpochMs: request.expiresAtEpochMs,
                payload: ["transferId": transferId]
            )
            guard BridgeProtocol.validate(read, nowEpochMs: nowEpochMs) == nil else {
                throw MobileOwnershipDiscoveryError.invalidHubResponse
            }
        }
        return transferIds
    }
}
