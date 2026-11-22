import Foundation
import Security
import LocalAuthentication

/// Non-secret Windows Hub binding that is committed atomically with the paired-device secret.
/// It is safe to expose as Keychain attributes for lifecycle rendering, but it never grants
/// authority without the biometric-protected credential stored in the same item.
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
        guard MobileOwnershipReadQueueValidation.validIdentifier(desktopHubId),
              MobileOwnershipReadQueueValidation.validEndpoint(endpoint),
              MobileOwnershipReadQueueValidation.validPin(certificateSHA256),
              !normalizedScopes.isEmpty, normalizedScopes.count == Set(normalizedScopes).count,
              normalizedScopes.count <= 32,
              normalizedScopes.allSatisfy({ scope in
                  (1...128).contains(scope.utf8.count) && scope.allSatisfy {
                      $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-" || $0 == "_")
                  }
              }),
              enrolledAtEpochMs > 0, expiresAtEpochMs > enrolledAtEpochMs else {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
        self.desktopHubId = desktopHubId
        self.endpoint = endpoint
        self.certificateSHA256 = certificateSHA256.lowercased()
        self.scopes = normalizedScopes
        self.enrolledAtEpochMs = enrolledAtEpochMs
        self.expiresAtEpochMs = expiresAtEpochMs
    }
}

public struct BridgeDeviceCredential: Equatable, Sendable {
    public let deviceId: String
    public let hmacSecret: Data
    public let ownershipTrustedSigningKey: MobileOwnershipTrustedSigningKey?
    public let hubBinding: BridgeHubBinding?

    public init(
        deviceId: String,
        hmacSecret: Data,
        ownershipTrustedSigningKey: MobileOwnershipTrustedSigningKey? = nil,
        hubBinding: BridgeHubBinding? = nil
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
    case credentialRevoked
    case authenticationCancelled
    case authenticationFailed
    case keychainFailure(status: Int32)
}

/// Stores one paired-device HMAC secret and its authenticated ownership trust anchor
/// as one device-only Keychain item, so local revocation cannot leave stale trust behind.
/// The item is bound to the currently enrolled biometric set; adding/removing
/// biometrics invalidates access rather than silently weakening protection.
public final class KeychainBridgeCredentialStore: @unchecked Sendable {
    private let service: String
    private var revocationService: String { service + ".revoked" }

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

    /// Atomically replaces an existing credential only after a fresh owner confirmation.
    /// A previous local revocation remains fail-closed until the new credential is committed.
    public func upsert(
        _ credential: BridgeDeviceCredential,
        ownerConfirmationReason: String
    ) async throws {
        try await OwnerBiometricAuthenticator().authenticate(reason: ownerConfirmationReason)
        try write(credential, replacingExisting: true)
        try clearRevocation(deviceId: credential.deviceId)
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
            var attributes: [String: Any] = [kSecValueData as String: storedCredential]
            if let lifecycleMetadata {
                attributes[kSecAttrGeneric as String] = lifecycleMetadata
            } else {
                attributes[kSecAttrGeneric as String] = Data()
            }
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
        if let lifecycleMetadata {
            query[kSecAttrGeneric as String] = lifecycleMetadata
        }

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

    /// Reads only non-secret Keychain attributes and fail-closed revocation markers.
    /// Invalid or legacy metadata is surfaced as `.invalid`, never as an active pairing.
    public func pairingStates(
        nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) throws -> [MobileHubPairingState] {
        guard nowEpochMs > 0 else { throw BridgeCredentialStoreError.invalidHubBinding }
        let credentialRows = try attributeRows(service: service)
        let revokedDeviceIds = Set(try attributeRows(service: revocationService).compactMap {
            $0[kSecAttrAccount as String] as? String
        })
        var states: [String: MobileHubPairingState] = [:]

        for row in credentialRows {
            guard let deviceId = row[kSecAttrAccount as String] as? String,
                  MobileOwnershipReadQueueValidation.validIdentifier(deviceId) else {
                throw BridgeCredentialStoreError.invalidDeviceIdentifier
            }
            if revokedDeviceIds.contains(deviceId) {
                states[deviceId] = .disconnected(.revoked, deviceId: deviceId)
                continue
            }
            guard let metadata = row[kSecAttrGeneric as String] as? Data,
                  !metadata.isEmpty,
                  let binding = try? BridgeHubBindingPersistence.decode(metadata) else {
                states[deviceId] = .disconnected(.invalid, deviceId: deviceId)
                continue
            }
            states[deviceId] = try .paired(
                deviceId: deviceId,
                desktopHubId: binding.desktopHubId,
                endpoint: binding.endpoint,
                certificateSHA256: binding.certificateSHA256,
                expiresAtEpochMs: binding.expiresAtEpochMs,
                nowEpochMs: nowEpochMs
            )
        }
        for deviceId in revokedDeviceIds where states[deviceId] == nil {
            guard MobileOwnershipReadQueueValidation.validIdentifier(deviceId) else {
                throw BridgeCredentialStoreError.invalidDeviceIdentifier
            }
            states[deviceId] = .disconnected(.revoked, deviceId: deviceId)
        }
        return states.keys.sorted().compactMap { states[$0] }
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
        if try isRevoked(deviceId: deviceId) {
            throw BridgeCredentialStoreError.credentialRevoked
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
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
        try clearRevocation(deviceId: deviceId)
    }

    /// Writes the fail-closed marker before deleting the credential. If deletion fails, future
    /// loads still reject the credential and pairing state remains revoked.
    public func revoke(
        deviceId: String,
        ownerConfirmationReason: String
    ) async throws {
        try await OwnerBiometricAuthenticator().authenticate(reason: ownerConfirmationReason)
        try writeRevocation(deviceId: deviceId)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
            kSecUseDataProtectionKeychain as String: true
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
    }

    private func attributeRows(service: String) throws -> [[String: Any]] {
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
        if let rows = result as? [[String: Any]] { return rows }
        if let row = result as? [String: Any] { return [row] }
        throw BridgeCredentialStoreError.invalidStoredCredential
    }

    private func isRevoked(deviceId: String) throws -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: revocationService,
            kSecAttrAccount as String: deviceId,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: true
        ]
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
        return true
    }

    private func writeRevocation(deviceId: String) throws {
        guard MobileOwnershipReadQueueValidation.validIdentifier(deviceId) else {
            throw BridgeCredentialStoreError.invalidDeviceIdentifier
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: revocationService,
            kSecAttrAccount as String: deviceId,
            kSecValueData as String: Data([1]),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecUseDataProtectionKeychain as String: true
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw BridgeCredentialStoreError.keychainFailure(status: status)
        }
    }

    private func clearRevocation(deviceId: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: revocationService,
            kSecAttrAccount as String: deviceId,
            kSecUseDataProtectionKeychain as String: true
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
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
            // Preserve the legacy 32-byte format for credentials without ownership scope.
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
                ownershipSigningPublicKeySha256: trust.signingPublicKeySha256,
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
            ownershipSigningPublicKeySha256: trust.signingPublicKeySha256
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
        let signingPublicKeySha256: String
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
            signingPublicKeySha256 = stored.ownershipSigningPublicKeySha256
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
            signingPublicKeySha256 = stored.ownershipSigningPublicKeySha256
            hubBinding = validatedBinding
        }
        guard deviceId == expectedDeviceId,
              let secret = Data(base64Encoded: secretText), secret.count == 32,
              secret.base64EncodedString() == secretText,
              let publicKey = Data(base64Encoded: signingPublicKeyText),
              publicKey.base64EncodedString() == signingPublicKeyText else {
            throw BridgeCredentialStoreError.invalidStoredCredential
        }
        let trust = MobileOwnershipTrustedSigningKey(
            ownerCoreId: ownerCoreId,
            signingKeyEpoch: signingKeyEpoch,
            signingPublicKeySha256: signingPublicKeySha256,
            derEncodedPublicKey: publicKey
        )
        guard MobileOwnershipTrustValidation.isValid(trust) else {
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

public extension Notification.Name {
    static let glafiraBridgeCredentialDidChange = Notification.Name("com.micronizationlab.glafira.bridgeCredentialDidChange")
}
