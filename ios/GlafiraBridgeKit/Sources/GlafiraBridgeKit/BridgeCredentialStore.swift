import Foundation
import Security
import LocalAuthentication

/// Non-secret lifecycle identity of the authoritative Windows Hub enrollment.
public struct BridgeHubBinding: Codable, Equatable, Sendable {
    public let desktopHubId: String
    public let endpoint: String
    public let certificateSHA256: String
    public let scopes: [String]
    public let enrolledAtEpochMs: Int64
    public let expiresAtEpochMs: Int64

    public init(
        desktopHubId: String,
        endpoint: String,
        certificateSHA256: String,
        scopes: [String],
        enrolledAtEpochMs: Int64,
        expiresAtEpochMs: Int64
    ) throws {
        let normalizedScopes = scopes.sorted()
        guard BridgeHubBindingValidation.validIdentifier(desktopHubId),
              BridgeHubBindingValidation.validEndpoint(endpoint),
              BridgeHubBindingValidation.validPin(certificateSHA256),
              !normalizedScopes.isEmpty,
              normalizedScopes.count == Set(normalizedScopes).count,
              normalizedScopes.count <= 32,
              normalizedScopes.allSatisfy(BridgeHubBindingValidation.validScope),
              enrolledAtEpochMs > 0,
              expiresAtEpochMs > enrolledAtEpochMs else {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
        self.desktopHubId = desktopHubId
        self.endpoint = endpoint
        self.certificateSHA256 = certificateSHA256
        self.scopes = normalizedScopes
        self.enrolledAtEpochMs = enrolledAtEpochMs
        self.expiresAtEpochMs = expiresAtEpochMs
    }
}

public struct BridgeDeviceCredential: Equatable, Sendable {
    public let deviceId: String
    public let hmacSecret: Data

    public init(deviceId: String, hmacSecret: Data) throws {
        let units = deviceId.utf16.count
        guard (8...128).contains(units),
              deviceId.unicodeScalars.allSatisfy({
                  $0.value <= 0xFFFF &&
                  (CharacterSet.alphanumerics.contains($0) ||
                   $0 == "-" || $0 == "_" || $0 == ".")
              }) else {
            throw BridgeCredentialStoreError.invalidDeviceIdentifier
        }
        guard hmacSecret.count == 32 else {
            throw BridgeCredentialStoreError.invalidSecret
        }
        self.deviceId = deviceId
        self.hmacSecret = hmacSecret
    }
}

public enum BridgeCredentialStoreError: Error, Equatable, Sendable {
    case invalidDeviceIdentifier
    case invalidSecret
    case invalidHubBinding
    case biometricProtectionUnavailable
    case credentialAlreadyExists
    case credentialNotFound
    case authenticationCancelled
    case authenticationFailed
    case keychainFailure(status: Int32)
}

private enum BridgeHubBindingValidation {
    static func validIdentifier(_ value: String) -> Bool {
        (1...128).contains(value.utf8.count) && value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_")
        }
    }

    static func validEndpoint(_ value: String) -> Bool {
        guard let url = URL(string: value),
              url.scheme?.lowercased() == "https", url.host != nil,
              url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil,
              url.path == "/bridge/v1" else {
            return false
        }
        return url.port == nil || (1...65_535).contains(url.port!)
    }

    static func validPin(_ value: String) -> Bool {
        value.utf8.count == 64 && value.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }

    static func validScope(_ value: String) -> Bool {
        (1...96).contains(value.utf8.count) && value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-" || $0 == "_")
        }
    }
}

/// Stores one paired-device HMAC secret as a device-only Keychain item.
/// The item is bound to the currently enrolled biometric set; adding/removing
/// biometrics invalidates access rather than silently weakening protection.
public final class KeychainBridgeCredentialStore {
    private let service: String

    public init(service: String = "com.micronizationlab.glafira.bridge") {
        self.service = service
    }

    public func store(
        _ credential: BridgeDeviceCredential,
        ownerConfirmationReason: String
    ) async throws {
        try await OwnerBiometricAuthenticator().authenticate(reason: ownerConfirmationReason)

        var accessError: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .biometryCurrentSet,
            &accessError
        ) else {
            throw BridgeCredentialStoreError.biometricProtectionUnavailable
        }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: credential.deviceId,
            kSecValueData as String: credential.hmacSecret,
            kSecAttrAccessControl as String: access,
            kSecUseDataProtectionKeychain as String: true
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            throw BridgeCredentialStoreError.credentialAlreadyExists
        }
        guard status == errSecSuccess else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
    }

    /// Returns account identifiers only; secret bytes are never requested or exposed.
    /// This lets the native shell render paired-device state without a biometric prompt.
    public func listDeviceIds() throws -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecUseDataProtectionKeychain as String: true
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let rows = result as? [[String: Any]] else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
        let identifiers = rows.compactMap { $0[kSecAttrAccount as String] as? String }
        guard identifiers.allSatisfy({ (8...128).contains($0.utf16.count) }) else {
            throw BridgeCredentialStoreError.invalidDeviceIdentifier
        }
        return Array(Set(identifiers)).sorted()
    }

    /// Keychain presents the system biometric prompt. No secret is returned
    /// until the current biometric set has authenticated successfully.
    public func load(
        deviceId: String,
        reason: String,
        context: LAContext = LAContext()
    ) throws -> BridgeDeviceCredential {
        guard !reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw BridgeCredentialStoreError.authenticationFailed
        }

        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseAuthenticationContext as String: context,
            kSecUseOperationPrompt as String: reason,
            kSecUseDataProtectionKeychain as String: true
        ]

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        query.removeAll()

        if status == errSecItemNotFound {
            throw BridgeCredentialStoreError.credentialNotFound
        }
        if status == errSecUserCanceled || status == errSecAuthFailed {
            throw status == errSecUserCanceled
                ? BridgeCredentialStoreError.authenticationCancelled
                : BridgeCredentialStoreError.authenticationFailed
        }
        guard status == errSecSuccess, let secret = result as? Data else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
        return try BridgeDeviceCredential(deviceId: deviceId, hmacSecret: secret)
    }

    /// Deletes only the selected paired device credential. Callers must gate
    /// this operation behind a fresh owner-biometric confirmation.
    public func delete(
        deviceId: String,
        ownerConfirmationReason: String
    ) async throws {
        try await OwnerBiometricAuthenticator().authenticate(reason: ownerConfirmationReason)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
            kSecUseDataProtectionKeychain as String: true
        ]
        let status = SecItemDelete(query as CFDictionary)
        if status == errSecItemNotFound {
            throw BridgeCredentialStoreError.credentialNotFound
        }
        guard status == errSecSuccess else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
    }
}

public extension Notification.Name {
    static let glafiraBridgeCredentialDidChange = Notification.Name("com.micronizationlab.glafira.bridgeCredentialDidChange")
}
