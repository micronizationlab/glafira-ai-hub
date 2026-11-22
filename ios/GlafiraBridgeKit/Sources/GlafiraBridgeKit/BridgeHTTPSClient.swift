import Foundation
import CryptoKit
import Security

public enum BridgeTransportError: Error, Equatable, Sendable {
    case invalidOrigin
    case invalidCertificatePin
    case certificatePinMismatch
    case untrustedServerCertificate
    case invalidHTTPResponse
    case responseTooLarge
    case unexpectedStatus(Int)
    case responseRequestMismatch
    case unsupportedResponseVersion
}

/// Pinned HTTPS transport for the historical Android-hosted Bridge contract; it is not the current Windows Hub peer API. It requires an owner-supplied
/// SHA-256 leaf-certificate pin and independently validates the TLS server
/// certificate for the requested hostname. Redirects are never followed.
public final class PinnedBridgeHTTPSClient: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let origin: URL
    private let host: String
    private let expectedPin: Data
    private let maximumResponseBytes: Int
    lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    public init(origin: String, certificateSHA256: String, maximumResponseBytes: Int = 1_048_576) throws {
        guard maximumResponseBytes > 0 && maximumResponseBytes <= 1_048_576,
              let url = URL(string: origin),
              url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.path.isEmpty || url.path == "/",
              (url.port == nil || (1...65_535).contains(url.port!)) else {
            throw BridgeTransportError.invalidOrigin
        }
        guard certificateSHA256.utf8.count == 64,
              certificateSHA256.allSatisfy({ $0.isHexDigit }),
              let pin = Self.decodeHex(certificateSHA256), pin.count == 32 else {
            throw BridgeTransportError.invalidCertificatePin
        }
        self.origin = url
        self.host = host
        self.expectedPin = pin
        self.maximumResponseBytes = maximumResponseBytes
        super.init()
    }

    public func submitPairing(_ submission: BridgePairingSubmission) async throws -> BridgePairingPendingResponse {
        var request = URLRequest(url: origin.appendingPathComponent("bridge").appendingPathComponent("v1").appendingPathComponent("pairing"))
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("close", forHTTPHeaderField: "Connection")
        request.httpBody = try JSONEncoder().encode(submission)
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 202 else {
            throw BridgeTransportError.invalidHTTPResponse
        }
        var data = Data()
        for try await byte in bytes {
            guard data.count < maximumResponseBytes else { throw BridgeTransportError.responseTooLarge }
            data.append(byte)
        }
        let pending = try JSONDecoder().decode(BridgePairingPendingResponse.self, from: data)
        guard pending.status == "PENDING_OWNER_APPROVAL" else { throw BridgeTransportError.invalidHTTPResponse }
        return pending
    }

    public func send(_ unsignedRequest: BridgeRequest, credentialStore: KeychainBridgeCredentialStore) async throws -> BridgeResponse {
        let credential = try credentialStore.load(
            deviceId: unsignedRequest.deviceId,
            reason: "Подтвердите использование ключа GLAFIRA Bridge"
        )
        let signed = try BridgeProtocol.sign(unsignedRequest, secret: credential.hmacSecret)
        var request = URLRequest(url: origin.appendingPathComponent("bridge").appendingPathComponent("v1").appendingPathComponent("requests"))
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("close", forHTTPHeaderField: "Connection")
        request.httpBody = try JSONEncoder.bridgeEncoder.encode(signed)

        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw BridgeTransportError.invalidHTTPResponse }
        guard http.statusCode == 200 || http.statusCode == 202 else {
            throw BridgeTransportError.unexpectedStatus(http.statusCode)
        }
        var data = Data()
        for try await byte in bytes {
            guard data.count < maximumResponseBytes else { throw BridgeTransportError.responseTooLarge }
            data.append(byte)
        }
        let decoded = try JSONDecoder.bridgeDecoder.decode(BridgeResponse.self, from: data)
        guard decoded.requestId == unsignedRequest.requestId else { throw BridgeTransportError.responseRequestMismatch }
        guard decoded.protocolVersion == BridgeProtocol.currentVersion else { throw BridgeTransportError.unsupportedResponseVersion }
        return decoded
    }

    public func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                           completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              challenge.protectionSpace.host.caseInsensitiveCompare(host) == .orderedSame,
              let trust = challenge.protectionSpace.serverTrust,
              let certificate = SecTrustGetCertificateAtIndex(trust, 0),
              let policy = SecPolicyCreateSSL(true, host as CFString) as SecPolicy?,
              SecTrustSetPolicies(trust, policy) == errSecSuccess,
              SecTrustSetAnchorCertificates(trust, [certificate] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess,
              SecTrustEvaluateWithError(trust, nil) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let actualPin = Data(SHA256.hash(data: SecCertificateCopyData(certificate) as Data))
        guard actualPin == expectedPin else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask,
                           willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest,
                           completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    private static func decodeHex(_ value: String) -> Data? {
        var output = Data()
        var index = value.startIndex
        while index < value.endIndex {
            let next = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<next], radix: 16) else { return nil }
            output.append(byte)
            index = next
        }
        return output
    }
}

private extension JSONEncoder {
    static var bridgeEncoder: JSONEncoder {
        let encoder = JSONEncoder()
        // Android's JSON contract uses lower camelCase.
        return encoder
    }
}

private extension JSONDecoder {
    static var bridgeDecoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .useDefaultKeys
        return decoder
    }
}
