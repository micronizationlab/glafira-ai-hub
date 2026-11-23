import CryptoKit
import XCTest
@testable import GlafiraBridgeKit

final class MobileWorkspaceOutboxConsumerTests: XCTestCase {
    private actor Recorder {
        private(set) var requests: [BridgeRequest] = []
        func record(_ request: BridgeRequest) { requests.append(request) }
    }

    private enum TestError: Error { case offline }

    private var directory: URL!
    private let now: Int64 = 1_800_000_000_000
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)
    private let key = Data(repeating: 0x55, count: 32)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("glafira-workspace-consumer-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    func testAcceptedAppendUsesBaseVersionAndExactIdempotencyThenAcknowledges() async throws {
        let (store, entry) = try await preparedStore()
        let credential = try pairedCredential()
        let recorder = Recorder()
        let expectedEndpoint = endpoint
        let expectedPin = pin
        let consumer = MobileWorkspaceOutboxConsumer(
            store: store,
            credentialLoader: { deviceId, _ in
                XCTAssertEqual(deviceId, "ios-device-0001")
                return credential
            },
            transport: { endpoint, pin, request, suppliedCredential in
                await recorder.record(request)
                XCTAssertEqual(endpoint, expectedEndpoint)
                XCTAssertEqual(pin, expectedPin)
                XCTAssertEqual(suppliedCredential, credential)
                XCTAssertEqual(request.operation, .conversationAppend)
                XCTAssertEqual(request.idempotencyKey, entry.operationId)
                XCTAssertEqual(request.payload, [
                    "conversationId": "conversation-0001",
                    "messageText": "Офлайн-сообщение",
                    "baseRemoteVersion": "7"
                ])
                return Self.accepted(request, entry: entry, version: "8", duplicate: false)
            },
            requestIdentifier: { "ios-workspace-request-0001" }
        )

        let outcome = try await consumer.consumeNext(
            pairingState: pairingState(),
            nowEpochMs: now
        )

        XCTAssertEqual(outcome, .synchronized(entry, newRemoteVersion: "8", duplicate: false))
        let recordedRequests = await recorder.requests
        let remainingOutbox = await store.pendingOutbox()
        XCTAssertEqual(recordedRequests.count, 1)
        XCTAssertTrue(remainingOutbox.isEmpty)
        let conversation = try await store.conversation(
            projectId: "project-0001",
            conversationId: "conversation-0001"
        )
        XCTAssertEqual(conversation?.remoteVersion, "8")
        XCTAssertEqual(conversation?.messages.last?.origin, .remote)
    }

    func testDuplicateHubAcknowledgementCompletesLostResponseRetryExactlyOnce() async throws {
        let (store, entry) = try await preparedStore()
        let credential = try pairedCredential()
        let consumer = MobileWorkspaceOutboxConsumer(
            store: store,
            credentialLoader: { _, _ in credential },
            transport: { _, _, request, _ in
                Self.accepted(request, entry: entry, version: "8", duplicate: true)
            }
        )

        let outcome = try await consumer.consumeNext(
            pairingState: pairingState(),
            nowEpochMs: now
        )

        XCTAssertEqual(outcome, .synchronized(entry, newRemoteVersion: "8", duplicate: true))
        let remainingOutbox = await store.pendingOutbox()
        XCTAssertTrue(remainingOutbox.isEmpty)
    }

    func testVersionConflictIsFencedForOwnerWithoutSilentRebase() async throws {
        let (store, entry) = try await preparedStore()
        let credential = try pairedCredential()
        let consumer = MobileWorkspaceOutboxConsumer(
            store: store,
            credentialLoader: { _, _ in credential },
            transport: { _, _, request, _ in
                BridgeResponse(
                    protocolVersion: BridgeProtocol.currentVersion,
                    requestId: request.requestId,
                    status: .failed,
                    result: ["currentVersion": "9"],
                    errorCode: "WORKSPACE_VERSION_CONFLICT"
                )
            }
        )

        let outcome = try await consumer.consumeNext(
            pairingState: pairingState(),
            nowEpochMs: now
        )

        XCTAssertEqual(outcome, .requiresOwnerResolution(entry, currentRemoteVersion: "9"))
        let outbox = await store.pendingOutbox()
        XCTAssertEqual(outbox.first?.state, .requiresOwnerResolution)
        XCTAssertEqual(outbox.first?.baseRemoteVersion, "7")
        let conversation = try await store.conversation(
            projectId: "project-0001",
            conversationId: "conversation-0001"
        )
        XCTAssertEqual(conversation?.remoteVersion, "7")
        XCTAssertEqual(conversation?.messages.last?.origin, .ownerOffline)
    }

    func testMissingCanonicalBaseVersionIsFencedBeforeCredentialOrNetwork() async throws {
        let store = try MobileWorkspaceStore(directory: directory, encryptionKey: key)
        try await store.replaceAuthorizedProjects([project()])
        let entry = try await store.appendOfflineMessage(
            projectId: "project-0001",
            conversationId: "conversation-0001",
            title: "ГЛАФИРА",
            text: "Без серверной версии",
            expectedRemoteVersion: nil,
            createdAtEpochMs: now - 1
        )
        let consumer = MobileWorkspaceOutboxConsumer(
            store: store,
            credentialLoader: { _, _ in
                XCTFail("Credential must not be requested")
                throw TestError.offline
            },
            transport: { _, _, _, _ in
                XCTFail("Transport must not run")
                throw TestError.offline
            }
        )

        let outcome = try await consumer.consumeNext(
            pairingState: pairingState(),
            nowEpochMs: now
        )

        XCTAssertEqual(outcome, .requiresOwnerResolution(entry, currentRemoteVersion: nil))
        let outbox = await store.pendingOutbox()
        XCTAssertEqual(outbox.first?.state, .requiresOwnerResolution)
    }

    func testCredentialBindingMismatchFailsBeforeTransportAndKeepsPending() async throws {
        let (store, _) = try await preparedStore()
        let credential = try pairedCredential(endpoint: "https://192.168.1.21:8443/bridge/v1")
        let consumer = MobileWorkspaceOutboxConsumer(
            store: store,
            credentialLoader: { _, _ in credential },
            transport: { _, _, _, _ in
                XCTFail("Transport must not run")
                throw TestError.offline
            }
        )

        do {
            _ = try await consumer.consumeNext(pairingState: pairingState(), nowEpochMs: now)
            XCTFail("Expected exact Hub binding rejection")
        } catch {
            XCTAssertEqual(error as? MobileWorkspaceOutboxConsumerError, .credentialBindingMismatch)
        }
        let outbox = await store.pendingOutbox()
        XCTAssertEqual(outbox.first?.state, .pending)
    }

    func testTransportFailureKeepsPendingWriteForSameIdempotentRetry() async throws {
        let (store, entry) = try await preparedStore()
        let credential = try pairedCredential()
        let consumer = MobileWorkspaceOutboxConsumer(
            store: store,
            credentialLoader: { _, _ in credential },
            transport: { _, _, _, _ in throw TestError.offline }
        )

        do {
            _ = try await consumer.consumeNext(pairingState: pairingState(), nowEpochMs: now)
            XCTFail("Expected transport failure")
        } catch TestError.offline {
            // The original entry must remain unchanged for an idempotent retry.
        }
        let outbox = await store.pendingOutbox()
        XCTAssertEqual(outbox, [entry])
    }

    private func preparedStore() async throws -> (MobileWorkspaceStore, MobileOutboxEntry) {
        let store = try MobileWorkspaceStore(directory: directory, encryptionKey: key)
        try await store.replaceAuthorizedProjects([project()])
        _ = try await store.applyRemoteConversation(
            projectId: "project-0001",
            conversationId: "conversation-0001",
            title: "ГЛАФИРА",
            remoteVersion: "7",
            messages: []
        )
        let entry = try await store.appendOfflineMessage(
            projectId: "project-0001",
            conversationId: "conversation-0001",
            title: "ГЛАФИРА",
            text: "Офлайн-сообщение",
            expectedRemoteVersion: "7",
            createdAtEpochMs: now - 1
        )
        return (store, entry)
    }

    private func project() -> MobileWorkspaceProject {
        MobileWorkspaceProject(
            id: "project-0001",
            name: "Работа",
            revision: "7",
            updatedAtEpochMs: now - 2
        )
    }

    private func pairingState() throws -> MobileHubPairingState {
        try .paired(
            deviceId: "ios-device-0001",
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint,
            certificateSHA256: pin,
            expiresAtEpochMs: now + 120_000,
            nowEpochMs: now
        )
    }

    private func pairedCredential(endpoint: String? = nil) throws -> BridgeDeviceCredential {
        let privateKey = P256.Signing.PrivateKey()
        let publicKey = privateKey.publicKey.derRepresentation
        let trustedKey = MobileOwnershipTrustedSigningKey(
            ownerCoreId: "owner-core-0001",
            signingKeyEpoch: 1,
            signingPublicKeySha256: SHA256.hash(data: publicKey).map {
                String(format: "%02x", $0)
            }.joined(),
            derEncodedPublicKey: publicKey
        )
        let binding = try BridgeHubBinding(
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint ?? self.endpoint,
            certificateSHA256: pin,
            scopes: ["conversation.append"],
            enrolledAtEpochMs: now - 1_000,
            expiresAtEpochMs: now + 120_000
        )
        return try BridgeDeviceCredential(
            deviceId: "ios-device-0001",
            hmacSecret: Data(repeating: 7, count: 32),
            ownershipTrustedSigningKey: trustedKey,
            hubBinding: binding
        )
    }

    private static func accepted(
        _ request: BridgeRequest,
        entry: MobileOutboxEntry,
        version: String,
        duplicate: Bool
    ) -> BridgeResponse {
        BridgeResponse(
            protocolVersion: BridgeProtocol.currentVersion,
            requestId: request.requestId,
            status: .accepted,
            result: [
                "conversationId": entry.conversationId,
                "version": version,
                "operationId": entry.operationId,
                "duplicate": duplicate ? "true" : "false"
            ]
        )
    }
}
