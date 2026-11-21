import Foundation
import Security
import LocalAuthentication

public struct BridgeDeviceCredential: Equatable, Sendable {
    public let deviceId: String
    public let hmacSecret: Data
    public let ownershipTrustedSigningKey: MobileOwnershipTrustedSigningKey?

    public init(
        deviceId: String,
        hmacSecret: Data,
        ownershipTrustedSigningKey: MobileOwnershipTrustedSigningKey? = nil
    ) throws {
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
        if let ownershipTrustedSigningKey,
           !MobileOwnershipTrustValidation.isValid(ownershipTrustedSigningKey) {
            throw BridgeCredentialStoreError.invalidOwnershipTrustAnchor
        }
        self.deviceId = deviceId
        self.hmacSecret = hmacSecret
        self.ownershipTrustedSigningKey = ownershipTrustedSigningKey
    }
}

public enum BridgeCredentialStoreError: Error, Equatable, Sendable {
    case invalidDeviceIdentifier
    case invalidSecret
    case invalidOwnershipTrustAnchor
    case invalidStoredCredential
    case biometricProtectionUnavailable
    case credentialAlreadyExists
    case credentialNotFound
    case authenticationCancelled
    case authenticationFailed
    case keychainFailure(status: Int32)
}

/// Stores one paired-device HMAC secret and its authenticated ownership trust anchor
/// as one device-only Keychain item, so local revocation cannot leave stale trust behind.
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

        let storedCredential = try BridgeCredentialPersistence.encode(credential)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: credential.deviceId,
            kSecValueData as String: storedCredential,
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
        guard status == errSecSuccess, let storedCredential = result as? Data else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
        return try BridgeCredentialPersistence.decode(storedCredential, expectedDeviceId: deviceId)
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

enum BridgeCredentialPersistence {
    private struct StoredCredential: Codable {
        let schemaVersion: Int
        let deviceId: String
        let hmacSecretBase64: String
        let ownerCoreId: String
        let ownershipSigningKeyEpoch: Int64
        let ownershipSigningPublicKeySpkiBase64: String
        let ownershipSigningPublicKeySha256: String
    }

    static func encode(_ credential: BridgeDeviceCredential) throws -> Data {
        guard let trust = credential.ownershipTrustedSigningKey else {
            // Preserve the legacy 32-byte format for credentials without ownership scope.
            return credential.hmacSecret
        }
        let stored = StoredCredential(
            schemaVersion: 2,
            deviceId: credential.deviceId,
            hmacSecretBase64: credential.hmacSecret.base64EncodedString(),
            ownerCoreId: trust.ownerCoreId,
            ownershipSigningKeyEpoch: trust.signingKeyEpoch,
            ownershipSigningPublicKeySpkiBase64: trust.derEncodedPublicKey.base64EncodedString(),
            ownershipSigningPublicKeySha256: trust.signingPublicKeySha256
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(stored)
    }

    static func decode(_ data: Data, expectedDeviceId: String) throws -> BridgeDeviceCredential {
        if data.count == 32 {
            return try BridgeDeviceCredential(deviceId: expectedDeviceId, hmacSecret: data)
        }
        guard (1...8_192).contains(data.count),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set([
                  "schemaVersion", "deviceId", "hmacSecretBase64", "ownerCoreId",
                  "ownershipSigningKeyEpoch", "ownershipSigningPublicKeySpkiBase64",
                  "ownershipSigningPublicKeySha256"
              ]),
              let stored = try? JSONDecoder().decode(StoredCredential.self, from: data),
              stored.schemaVersion == 2,
              stored.deviceId == expectedDeviceId,
              let secret = Data(base64Encoded: stored.hmacSecretBase64), secret.count == 32,
              secret.base64EncodedString() == stored.hmacSecretBase64,
              let publicKey = Data(base64Encoded: stored.ownershipSigningPublicKeySpkiBase64),
              publicKey.base64EncodedString() == stored.ownershipSigningPublicKeySpkiBase64 else {
            throw BridgeCredentialStoreError.invalidStoredCredential
        }
        let trust = MobileOwnershipTrustedSigningKey(
            ownerCoreId: stored.ownerCoreId,
            signingKeyEpoch: stored.ownershipSigningKeyEpoch,
            signingPublicKeySha256: stored.ownershipSigningPublicKeySha256,
            derEncodedPublicKey: publicKey
        )
        guard MobileOwnershipTrustValidation.isValid(trust) else {
            throw BridgeCredentialStoreError.invalidStoredCredential
        }
        return try BridgeDeviceCredential(
            deviceId: expectedDeviceId,
            hmacSecret: secret,
            ownershipTrustedSigningKey: trust
        )
    }
}

public extension Notification.Name {
    static let glafiraBridgeCredentialDidChange = Notification.Name("com.micronizationlab.glafira.bridgeCredentialDidChange")
}
