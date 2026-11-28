import Foundation

/// Deprecated source-compatibility bridge for callers that still require Codable.
///
/// Do not use JSONDecoder for persisted Hub bindings: it cannot enforce the bounded
/// payload, nesting, and duplicate-key checks. Persisted values must use
/// BridgeHubBindingPersistence.decode(_:) instead.
@available(*, deprecated, message: "Use BridgeHubBindingPersistence for persisted Hub bindings.")
extension BridgeHubBinding: Codable {
    private enum CodingKeys: String, CodingKey {
        case desktopHubId
        case endpoint
        case certificateSHA256
        case scopes
        case enrolledAtEpochMs
        case expiresAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let desktopHubId = try container.decode(String.self, forKey: .desktopHubId)
        let endpoint = try container.decode(String.self, forKey: .endpoint)
        let certificateSHA256 = try container.decode(String.self, forKey: .certificateSHA256)
        let scopes = try container.decode([String].self, forKey: .scopes)
        let enrolledAtEpochMs = try container.decode(Int64.self, forKey: .enrolledAtEpochMs)
        let expiresAtEpochMs = try container.decode(Int64.self, forKey: .expiresAtEpochMs)

        do {
            try self.init(
                desktopHubId: desktopHubId,
                endpoint: endpoint,
                certificateSHA256: certificateSHA256,
                scopes: scopes,
                enrolledAtEpochMs: enrolledAtEpochMs,
                expiresAtEpochMs: expiresAtEpochMs
            )
        } catch {
            throw DecodingError.dataCorrupted(
                .init(codingPath: decoder.codingPath, debugDescription: "Decoded Hub binding failed validation.")
            )
        }
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
}
