import Foundation
import Security
import LocalAuthentication

/// Non-secret lifecycle binding committed atomically with the Windows Hub credential.
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
        guard BridgeCredentialValidation.validIdentifier(desktopHubId, minimum: 1),
              BridgeCredentialValidation.validEndpoint(endpoint),
              BridgeCredentialValidation.validPin(certificateSHA256),
              !normalizedScopes.isEmpty, normalizedScopes.count == Set(normalizedScopes).count,
              normalizedScopes.count <= 32,
              normalizedScopes.allSatisfy(BridgeCredentialValidation.validScope),
              enrolledAtEpochMs > 0, expiresAtEpochMs > enrolledAtEpochMs else {
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
    public let ownershipTrustedSigningKey: BridgeOwnershipTrustedSigningKey?
    public let hubBinding: BridgeHubBinding?

    public init(
        deviceId: String,
        hmacSecret: Data,
        ownershipTrustedSigningKey: BridgeOwnershipTrustedSigningKey? = nil,
        hubBinding: BridgeHubBinding? = nil
    ) throws {
        guard BridgeCredentialValidation.validIdentifier(deviceId, minimum: 8) else {
            throw BridgeCredentialStoreError.invalidDeviceIdentifier
        }
        guard hmacSecret.count == 32 else {
            throw BridgeCredentialStoreError.invalidSecret
        }
        if let ownershipTrustedSigningKey,
           !BridgeOwnershipTrustValidation.isValid(ownershipTrustedSigningKey) {
            throw BridgeCredentialStoreError.invalidOwnershipTrustAnchor
        }
        if hubBinding != nil, ownershipTrustedSigningKey == nil {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
        self.deviceId = deviceId
        self.hmacSecret = hmacSecret
        self.ownershipTrustedSigningKey = ownershipTrustedSigningKey
        self.hubBinding = hubBinding
    }
}

public enum BridgeCredentialStoreError: Error, Equatable, Sendable {
    case invalidDeviceIdentifier
    case invalidSecret
    case invalidOwnershipTrustAnchor
    case invalidHubBinding
    case invalidStoredCredential
    case biometricProtectionUnavailable
    case credentialAlreadyExists
    case credentialNotFound
    case authenticationCancelled
    case authenticationFailed
    case keychainFailure(status: Int32)
}

/// Stores the secret, ownership trust anchor and Hub binding in one device-only Keychain item.
public final class KeychainBridgeCredentialStore: @unchecked Sendable {
    private let service: String

    public init(service: String = "com.micronizationlab.glafira.bridge") {
        self.service = service
    }

    public func store(
        _ credential: BridgeDeviceCredential,
        ownerConfirmationReason: String
    ) async throws {
        try await OwnerBiometricAuthenticator().authenticate(reason: ownerConfirmationReason)
        try write(credential, replacingExisting: false)
    }

    /// Atomically replaces the selected device credential after fresh owner confirmation.
    public func upsert(
        _ credential: BridgeDeviceCredential,
        ownerConfirmationReason: String
    ) async throws {
        try await OwnerBiometricAuthenticator().authenticate(reason: ownerConfirmationReason)
        try write(credential, replacingExisting: true)
    }

    private func write(_ credential: BridgeDeviceCredential, replacingExisting: Bool) throws {
        let storedCredential = try BridgeCredentialPersistence.encode(credential)
        let lifecycleMetadata = try credential.hubBinding.map(BridgeHubBindingPersistence.encode)

        if replacingExisting {
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: credential.deviceId,
                kSecUseDataProtectionKeychain as String: true
            ]
            let attributes: [String: Any] = [
                kSecValueData as String: storedCredential,
                kSecAttrGeneric as String: lifecycleMetadata ?? Data()
            ]
            let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if updateStatus == errSecSuccess { return }
            guard updateStatus == errSecItemNotFound else {
                throw BridgeCredentialStoreError.keychainFailure(status: updateStatus)
            }
        }

        var accessError: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .biometryCurrentSet,
            &accessError
        ) else {
            throw BridgeCredentialStoreError.biometricProtectionUnavailable
        }

        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: credential.deviceId,
            kSecValueData as String: storedCredential,
            kSecAttrAccessControl as String: access,
            kSecUseDataProtectionKeychain as String: true
        ]
        if let lifecycleMetadata { query[kSecAttrGeneric as String] = lifecycleMetadata }

        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            throw BridgeCredentialStoreError.credentialAlreadyExists
        }
        guard status == errSecSuccess else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
    }

    /// Lists identifiers without requesting secret bytes or triggering a biometric prompt.
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
        guard status == errSecSuccess else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
        let rows: [[String: Any]]
        if let many = result as? [[String: Any]] { rows = many }
        else if let one = result as? [String: Any] { rows = [one] }
        else { throw BridgeCredentialStoreError.invalidStoredCredential }
        let identifiers = rows.compactMap { $0[kSecAttrAccount as String] as? String }
        guard identifiers.count == rows.count,
              identifiers.allSatisfy({ BridgeCredentialValidation.validIdentifier($0, minimum: 8) }) else {
            throw BridgeCredentialStoreError.invalidDeviceIdentifier
        }
        return Array(Set(identifiers)).sorted()
    }

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
        if status == errSecItemNotFound { throw BridgeCredentialStoreError.credentialNotFound }
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
        if status == errSecItemNotFound { throw BridgeCredentialStoreError.credentialNotFound }
        guard status == errSecSuccess else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
    }
}

enum BridgeCredentialPersistence {
    private struct StoredCredentialV2: Codable {
        let schemaVersion: Int
        let deviceId: String
        let hmacSecretBase64: String
        let ownerCoreId: String
        let ownershipSigningKeyEpoch: Int64
        let ownershipSigningPublicKeySpkiBase64: String
        let ownershipSigningPublicKeySha256: String
    }

    private struct StoredCredentialV3: Codable {
        let schemaVersion: Int
        let deviceId: String
        let hmacSecretBase64: String
        let ownerCoreId: String
        let ownershipSigningKeyEpoch: Int64
        let ownershipSigningPublicKeySpkiBase64: String
        let ownershipSigningPublicKeySha256: String
        let hubBinding: BridgeHubBinding
    }

    static func encode(_ credential: BridgeDeviceCredential) throws -> Data {
        guard let trust = credential.ownershipTrustedSigningKey else {
            return credential.hmacSecret
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        if let binding = credential.hubBinding {
            return try encoder.encode(StoredCredentialV3(
                schemaVersion: 3,
                deviceId: credential.deviceId,
                hmacSecretBase64: credential.hmacSecret.base64EncodedString(),
                ownerCoreId: trust.ownerCoreId,
                ownershipSigningKeyEpoch: trust.signingKeyEpoch,
                ownershipSigningPublicKeySpkiBase64: trust.derEncodedPublicKey.base64EncodedString(),
                ownershipSigningPublicKeySha256: trust.signingPublicKeySHA256,
                hubBinding: binding
            ))
        }
        return try encoder.encode(StoredCredentialV2(
            schemaVersion: 2,
            deviceId: credential.deviceId,
            hmacSecretBase64: credential.hmacSecret.base64EncodedString(),
            ownerCoreId: trust.ownerCoreId,
            ownershipSigningKeyEpoch: trust.signingKeyEpoch,
            ownershipSigningPublicKeySpkiBase64: trust.derEncodedPublicKey.base64EncodedString(),
            ownershipSigningPublicKeySha256: trust.signingPublicKeySHA256
        ))
    }

    static func decode(_ data: Data, expectedDeviceId: String) throws -> BridgeDeviceCredential {
        if data.count == 32 {
            return try BridgeDeviceCredential(deviceId: expectedDeviceId, hmacSecret: data)
        }
        guard (1...8_192).contains(data.count),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let schemaVersion = object["schemaVersion"] as? Int,
              schemaVersion == 2 || schemaVersion == 3 else {
            throw BridgeCredentialStoreError.invalidStoredCredential
        }
        let decoder = JSONDecoder()
        let commonKeys = Set([
            "schemaVersion", "deviceId", "hmacSecretBase64", "ownerCoreId",
            "ownershipSigningKeyEpoch", "ownershipSigningPublicKeySpkiBase64",
            "ownershipSigningPublicKeySha256"
        ])
        let deviceId: String
        let secretText: String
        let ownerCoreId: String
        let signingKeyEpoch: Int64
        let signingPublicKeyText: String
        let signingPublicKeySHA256: String
        let hubBinding: BridgeHubBinding?
        if schemaVersion == 2 {
            guard Set(object.keys) == commonKeys,
                  let stored = try? decoder.decode(StoredCredentialV2.self, from: data) else {
                throw BridgeCredentialStoreError.invalidStoredCredential
            }
            deviceId = stored.deviceId
            secretText = stored.hmacSecretBase64
            ownerCoreId = stored.ownerCoreId
            signingKeyEpoch = stored.ownershipSigningKeyEpoch
            signingPublicKeyText = stored.ownershipSigningPublicKeySpkiBase64
            signingPublicKeySHA256 = stored.ownershipSigningPublicKeySha256
            hubBinding = nil
        } else {
            guard Set(object.keys) == commonKeys.union(["hubBinding"]),
                  BridgeHubBindingPersistence.validJSONObject(object["hubBinding"]),
                  let stored = try? decoder.decode(StoredCredentialV3.self, from: data),
                  let validatedBinding = try? BridgeHubBinding(
                    desktopHubId: stored.hubBinding.desktopHubId,
                    endpoint: stored.hubBinding.endpoint,
                    certificateSHA256: stored.hubBinding.certificateSHA256,
                    scopes: stored.hubBinding.scopes,
                    enrolledAtEpochMs: stored.hubBinding.enrolledAtEpochMs,
                    expiresAtEpochMs: stored.hubBinding.expiresAtEpochMs
                  ), validatedBinding == stored.hubBinding else {
                throw BridgeCredentialStoreError.invalidStoredCredential
            }
            deviceId = stored.deviceId
            secretText = stored.hmacSecretBase64
            ownerCoreId = stored.ownerCoreId
            signingKeyEpoch = stored.ownershipSigningKeyEpoch
            signingPublicKeyText = stored.ownershipSigningPublicKeySpkiBase64
            signingPublicKeySHA256 = stored.ownershipSigningPublicKeySha256
            hubBinding = validatedBinding
        }
        guard deviceId == expectedDeviceId,
              let secret = Data(base64Encoded: secretText), secret.count == 32,
              secret.base64EncodedString() == secretText,
              let publicKey = Data(base64Encoded: signingPublicKeyText),
              publicKey.base64EncodedString() == signingPublicKeyText else {
            throw BridgeCredentialStoreError.invalidStoredCredential
        }
        let trust = BridgeOwnershipTrustedSigningKey(
            ownerCoreId: ownerCoreId,
            signingKeyEpoch: signingKeyEpoch,
            signingPublicKeySHA256: signingPublicKeySHA256,
            derEncodedPublicKey: publicKey
        )
        guard BridgeOwnershipTrustValidation.isValid(trust) else {
            throw BridgeCredentialStoreError.invalidStoredCredential
        }
        return try BridgeDeviceCredential(
            deviceId: expectedDeviceId,
            hmacSecret: secret,
            ownershipTrustedSigningKey: trust,
            hubBinding: hubBinding
        )
    }
}

enum BridgeHubBindingPersistence {
    private struct Envelope: Codable {
        let schemaVersion: Int
        let binding: BridgeHubBinding
    }

    static func encode(_ binding: BridgeHubBinding) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(Envelope(schemaVersion: 1, binding: binding))
    }

    static func decode(_ data: Data) throws -> BridgeHubBinding {
        guard (1...4_096).contains(data.count),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set(["schemaVersion", "binding"]),
              validJSONObject(object["binding"]),
              let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.schemaVersion == 1,
              let validated = try? BridgeHubBinding(
                desktopHubId: envelope.binding.desktopHubId,
                endpoint: envelope.binding.endpoint,
                certificateSHA256: envelope.binding.certificateSHA256,
                scopes: envelope.binding.scopes,
                enrolledAtEpochMs: envelope.binding.enrolledAtEpochMs,
                expiresAtEpochMs: envelope.binding.expiresAtEpochMs
              ), validated == envelope.binding else {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
        return validated
    }

    static func validJSONObject(_ value: Any?) -> Bool {
        guard let object = value as? [String: Any] else { return false }
        return Set(object.keys) == Set([
            "desktopHubId", "endpoint", "certificateSHA256", "scopes",
            "enrolledAtEpochMs", "expiresAtEpochMs"
        ])
    }
}

private enum BridgeCredentialValidation {
    static func validIdentifier(_ value: String, minimum: Int) -> Bool {
        (minimum...128).contains(value.utf8.count) && value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == ".")
        }
    }

    static func validEndpoint(_ value: String) -> Bool {
        guard let url = URL(string: value), url.scheme?.lowercased() == "https",
              url.host != nil, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil, url.path == "/bridge/v1" else {
            return false
        }
        return url.port == nil || (1...65_535).contains(url.port!)
    }

    static func validPin(_ value: String) -> Bool {
        value.utf8.count == 64 && value.allSatisfy({ $0.isHexDigit && !$0.isUppercase })
    }

    static func validScope(_ value: String) -> Bool {
        (1...96).contains(value.utf8.count) && value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-" || $0 == "_")
        }
    }
}

public extension Notification.Name {
    static let glafiraBridgeCredentialDidChange = Notification.Name("com.micronizationlab.glafira.bridgeCredentialDidChange")
}
