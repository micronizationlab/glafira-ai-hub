import Foundation
import Security

public enum MobileWorkspaceEncryptionKeyStoreError: Error, Equatable, Sendable {
    case keychainFailure(status: OSStatus)
    case invalidStoredKey
    case randomGenerationFailed(status: OSStatus)
}

/// Device-only key for the offline workspace cache. It is separate from the
/// paired Hub credential and never crosses the Bridge transport.
public final class MobileWorkspaceEncryptionKeyStore: @unchecked Sendable {
    private let service: String
    private let account = "mobile-workspace-v1"

    public init(service: String = "com.micronizationlab.glafira.mobile-workspace") {
        self.service = service
    }

    public func loadOrCreate() throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: true
        ]
        var result: CFTypeRef?
        let readStatus = SecItemCopyMatching(query as CFDictionary, &result)
        if readStatus == errSecSuccess {
            guard let key = result as? Data, key.count == 32 else {
                throw MobileWorkspaceEncryptionKeyStoreError.invalidStoredKey
            }
            return key
        }
        guard readStatus == errSecItemNotFound else {
            throw MobileWorkspaceEncryptionKeyStoreError.keychainFailure(status: readStatus)
        }

        var key = Data(count: 32)
        let randomStatus = key.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, 32, bytes.baseAddress!)
        }
        guard randomStatus == errSecSuccess else {
            throw MobileWorkspaceEncryptionKeyStoreError.randomGenerationFailed(status: randomStatus)
        }
        let insert: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecUseDataProtectionKeychain as String: true
        ]
        let insertStatus = SecItemAdd(insert as CFDictionary, nil)
        if insertStatus == errSecDuplicateItem {
            return try loadOrCreate()
        }
        guard insertStatus == errSecSuccess else {
            throw MobileWorkspaceEncryptionKeyStoreError.keychainFailure(status: insertStatus)
        }
        return key
    }
}
