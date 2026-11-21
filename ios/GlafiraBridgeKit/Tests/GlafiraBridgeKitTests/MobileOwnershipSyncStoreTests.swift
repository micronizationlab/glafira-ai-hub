import CryptoKit
import Foundation
import XCTest
@testable import GlafiraBridgeKit

final class MobileOwnershipSyncStoreTests: XCTestCase {
    private var directory: URL!
    private var privateKey: P256.Signing.PrivateKey!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("glafira-ownership-sync-\(UUID().uuidString)", isDirectory: true)
        privateKey = P256.Signing.PrivateKey()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if directory != nil { try? FileManager.default.removeItem(at: directory) }
    }

    func testSignedNextGenerationPersistsAndExactRetryIsIdempotent() async throws {
        let store = try MobileOwnershipSyncStore(directory: directory)
        let transfer = try signedTransfer()
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-21T12:01:00Z"))

        let applied = try await store.apply(transfer, trustedKey: trustedKey(), now: now)
        let duplicate = try await store.apply(transfer, trustedKey: trustedKey(), now: now)

        XCTAssertEqual(applied.disposition, .applied)
        XCTAssertEqual(applied.projection.ownerCoreId, "desktop-core-01")
        XCTAssertEqual(applied.projection.ownerGeneration, 8)
        XCTAssertEqual(duplicate.disposition, .exactDuplicate)

        let reopened = try MobileOwnershipSyncStore(directory: directory)
        let persisted = await reopened.projection(taskId: "task-000001", projectId: "project-0001")
        XCTAssertEqual(persisted, applied.projection)
    }

    func testSignedButDivergentPreviousOwnerDoesNotAdvanceProjection() async throws {
        let store = try MobileOwnershipSyncStore(directory: directory)
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-21T12:01:00Z"))
        _ = try await store.apply(try signedTransfer(), trustedKey: trustedKey(), now: now)
        let divergent = try signedTransfer(
            transferId: "transfer-0002",
            nonce: "nonce-000002",
            oldGeneration: 8,
            newGeneration: 9,
            newOwnerCoreId: "desktop-core-02"
        )

        do {
            _ = try await store.apply(divergent, trustedKey: trustedKey(), now: now)
            XCTFail("Divergent old owner must be rejected")
        } catch {
            XCTAssertEqual(error as? MobileOwnershipSyncError, .previousOwnerMismatch)
        }
        let projection = await store.projection(taskId: "task-000001", projectId: "project-0001")
        XCTAssertEqual(projection?.ownerCoreId, "desktop-core-01")
        XCTAssertEqual(projection?.ownerGeneration, 8)
    }

    func testTamperedExpiredAndUntrustedTransfersNeverSeedProjection() async throws {
        let valid = try signedTransfer()
        var tampered = valid
        tampered.signature = Data(repeating: 0, count: 64).base64EncodedString()
        let validNow = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-21T12:01:00Z"))
        let expiredNow = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-21T12:05:00Z"))
        let wrongKey = P256.Signing.PrivateKey().publicKey.derRepresentation
        let wrongTrustedKey = MobileOwnershipTrustedSigningKey(
            ownerCoreId: "android-core-01",
            signingKeyEpoch: 3,
            signingPublicKeySha256: fingerprint(wrongKey),
            derEncodedPublicKey: wrongKey
        )

        for (index, item) in [tampered, valid, valid].enumerated() {
            let isolated = directory.appendingPathComponent("case-\(index)", isDirectory: true)
            let store = try MobileOwnershipSyncStore(directory: isolated)
            do {
                _ = try await store.apply(
                    item,
                    trustedKey: index == 2 ? wrongTrustedKey : trustedKey(),
                    now: index == 1 ? expiredNow : validNow
                )
                XCTFail("Invalid transfer must fail closed")
            } catch {
                // Expected: signature, expiry, and trust failures are distinct fail-closed paths.
            }
            let projection = await store.projection(
                taskId: "task-000001",
                projectId: "project-0001"
            )
            XCTAssertNil(projection)
        }
    }

    func testStrictWireDecoderRejectsUnknownFields() throws {
        let transfer = try signedTransfer()
        let data = try JSONEncoder().encode(transfer)
        XCTAssertEqual(try MobileOwnershipTransferEnvelope.decodeStrict(data), transfer)

        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        object["unreviewedField"] = true
        let unknown = try JSONSerialization.data(withJSONObject: object)
        XCTAssertThrowsError(try MobileOwnershipTransferEnvelope.decodeStrict(unknown))
    }

    func testPersistedHighRiskProjectionWithoutApprovalFailsClosed() throws {
        let record: [String: Any] = [
            "taskId": "task-000001",
            "projectId": "project-0001",
            "ownerCoreId": "desktop-core-01",
            "ownerGeneration": 8,
            "stateVersion": 42,
            "payloadSha256": String(repeating: "a", count: 64),
            "idempotencyKey": "idem-000001",
            "effectRisk": "DESTRUCTIVE",
            "approvalRef": NSNull(),
            "lastTransferId": "transfer-0001",
            "lastNonce": "nonce-000001",
            "updatedAtEpochMs": 1_800_000_000_000
        ]
        let data = try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1,
            "records": [record]
        ])
        try data.write(to: directory.appendingPathComponent(
            "mobile-ownership-projection-v1.json"
        ))

        XCTAssertThrowsError(try MobileOwnershipSyncStore(directory: directory))
    }

    func testBridgeResponseIsCorrelatedProjectBoundAndUsesOutOfBandTrustKey() async throws {
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-21T12:01:00Z"))
        let transfer = try signedTransfer()
        let request = ownershipReadRequest(transferId: transfer.transferId)
        let envelope = String(decoding: try JSONEncoder().encode(transfer), as: UTF8.self)
        let response = BridgeResponse(
            protocolVersion: 1,
            requestId: request.requestId,
            status: .accepted,
            result: ["available": "true", "envelope": envelope]
        )
        let store = try MobileOwnershipSyncStore(directory: directory)

        let applied = try await store.applyBridgeResponse(
            response, for: request, trustedKey: trustedKey(), now: now
        )

        XCTAssertEqual(applied?.projection.ownerCoreId, "desktop-core-01")
        let isolated = directory.appendingPathComponent("mismatch", isDirectory: true)
        let rejectedStore = try MobileOwnershipSyncStore(directory: isolated)
        let mismatched = BridgeResponse(
            protocolVersion: 1,
            requestId: "request-owner-read-02",
            status: .accepted,
            result: response.result
        )
        do {
            _ = try await rejectedStore.applyBridgeResponse(
                mismatched, for: request, trustedKey: trustedKey(), now: now
            )
            XCTFail("Mismatched response correlation must fail")
        } catch {
            XCTAssertEqual(error as? MobileOwnershipSyncError, .invalidBridgeResponse)
        }
        let rejectedProjection = await rejectedStore.projection(
            taskId: "task-000001", projectId: "project-0001"
        )
        XCTAssertNil(rejectedProjection)
    }

    func testBridgeUnavailableResponseRequiresExactFieldsAndDoesNotMutate() async throws {
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-21T12:01:00Z"))
        let request = ownershipReadRequest(transferId: "transfer-0001")
        let store = try MobileOwnershipSyncStore(directory: directory)
        let unavailable = BridgeResponse(
            protocolVersion: 1,
            requestId: request.requestId,
            status: .accepted,
            result: ["available": "false"]
        )

        let missing = try await store.applyBridgeResponse(
            unavailable, for: request, trustedKey: trustedKey(), now: now
        )
        XCTAssertNil(missing)
        let extra = BridgeResponse(
            protocolVersion: 1,
            requestId: request.requestId,
            status: .accepted,
            result: ["available": "false", "envelope": "{}"]
        )
        do {
            _ = try await store.applyBridgeResponse(
                extra, for: request, trustedKey: trustedKey(), now: now
            )
            XCTFail("Unexpected result fields must fail")
        } catch {
            XCTAssertEqual(error as? MobileOwnershipSyncError, .invalidBridgeResponse)
        }
    }

    private func signedTransfer(
        transferId: String = "transfer-0001",
        nonce: String = "nonce-000001",
        oldGeneration: Int64 = 7,
        newGeneration: Int64 = 8,
        newOwnerCoreId: String = "desktop-core-01"
    ) throws -> MobileOwnershipTransferEnvelope {
        let publicKey = privateKey.publicKey.derRepresentation
        var transfer = MobileOwnershipTransferEnvelope(
            schemaVersion: 1,
            transferId: transferId,
            nonce: nonce,
            taskId: "task-000001",
            projectId: "project-0001",
            oldOwnerCoreId: "android-core-01",
            newOwnerCoreId: newOwnerCoreId,
            oldOwnerGeneration: oldGeneration,
            newOwnerGeneration: newGeneration,
            stateVersion: 42,
            payloadSha256: String(repeating: "a", count: 64),
            idempotencyKey: "idem-000001",
            effectRisk: "READ_ONLY",
            approvalRef: nil,
            issuedAt: "2026-11-21T12:00:00Z",
            expiresAt: "2026-11-21T12:05:00Z",
            signingKeyEpoch: 3,
            signingPublicKeySha256: fingerprint(publicKey),
            signatureAlgorithm: "ECDSA_P256_SHA256",
            signatureFormat: "IEEE_P1363_FIXED_64",
            signature: ""
        )
        transfer.signature = try privateKey.signature(
            for: transfer.canonicalSigningData()
        ).rawRepresentation.base64EncodedString()
        return transfer
    }

    private func trustedKey() -> MobileOwnershipTrustedSigningKey {
        let publicKey = privateKey.publicKey.derRepresentation
        return MobileOwnershipTrustedSigningKey(
            ownerCoreId: "android-core-01",
            signingKeyEpoch: 3,
            signingPublicKeySha256: fingerprint(publicKey),
            derEncodedPublicKey: publicKey
        )
    }

    private func ownershipReadRequest(transferId: String) -> BridgeRequest {
        BridgeRequest(
            requestId: "request-owner-read-01",
            idempotencyKey: "idem-owner-read-01",
            deviceId: "device-0001",
            projectId: "project-0001",
            operation: .ownershipTransferRead,
            issuedAtEpochMs: 1_795_262_400_000,
            expiresAtEpochMs: 1_795_262_700_000,
            payload: ["transferId": transferId],
            signatureHex: String(repeating: "0", count: 64)
        )
    }

    private func fingerprint(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
