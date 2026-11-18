import Foundation
import CoreFoundation

/// Exact JSON bundle copied from the Android owner approval screen after the
/// owner has approved the pending device and selected its scopes.
/// This is an explicit owner-mediated handoff, not a network grant endpoint.
public struct OwnerApprovedBridgeCredentialBundle: Sendable {
    public let origin: String
    public let deviceId: String
    public let hmacSecret: Data
    public let grantedScopes: Set<String>
    public let serverCertificateSHA256: String

    private init(origin: String, deviceId: String, hmacSecret: Data,
                 grantedScopes: Set<String>, serverCertificateSHA256: String) {
        self.origin = origin
        self.deviceId = deviceId
        self.hmacSecret = hmacSecret
        self.grantedScopes = grantedScopes
        self.serverCertificateSHA256 = serverCertificateSHA256
    }

    public static func parse(
        _ json: Data,
        expectedOrigin: String,
        expectedCertificateSHA256: String,
        requestedScopes: Set<String>
    ) throws -> OwnerApprovedBridgeCredentialBundle {
        guard json.count in 1...8_192,
              let object = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              Set(object.keys) == Set([
                "format", "origin", "deviceId", "hmacSecretBase64Url",
                "grantedScopes", "serverCertificateSha256"
              ]),
              let format = object["format"] as? String,
              format == "GLAFIRA_BRIDGE_CREDENTIAL_V1",
              let origin = object["origin"] as? String,
              let deviceId = object["deviceId"] as? String,
              let secretText = object["hmacSecretBase64Url"] as? String,
              let scopesArray = object["grantedScopes"] as? [String],
              let pin = object["serverCertificateSha256"] as? String else {
            throw BridgeCredentialBundleError.invalidFormat
        }
        guard origin == expectedOrigin,
              pin.caseInsensitiveCompare(expectedCertificateSHA256) == .orderedSame,
              let url = URL(string: origin),
              url.scheme?.lowercased() == "https",
              url.host != nil,
              url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil,
              url.path.isEmpty || url.path == "/",
              pin.utf8.count == 64, pin.allSatisfy({ $0.isHexDigit }) else {
            throw BridgeCredentialBundleError.serverIdentityMismatch
        }
        guard (8...128).contains(deviceId.utf16.count),
              deviceId.unicodeScalars.allSatisfy({
                  $0.value <= 0xFFFF &&
                  (CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_" || $0 == ".")
              }) else {
            throw BridgeCredentialBundleError.invalidDevice
        }
        let scopes = Set(scopesArray)
        guard scopes.count == scopesArray.count,
              !scopes.isEmpty, scopes.isSubset(of: requestedScopes),
              scopes.allSatisfy({ scope in
                  !scope.isEmpty && scope.count <= 96 &&
                  scope.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-" })
              }) else {
            throw BridgeCredentialBundleError.invalidScopes
        }
        var normalized = secretText.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 { normalized += String(repeating: "=", count: 4 - remainder) }
        guard let secret = Data(base64Encoded: normalized), secret.count == 32 else {
            throw BridgeCredentialBundleError.invalidSecret
        }
        return OwnerApprovedBridgeCredentialBundle(
            origin: origin, deviceId: deviceId, hmacSecret: secret,
            grantedScopes: scopes, serverCertificateSHA256: pin.lowercased()
        )
    }
}

public enum BridgeCredentialBundleError: Error, Equatable, Sendable {
    case invalidFormat
    case serverIdentityMismatch
    case invalidDevice
    case invalidScopes
    case invalidSecret
}

/// Persists the exact Android-issued owner bundle only after fresh local biometric
/// confirmation. The secret never enters logs or a general-purpose preferences store.
public enum BridgeCredentialBundleImporter {
    public static func importOwnerBundle(
        _ bundle: OwnerApprovedBridgeCredentialBundle,
        credentialStore: KeychainBridgeCredentialStore
    ) async throws {
        let credential = try BridgeDeviceCredential(
            deviceId: bundle.deviceId,
            hmacSecret: bundle.hmacSecret
        )
        try await credentialStore.store(
            credential,
            ownerConfirmationReason: "Подтвердите импорт одобренного Android-устройства в GLAFIRA"
        )
    }
}
