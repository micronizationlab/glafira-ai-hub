import Foundation
import CryptoKit
import XCTest
@testable import GlafiraBridgeKit

final class BridgePairingTests: XCTestCase {
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)
    private let pairingCode = Data(repeating: 0x2A, count: 32).base64URLForTest()
    private let signingPrivateKey = P256.Signing.PrivateKey()

    private var signingPublicKey: Data { signingPrivateKey.publicKey.derRepresentation }
    private var signingFingerprint: String {
        SHA256.hash(data: signingPublicKey).map { String(format: "%02x", $0) }.joined()
    }

    private func bundle(extra: [String: Any] = [:]) throws -> Data {
        var object: [String: Any] = [
            "version": 1,
            "agentId": "ios-device-0001",
            "endpoint": endpoint,
            "tlsCertificateSha256": pin,
            "ownerCoreId": "owner-core-0001",
            "ownershipSigningKeyEpoch": 7,
            "ownershipSigningPublicKeySpkiBase64": signingPublicKey.base64EncodedString(),
            "ownershipSigningPublicKeySha256": signingFingerprint,
            "ticketId": "0123456789abcdef0123456789abcdef",
            "pairingCode": pairingCode,
            "scopes": ["ownership.read", "project.read"],
            "expiresAtEpochMs": 2_000_000
        ]
        extra.forEach { object[$0.key] = $0.value }
        return try JSONSerialization.data(withJSONObject: object)
    }

    private func invitation() throws -> BridgePairingInvitation {
        try BridgePairingInvitation.parseDesktopHubBundle(bundle(), nowEpochMs: 1_950_000)
    }

    private func enrolledResponse(
        for invitation: BridgePairingInvitation,
        scopes: [String] = ["project.read"],
        keyEpoch: Int64? = nil
    ) -> BridgePairingEnrollmentResponse {
        BridgePairingEnrollmentResponse(
            version: 1,
            status: "ENROLLED",
            errorCode: nil,
            agentId: invitation.agentId,
            enrollmentId: invitation.ticketId,
            desktopHubId: "desktop-hub-0001",
            endpoint: invitation.endpoint,
            tlsCertificateSha256: invitation.certificateSHA256,
            ownerCoreId: invitation.ownershipTrustedSigningKey.ownerCoreId,
            ownershipSigningKeyEpoch: keyEpoch ?? invitation.ownershipTrustedSigningKey.signingKeyEpoch,
            ownershipSigningPublicKeySpkiBase64:
                invitation.ownershipTrustedSigningKey.derEncodedPublicKey.base64EncodedString(),
            ownershipSigningPublicKeySha256:
                invitation.ownershipTrustedSigningKey.signingPublicKeySHA256,
            sharedSecretBase64: Data(repeating: 0x5C, count: 32).base64EncodedString(),
            scopes: scopes,
            enrolledAtEpochMs: 1_940_000,
            expiresAtEpochMs: 3_000_000
        )
    }

    func testParsesExactWindowsDesktopHubInvitation() throws {
        let invitation = try invitation()
        XCTAssertEqual(invitation.endpoint, endpoint)
        XCTAssertEqual(invitation.agentId, "ios-device-0001")
        XCTAssertEqual(invitation.ticketId, "0123456789abcdef0123456789abcdef")
        XCTAssertEqual(invitation.scopes, ["ownership.read", "project.read"])
        XCTAssertEqual(invitation.ownershipTrustedSigningKey.ownerCoreId, "owner-core-0001")
        XCTAssertEqual(invitation.ownershipTrustedSigningKey.signingKeyEpoch, 7)
        XCTAssertEqual(invitation.ownershipTrustedSigningKey.derEncodedPublicKey, signingPublicKey)
    }

    func testRejectsUnknownFieldsExpiredTicketAndWrongHubIdentity() throws {
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(extra: ["untrusted": true]), nowEpochMs: 1_950_000
        ))
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(), nowEpochMs: 2_000_000
        ))
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(extra: ["endpoint": "https://192.168.1.20:8443/bridge/v1/requests"]),
            nowEpochMs: 1_950_000
        ))
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(extra: ["ownershipSigningPublicKeySha256": String(repeating: "0", count: 64)]),
            nowEpochMs: 1_950_000
        ))
        XCTAssertThrowsError(try BridgePairingInvitation.parseDesktopHubBundle(
            bundle(extra: ["ownershipSigningKeyEpoch": 7.5]), nowEpochMs: 1_950_000
        ))
    }

    func testPairingRequestContainsOnlyWindowsHubContractFields() throws {
        let request = BridgePairingRequest(invitation: try invitation())
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any]
        )
        XCTAssertEqual(Set(object.keys), ["version", "ticketId", "pairingCode", "agentId"])
        XCTAssertNil(object["deviceLabel"])
        XCTAssertNil(object["proofHex"])
    }

    func testEnrollmentResponseYieldsOnlyCorrelatedCredential() throws {
        let invitation = try invitation()
        let credential = try enrolledResponse(for: invitation).credential(
            matching: invitation,
            nowEpochMs: 1_950_000
        )
        XCTAssertEqual(credential.deviceId, invitation.agentId)
        XCTAssertEqual(credential.hmacSecret, Data(repeating: 0x5C, count: 32))
        XCTAssertEqual(credential.ownershipTrustedSigningKey, invitation.ownershipTrustedSigningKey)
        XCTAssertEqual(credential.hubBinding?.desktopHubId, "desktop-hub-0001")
        XCTAssertEqual(credential.hubBinding?.endpoint, invitation.endpoint)
        XCTAssertEqual(credential.hubBinding?.certificateSHA256, invitation.certificateSHA256)
        XCTAssertEqual(credential.hubBinding?.scopes, ["project.read"])

        XCTAssertThrowsError(try enrolledResponse(
            for: invitation,
            scopes: ["admin.root"]
        ).credential(matching: invitation, nowEpochMs: 1_950_000))
        XCTAssertThrowsError(try enrolledResponse(
            for: invitation,
            keyEpoch: invitation.ownershipTrustedSigningKey.signingKeyEpoch + 1
        ).credential(matching: invitation, nowEpochMs: 1_950_000))
    }

    func testEnrollmentDecoderRejectsUnknownResponseFields() throws {
        let response: [String: Any] = [
            "version": 1, "status": "FAILED", "errorCode": "PAIRING_REJECTED",
            "agentId": NSNull(), "enrollmentId": NSNull(), "desktopHubId": NSNull(),
            "endpoint": NSNull(), "tlsCertificateSha256": NSNull(),
            "ownerCoreId": NSNull(), "ownershipSigningKeyEpoch": NSNull(),
            "ownershipSigningPublicKeySpkiBase64": NSNull(),
            "ownershipSigningPublicKeySha256": NSNull(),
            "sharedSecretBase64": NSNull(), "scopes": [],
            "enrolledAtEpochMs": NSNull(), "expiresAtEpochMs": NSNull(),
            "unexpected": "value"
        ]
        XCTAssertThrowsError(try BridgePairingEnrollmentResponse.decodeStrict(
            JSONSerialization.data(withJSONObject: response)
        ))
    }

    func testCredentialPersistenceBindsTrustAndHubLifecycleAtomically() throws {
        let invitation = try invitation()
        let credential = try enrolledResponse(for: invitation).credential(
            matching: invitation,
            nowEpochMs: 1_950_000
        )
        let stored = try BridgeCredentialPersistence.encode(credential)
        XCTAssertNotEqual(stored.count, 32)
        XCTAssertEqual(
            try BridgeCredentialPersistence.decode(stored, expectedDeviceId: credential.deviceId),
            credential
        )
        XCTAssertThrowsError(try BridgeCredentialPersistence.decode(
            stored, expectedDeviceId: "ios-device-9999"
        ))

        let metadata = try BridgeHubBindingPersistence.encode(try XCTUnwrap(credential.hubBinding))
        XCTAssertEqual(try BridgeHubBindingPersistence.decode(metadata), credential.hubBinding)
    }

    func testCredentialPersistenceSupportsLegacySecretWithoutInventingTrust() throws {
        let secret = Data(repeating: 0x44, count: 32)
        let credential = try BridgeCredentialPersistence.decode(
            secret,
            expectedDeviceId: "ios-device-0001"
        )
        XCTAssertEqual(credential.hmacSecret, secret)
        XCTAssertNil(credential.ownershipTrustedSigningKey)
        XCTAssertNil(credential.hubBinding)
    }

    func testTransportRequiresExactWindowsHubEndpointAndPin() {
        XCTAssertNoThrow(try PinnedBridgeHTTPSClient(origin: endpoint, certificateSHA256: pin))
        XCTAssertThrowsError(try PinnedBridgeHTTPSClient(
            origin: "https://192.168.1.20:8443", certificateSHA256: pin
        ))
        XCTAssertThrowsError(try PinnedBridgeHTTPSClient(
            origin: "http://192.168.1.20:8443/bridge/v1", certificateSHA256: pin
        ))
    }
}
private extension Data {
    func base64URLForTest() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}
