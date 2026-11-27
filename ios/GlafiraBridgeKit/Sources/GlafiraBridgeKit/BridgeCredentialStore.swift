import Foundation
import Security
import LocalAuthentication
import Darwin
import CoreFoundation

/// Non-secret lifecycle identity of the authoritative Windows Hub enrollment.
/// Deliberately not Codable: callers must use the strict, bounded persistence API.
public struct BridgeHubBinding: Equatable, Sendable {
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
              scopes.count == Set(scopes).count,
              scopes.count <= 32,
              scopes.allSatisfy(BridgeHubBindingValidation.validScope),
              (1...BridgeHubBindingValidation.maximumUnixEpochMilliseconds).contains(enrolledAtEpochMs),
              (1...BridgeHubBindingValidation.maximumUnixEpochMilliseconds).contains(expiresAtEpochMs),
              expiresAtEpochMs > enrolledAtEpochMs else {
            throw BridgeCredentialStoreError.invalidHubBinding
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
        (1...128).contains(value.utf8.count) && value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_")
        }
    }

    static func canonicalEndpoint(_ value: String) -> String? {
        guard var components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              let rawHost = components.host else {
            return nil
        }
        let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard isPrivateLanAddress(host),
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              components.path == "/bridge/v1",
              components.port == nil || (1...65_535).contains(components.port!) else {
            return nil
        }
        components.scheme = "https"
        components.host = host.contains(":") ? "[\(host.lowercased())]" : host.lowercased()
        guard let normalized = components.string,
              let normalizedURL = URL(string: normalized),
              normalizedURL.path == "/bridge/v1",
              normalizedURL.scheme == "https" else {
            return nil
        }
        return normalizedURL.absoluteString
    }

    private static func isPrivateLanAddress(_ host: String) -> Bool {
        var ipv4 = in_addr()
        if host.withCString({ inet_pton(AF_INET, $0, &ipv4) }) == 1 {
            let octets = host.split(separator: ".").compactMap { UInt8($0) }
            guard octets.count == 4 else { return false }
            return octets[0] == 10 ||
                (octets[0] == 172 && (16...31).contains(octets[1])) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        var ipv6 = in6_addr()
        if host.withCString({ inet_pton(AF_INET6, $0, &ipv6) }) == 1 {
            let normalized = host.lowercased()
            return normalized.hasPrefix("fc") || normalized.hasPrefix("fd")
        }
        return false
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

/// Strict, bounded persistence envelope. This is the only public decoding route for bindings.
public enum BridgeHubBindingPersistence {
    static let maximumPayloadBytes = 4_096
    static let maximumJSONNestingDepth = 16

    public static func encode(_ binding: BridgeHubBinding) throws -> Data {
        do {
            try binding.validate()
            let bindingObject: [String: Any] = [
                "desktopHubId": binding.desktopHubId,
                "endpoint": binding.endpoint,
                "certificateSHA256": binding.certificateSHA256,
                "scopes": binding.scopes,
                "enrolledAtEpochMs": binding.enrolledAtEpochMs,
                "expiresAtEpochMs": binding.expiresAtEpochMs
            ]
            let envelope: [String: Any] = ["schemaVersion": 1, "binding": bindingObject]
            let data = try JSONSerialization.data(withJSONObject: envelope, options: [.sortedKeys])
            try validatePayloadSize(data)
            return data
        } catch let error as BridgeCredentialStoreError {
            throw error
        } catch {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
    }

    public static func decode(_ data: Data) throws -> BridgeHubBinding {
        do {
            try validatePayloadSize(data)
            try validateJSONStructure(data)
            guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  Set(envelope.keys) == Set(["schemaVersion", "binding"]),
                  integerValue(envelope["schemaVersion"]) == 1,
                  let object = envelope["binding"] as? [String: Any],
                  Set(object.keys) == Set([
                    "desktopHubId", "endpoint", "certificateSHA256", "scopes",
                    "enrolledAtEpochMs", "expiresAtEpochMs"
                  ]),
                  let desktopHubId = object["desktopHubId"] as? String,
                  let endpoint = object["endpoint"] as? String,
                  let certificateSHA256 = object["certificateSHA256"] as? String,
                  let scopes = object["scopes"] as? [String],
                  let enrolledAtEpochMs = integerValue(object["enrolledAtEpochMs"]),
                  let expiresAtEpochMs = integerValue(object["expiresAtEpochMs"]) else {
                throw BridgeCredentialStoreError.invalidHubBinding
            }
            return try BridgeHubBinding(
                desktopHubId: desktopHubId,
                endpoint: endpoint,
                certificateSHA256: certificateSHA256,
                scopes: scopes,
                enrolledAtEpochMs: enrolledAtEpochMs,
                expiresAtEpochMs: expiresAtEpochMs
            )
        } catch let error as BridgeCredentialStoreError {
            throw error
        } catch {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
    }

    static func validatePayloadSize(_ data: Data) throws {
        guard (1...maximumPayloadBytes).contains(data.count) else {
            throw BridgeCredentialStoreError.payloadTooLarge
        }
    }

    static func validateJSONStructure(_ data: Data) throws {
        var validator = StrictJSONStructureValidator(
            bytes: Array(data),
            maximumDepth: maximumJSONNestingDepth
        )
        try validator.validate()
    }

    private static func integerValue(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else {
            return nil
        }
        let value = number.doubleValue
        guard value.isFinite,
              value.rounded(.towardZero) == value,
              value >= Double(Int64.min),
              value <= Double(Int64.max) else {
            return nil
        }
        return number.int64Value
    }
}

/// Small pre-parse JSON scanner: detects duplicate decoded member names and bounds nesting
/// before JSONSerialization can collapse duplicate keys or allocate a deep object graph.
private struct StrictJSONStructureValidator {
    private let bytes: [UInt8]
    private let maximumDepth: Int
    private var index = 0

    init(bytes: [UInt8], maximumDepth: Int) {
        self.bytes = bytes
        self.maximumDepth = maximumDepth
    }

    mutating func validate() throws {
        skipWhitespace()
        try parseValue(depth: 0)
        skipWhitespace()
        guard index == bytes.count else { throw BridgeCredentialStoreError.invalidHubBinding }
    }

    private mutating func parseValue(depth: Int) throws {
        skipWhitespace()
        guard index < bytes.count else { throw BridgeCredentialStoreError.invalidHubBinding }
        switch bytes[index] {
        case 0x7B: try parseObject(depth: depth + 1) // {
        case 0x5B: try parseArray(depth: depth + 1) // [
        case 0x22: _ = try parseString()
        default: try parsePrimitive()
        }
    }

    private mutating func parseObject(depth: Int) throws {
        guard depth <= maximumDepth else { throw BridgeCredentialStoreError.payloadTooDeep }
        index += 1
        skipWhitespace()
        if consume(0x7D) { return }
        var keys = Set<String>()
        while true {
            skipWhitespace()
            let key = try parseString()
            guard keys.insert(key).inserted else { throw BridgeCredentialStoreError.duplicateJSONKey }
            skipWhitespace()
            guard consume(0x3A) else { throw BridgeCredentialStoreError.invalidHubBinding } // :
            if key == "schemaVersion" || key == "enrolledAtEpochMs" || key == "expiresAtEpochMs" {
                try parseIntegerPrimitive()
            } else {
                try parseValue(depth: depth)
            }
            skipWhitespace()
            if consume(0x2C) { continue } // ,
            if consume(0x7D) { return } // }
            throw BridgeCredentialStoreError.invalidHubBinding
        }
    }

    private mutating func parseArray(depth: Int) throws {
        guard depth <= maximumDepth else { throw BridgeCredentialStoreError.payloadTooDeep }
        index += 1
        skipWhitespace()
        if consume(0x5D) { return }
        while true {
            try parseValue(depth: depth)
            skipWhitespace()
            if consume(0x2C) { continue } // ,
            if consume(0x5D) { return } // ]
            throw BridgeCredentialStoreError.invalidHubBinding
        }
    }

    private mutating func parseString() throws -> String {
        guard consume(0x22) else { throw BridgeCredentialStoreError.invalidHubBinding }
        let start = index - 1
        while index < bytes.count {
            let byte = bytes[index]
            if byte == 0x22 {
                index += 1
                let encoded = Data(bytes[start..<index])
                guard let value = try? JSONDecoder().decode(String.self, from: encoded) else {
                    throw BridgeCredentialStoreError.invalidHubBinding
                }
                return value
            }
            if byte < 0x20 { throw BridgeCredentialStoreError.invalidHubBinding }
            if byte == 0x5C {
                index += 1
                guard index < bytes.count else { throw BridgeCredentialStoreError.invalidHubBinding }
                if bytes[index] == 0x75 {
                    guard index + 4 < bytes.count else { throw BridgeCredentialStoreError.invalidHubBinding }
                    index += 5
                } else {
                    index += 1
                }
            } else {
                index += 1
            }
        }
        throw BridgeCredentialStoreError.invalidHubBinding
    }

    private mutating func parsePrimitive() throws {
        let start = index
        while index < bytes.count,
              !isWhitespace(bytes[index]),
              bytes[index] != 0x2C, bytes[index] != 0x5D, bytes[index] != 0x7D {
            index += 1
        }
        guard index > start else { throw BridgeCredentialStoreError.invalidHubBinding }
    }

    private mutating func parseIntegerPrimitive() throws {
        let start = index
        if consume(0x2D) { /* optional minus; semantic validation rejects negative schema/timestamps */ }
        guard index < bytes.count else { throw BridgeCredentialStoreError.invalidHubBinding }
        if bytes[index] == 0x30 {
            index += 1
        } else {
            guard (0x31...0x39).contains(bytes[index]) else {
                throw BridgeCredentialStoreError.invalidHubBinding
            }
            while index < bytes.count && (0x30...0x39).contains(bytes[index]) {
                index += 1
            }
        }
        let token = String(decoding: bytes[start..<index], as: UTF8.self)
        guard Int64(token) != nil else { throw BridgeCredentialStoreError.invalidHubBinding }
        guard index == bytes.count ||
                isWhitespace(bytes[index]) ||
                bytes[index] == 0x2C || bytes[index] == 0x5D || bytes[index] == 0x7D else {
            throw BridgeCredentialStoreError.invalidHubBinding
        }
    }

    private mutating func skipWhitespace() {
        while index < bytes.count && isWhitespace(bytes[index]) { index += 1 }
    }

    private func isWhitespace(_ byte: UInt8) -> Bool {
        byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D
    }

    private mutating func consume(_ byte: UInt8) -> Bool {
        guard index < bytes.count, bytes[index] == byte else { return false }
        index += 1
        return true
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
    case payloadTooLarge
    case payloadTooDeep
    case duplicateJSONKey
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
