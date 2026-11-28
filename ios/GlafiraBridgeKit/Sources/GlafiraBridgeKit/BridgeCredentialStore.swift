import Foundation
import Security
import LocalAuthentication
import Darwin

/// Non-secret lifecycle identity of the authoritative Windows Hub enrollment.
public struct BridgeHubBinding: Codable, Equatable, Sendable {
    public let desktopHubId: String
    /// Canonical HTTPS endpoint including the Windows Hub API base path.
    public let endpoint: String
    public let certificateSHA256: String
    public let scopes: [String]
    public let enrolledAtEpochMs: Int64
    public let expiresAtEpochMs: Int64

    /// Origin accepted by PinnedBridgeHTTPSClient; endpoint retains /bridge/v1.
    public var transportOrigin: String {
        var components = URLComponents(string: endpoint)!
        components.path = ""
        return components.string!
    }

    private enum CodingKeys: String, CodingKey {
        case desktopHubId
        case endpoint
        case certificateSHA256
        case scopes
        case enrolledAtEpochMs
        case expiresAtEpochMs
    }

    public init(
        desktopHubId: String,
        endpoint: String,
        certificateSHA256: String,
        scopes: [String],
        enrolledAtEpochMs: Int64,
        expiresAtEpochMs: Int64
    ) throws {
        guard BridgeHubBindingValidation.validIdentifier(desktopHubId),
              let canonicalEndpoint = BridgeHubBindingValidation.canonicalEndpoint(endpoint),
              BridgeHubBindingValidation.validPin(certificateSHA256),
              !scopes.isEmpty,
              scopes.count <= 32,
              scopes.count == Set(scopes).count,
              scopes.allSatisfy(BridgeHubBindingValidation.validScope),
              (1...BridgeHubBindingValidation.maximumUnixEpochMilliseconds).contains(enrolledAtEpochMs),
              (1...BridgeHubBindingValidation.maximumUnixEpochMilliseconds).contains(expiresAtEpochMs),
              expiresAtEpochMs > enrolledAtEpochMs else {
            throw BridgeHubBindingError.invalidBinding
        }
        let lifetime = expiresAtEpochMs - enrolledAtEpochMs
        guard (BridgeHubBindingValidation.minimumLifetimeMilliseconds...BridgeHubBindingValidation.maximumLifetimeMilliseconds).contains(lifetime) else {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
        self.desktopHubId = desktopHubId
        self.endpoint = canonicalEndpoint
        self.certificateSHA256 = certificateSHA256
        self.scopes = scopes.sorted()
        self.enrolledAtEpochMs = enrolledAtEpochMs
        self.expiresAtEpochMs = expiresAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            desktopHubId: container.decode(String.self, forKey: .desktopHubId),
            endpoint: container.decode(String.self, forKey: .endpoint),
            certificateSHA256: container.decode(String.self, forKey: .certificateSHA256),
            scopes: container.decode([String].self, forKey: .scopes),
            enrolledAtEpochMs: container.decode(Int64.self, forKey: .enrolledAtEpochMs),
            expiresAtEpochMs: container.decode(Int64.self, forKey: .expiresAtEpochMs)
        )
    }

    public func encode(to encoder: Encoder) throws {
        try validate()
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(desktopHubId, forKey: .desktopHubId)
        try container.encode(endpoint, forKey: .endpoint)
        try container.encode(certificateSHA256, forKey: .certificateSHA256)
        try container.encode(scopes, forKey: .scopes)
        try container.encode(enrolledAtEpochMs, forKey: .enrolledAtEpochMs)
        try container.encode(expiresAtEpochMs, forKey: .expiresAtEpochMs)
    }

    public func validate() throws {
        let canonical = try BridgeHubBinding(
            desktopHubId: desktopHubId,
            endpoint: endpoint,
            certificateSHA256: certificateSHA256,
            scopes: scopes,
            enrolledAtEpochMs: enrolledAtEpochMs,
            expiresAtEpochMs: expiresAtEpochMs
        )
        guard canonical == self else { throw BridgeCredentialStoreError.invalidHubBinding }
    }
}

private enum BridgeHubBindingValidation {
    static let maximumUnixEpochMilliseconds: Int64 = 253_402_300_799_999
    static let minimumLifetimeMilliseconds: Int64 = 60 * 60 * 1_000
    static let maximumLifetimeMilliseconds: Int64 = 365 * 24 * 60 * 60 * 1_000

    private static let supportedScopes: Set<String> = [
        "approval.request",
        "conversation.append",
        "conversation.read",
        "desktop.task.claim",
        "desktop.task.result",
        "ownership.read",
        "project.read"
    ]

    static func validIdentifier(_ value: String) -> Bool {
        (8...128).contains(value.utf8.count) && value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == ".")
        }
    }

    static func canonicalEndpoint(_ value: String) -> String? {
        guard var components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              let rawHost = components.host else {
            return nil
        }
        let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        let canonicalHost: String
        if host.contains(":") {
            guard let ipv6 = canonicalUniqueLocalIPv6Address(host) else { return nil }
            canonicalHost = "[\(ipv6)]"
        } else {
            guard isPrivateIPv4Address(host) else { return nil }
            canonicalHost = host.lowercased()
        }
        let effectivePort = components.port ?? 8443
        guard components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              components.path == "/bridge/v1",
              (1024...65_535).contains(effectivePort) else {
            return nil
        }
        components.scheme = "https"
        components.host = canonicalHost
        components.port = effectivePort
        guard let normalized = components.string,
              let normalizedURL = URL(string: normalized),
              normalizedURL.path == "/bridge/v1",
              normalizedURL.scheme == "https",
              normalizedURL.port == effectivePort else {
            return nil
        }
        return normalizedURL.absoluteString
    }

    private static func isPrivateIPv4Address(_ host: String) -> Bool {
        var ipv4 = in_addr()
        guard host.withCString({ inet_pton(AF_INET, $0, &ipv4) }) == 1 else { return false }
        let octets = host.split(separator: ".").compactMap { UInt8($0) }
        guard octets.count == 4 else { return false }
        return octets[0] == 10 ||
            (octets[0] == 172 && (16...31).contains(octets[1])) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private static func canonicalUniqueLocalIPv6Address(_ host: String) -> String? {
        var ipv6 = in6_addr()
        guard host.withCString({ inet_pton(AF_INET6, $0, &ipv6) }) == 1 else { return nil }
        let bytes = withUnsafeBytes(of: ipv6) { Array($0.prefix(16)) }
        guard bytes.count == 16, (bytes[0] & 0xFE) == 0xFC else { return nil }
        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        let rendered = withUnsafePointer(to: &ipv6) { address in
            buffer.withUnsafeMutableBufferPointer { output in
                inet_ntop(AF_INET6, address, output.baseAddress, socklen_t(output.count))
            }
        }
        guard rendered != nil else { return nil }
        return String(cString: buffer)
    }

    static func validPin(_ value: String) -> Bool {
        value.utf8.count == 64 && value.allSatisfy {
            $0.isASCII && $0.isHexDigit && !$0.isUppercase
        }
    }

    static func validScope(_ value: String) -> Bool {
        supportedScopes.contains(value)
    }
}

public enum BridgeHubBindingError: Error, Equatable, Sendable {
    case invalidBinding
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
