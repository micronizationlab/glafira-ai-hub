import Foundation
import Security
import LocalAuthentication

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
    case biometricProtectionUnavailable
    case credentialAlreadyExists
    case credentialNotFound
    case authenticationCancelled
    case authenticationFailed
    case keychainFailure(status: Int32)
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

        context.localizedReason = reason
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseAuthenticationContext as String: context,
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
