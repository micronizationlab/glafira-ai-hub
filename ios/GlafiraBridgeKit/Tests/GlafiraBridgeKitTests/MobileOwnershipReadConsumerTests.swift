import CryptoKit
import XCTest
@testable import GlafiraBridgeKit

final class MobileOwnershipReadConsumerTests: XCTestCase {
    private actor CallRecorder {
        private(set) var count = 0
        func record() { count += 1 }
    }

    private var directory: URL!
    private let now: Int64 = 1_800_000_000_000
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    func testUnavailableReadUsesExactBindingAndAcknowledgesOnlyOwnershipWork() async throws {
        let queue = try MobileOwnershipReadQueue(directory: directory.appendingPathComponent("queue"))
        let syncStore = try MobileOwnershipSyncStore(directory: directory.appendingPathComponent("sync"))
        let pairing = try pairingState()
        let scheduled = try await queue.schedule(
            pairingState: pairing,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now
        )
        guard case .enqueued(let work) = scheduled else { return XCTFail("Expected enqueue") }
        let credential = try pairedCredential()
        let recorder = CallRecorder()
        let expectedEndpoint = endpoint
        let expectedPin = pin
        let consumer = MobileOwnershipReadConsumer(
            queue: queue,
            syncStore: syncStore,
            credentialLoader: { deviceId, _ in
                XCTAssertEqual(deviceId, "ios-device-0001")
                return credential
            },
            transport: { endpoint, pin, request, suppliedCredential in
                await recorder.record()
                XCTAssertEqual(endpoint, expectedEndpoint)
                XCTAssertEqual(pin, expectedPin)
                XCTAssertEqual(suppliedCredential, credential)
                XCTAssertEqual(request.operation, .ownershipTransferRead)
                XCTAssertEqual(request.payload, ["transferId": "transfer-0001"])
                return BridgeResponse(
                    protocolVersion: BridgeProtocol.currentVersion,
                    requestId: request.requestId,
                    status: .accepted,
                    result: ["available": "false"]
                )
            },
            requestIdentifier: { "ios-request-0001" }
        )

        let outcome = try await consumer.consumeNext(pairingState: pairing, nowEpochMs: now)

        XCTAssertEqual(outcome, .unavailable(work))
        let transportCount = await recorder.count
        let pending = try await queue.pending(pairingState: pairing, nowEpochMs: now + 1)
        XCTAssertEqual(transportCount, 1)
        XCTAssertEqual(pending, [])
    }

    func testBindingMismatchFailsBeforeNetworkAndLeavesWorkQueued() async throws {
        let queue = try MobileOwnershipReadQueue(directory: directory.appendingPathComponent("queue"))
        let syncStore = try MobileOwnershipSyncStore(directory: directory.appendingPathComponent("sync"))
        let pairing = try pairingState()
        _ = try await queue.schedule(
            pairingState: pairing,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now
        )
        let mismatched = try pairedCredential(endpoint: "https://192.168.1.21:8443/bridge/v1")
        let recorder = CallRecorder()
        let consumer = MobileOwnershipReadConsumer(
            queue: queue,
            syncStore: syncStore,
            credentialLoader: { _, _ in mismatched },
            transport: { _, _, request, _ in
                await recorder.record()
                return BridgeResponse(
                    protocolVersion: BridgeProtocol.currentVersion,
                    requestId: request.requestId,
                    status: .accepted,
                    result: ["available": "false"]
                )
            }
        )

        do {
            _ = try await consumer.consumeNext(pairingState: pairing, nowEpochMs: now)
            XCTFail("Expected exact Hub binding rejection")
        } catch {
            XCTAssertEqual(error as? MobileOwnershipReadConsumerError, .credentialBindingMismatch)
        }
        let transportCount = await recorder.count
        let pending = try await queue.pending(pairingState: pairing, nowEpochMs: now + 1)
        XCTAssertEqual(transportCount, 0)
        XCTAssertEqual(pending.count, 1)
    }

    func testMissingOwnershipScopeFailsBeforeNetwork() async throws {
        let queue = try MobileOwnershipReadQueue(directory: directory.appendingPathComponent("queue"))
        let syncStore = try MobileOwnershipSyncStore(directory: directory.appendingPathComponent("sync"))
        let pairing = try pairingState()
        _ = try await queue.schedule(
            pairingState: pairing,
            projectId: "project-0001",
            transferId: "transfer-0001",
            nowEpochMs: now
        )
        let credential = try pairedCredential(scopes: ["project.read"])
        let consumer = MobileOwnershipReadConsumer(
            queue: queue,
            syncStore: syncStore,
            credentialLoader: { _, _ in credential },
            transport: { _, _, _, _ in
                XCTFail("Transport must not run")
                throw TestError.called
            }
        )

        do {
            _ = try await consumer.consumeNext(pairingState: pairing, nowEpochMs: now)
            XCTFail("Expected scope rejection")
        } catch {
            XCTAssertEqual(error as? MobileOwnershipReadConsumerError, .ownershipReadScopeMissing)
        }
        let pending = try await queue.pending(pairingState: pairing, nowEpochMs: now + 1)
        XCTAssertEqual(pending.count, 1)
    }

    private enum TestError: Error { case called }

    private func pairingState() throws -> MobileHubPairingState {
        try .paired(
            deviceId: "ios-device-0001",
            desktopHubId: "desktop-hub-0001",
            endpoint: endpoint,
            certificateSHA256: pin,
            expiresAtEpochMs: now + 60_000,
            nowEpochMs: now
        )
    }

    private func pairedCredential(
        endpoint: String? = nil,
        scopes: [String] = ["ownership.read"]
    ) throws -> BridgeDeviceCredential {
        let privateKey = P256.Signing.PrivateKey()
        let publicKey = privateKey.publicKey.derRepresentation
        let key = MobileOwnershipTrustedSigningKey(
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
            scopes: scopes,
            enrolledAtEpochMs: now - 1,
            expiresAtEpochMs: now + 60_000
        )
        return try BridgeDeviceCredential(
            deviceId: "ios-device-0001",
            hmacSecret: Data(repeating: 7, count: 32),
            ownershipTrustedSigningKey: key,
            hubBinding: binding
        )
    }
}
