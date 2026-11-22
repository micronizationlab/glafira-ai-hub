import CryptoKit
import XCTest
@testable import GlafiraBridgeKit

final class MobileOwnershipDiscoveryClientTests: XCTestCase {
    private actor CallRecorder {
        private(set) var count = 0
        func record() { count += 1 }
    }

    private var directory: URL!
    private let now: Int64 = 1_800_000_000_000
    private let endpoint = "https://hub.example.test:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    func testDiscoveryUsesExactBindingAndQueuesOnlyHubReturnedProjectWork() async throws {
        let queue = try MobileOwnershipReadQueue(directory: directory.appendingPathComponent("queue"))
        let syncStore = try MobileOwnershipSyncStore(directory: directory.appendingPathComponent("sync"))
        let pairing = try pairingState()
        let credential = try pairedCredential()
        let expectedEndpoint = endpoint
        let expectedPin = pin
        let client = MobileOwnershipDiscoveryClient(
            queue: queue,
            syncStore: syncStore,
            credentialLoader: { deviceId, _ in
                XCTAssertEqual(deviceId, "ios-device-0001")
                return credential
            },
            transport: { endpoint, pin, request, suppliedCredential in
                XCTAssertEqual(endpoint, expectedEndpoint)
                XCTAssertEqual(pin, expectedPin)
                XCTAssertEqual(suppliedCredential, credential)
                XCTAssertEqual(request.operation, .ownershipTransferDiscover)
                XCTAssertEqual(request.projectId, "project-0001")
                XCTAssertEqual(request.payload, [:])
                return BridgeResponse(
                    protocolVersion: BridgeProtocol.currentVersion,
                    requestId: request.requestId,
                    status: .accepted,
                    result: ["transferIds": "[\"transfer-0001\",\"transfer-0002\"]"]
                )
            },
            requestIdentifier: { "ios-discovery-0001" }
        )

        let first = try await client.discover(
            pairingState: pairing,
            projectId: "project-0001",
            nowEpochMs: now
        )
        let second = try await client.discover(
            pairingState: pairing,
            projectId: "project-0001",
            nowEpochMs: now + 1
        )

        XCTAssertEqual(first, MobileOwnershipDiscoveryResult(
            discoveredCount: 2,
            enqueuedCount: 2,
            alreadyKnownCount: 0
        ))
        XCTAssertEqual(second, MobileOwnershipDiscoveryResult(
            discoveredCount: 2,
            enqueuedCount: 0,
            alreadyKnownCount: 2
        ))
        let pending = try await queue.pending(pairingState: pairing, nowEpochMs: now + 2)
        XCTAssertEqual(pending.map(\.projectId), ["project-0001", "project-0001"])
        XCTAssertEqual(Set(pending.map(\.transferId)), Set(["transfer-0001", "transfer-0002"]))
    }

    func testMalformedOrDuplicateHubListFailsWithoutChangingQueue() async throws {
        let queue = try MobileOwnershipReadQueue(directory: directory.appendingPathComponent("queue"))
        let syncStore = try MobileOwnershipSyncStore(directory: directory.appendingPathComponent("sync"))
        let pairing = try pairingState()
        let credential = try pairedCredential()
        let client = MobileOwnershipDiscoveryClient(
            queue: queue,
            syncStore: syncStore,
            credentialLoader: { _, _ in credential },
            transport: { _, _, request, _ in
                BridgeResponse(
                    protocolVersion: BridgeProtocol.currentVersion,
                    requestId: request.requestId,
                    status: .accepted,
                    result: ["transferIds": "[\"transfer-0001\",\"transfer-0001\"]"]
                )
            }
        )

        do {
            _ = try await client.discover(
                pairingState: pairing,
                projectId: "project-0001",
                nowEpochMs: now
            )
            XCTFail("Expected duplicate list rejection")
        } catch {
            XCTAssertEqual(error as? MobileOwnershipDiscoveryError, .invalidHubResponse)
        }
        let pending = try await queue.snapshot()
        XCTAssertEqual(pending, [])
    }

    func testBindingMismatchFailsBeforeNetwork() async throws {
        let queue = try MobileOwnershipReadQueue(directory: directory.appendingPathComponent("queue"))
        let syncStore = try MobileOwnershipSyncStore(directory: directory.appendingPathComponent("sync"))
        let pairing = try pairingState()
        let mismatched = try pairedCredential(endpoint: "https://other.example.test:8443/bridge/v1")
        let recorder = CallRecorder()
        let client = MobileOwnershipDiscoveryClient(
            queue: queue,
            syncStore: syncStore,
            credentialLoader: { _, _ in mismatched },
            transport: { _, _, request, _ in
                await recorder.record()
                return BridgeResponse(
                    protocolVersion: BridgeProtocol.currentVersion,
                    requestId: request.requestId,
                    status: .accepted,
                    result: ["transferIds": "[]"]
                )
            }
        )

        do {
            _ = try await client.discover(
                pairingState: pairing,
                projectId: "project-0001",
                nowEpochMs: now
            )
            XCTFail("Expected binding mismatch")
        } catch {
            XCTAssertEqual(error as? MobileOwnershipDiscoveryError, .credentialBindingMismatch)
        }
        let transportCount = await recorder.count
        let pending = try await queue.snapshot()
        XCTAssertEqual(transportCount, 0)
        XCTAssertEqual(pending, [])
    }

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

    private func pairedCredential(endpoint: String? = nil) throws -> BridgeDeviceCredential {
        let publicKey = P256.Signing.PrivateKey().publicKey.derRepresentation
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
            scopes: ["ownership.read"],
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
