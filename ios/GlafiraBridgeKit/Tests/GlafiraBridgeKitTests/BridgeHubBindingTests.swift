import XCTest
@testable import GlafiraBridgeKit

final class BridgeHubBindingTests: XCTestCase {
    private let endpoint = "https://192.168.1.20:8443/bridge/v1"
    private let pin = String(repeating: "a", count: 64)
    private let enrolledAt: Int64 = 1_700_000_000_000
    private let oneHour: Int64 = 60 * 60 * 1_000

    private func binding(
        hubId: String? = nil,
        endpoint: String? = nil,
        pin: String? = nil,
        scopes: [String] = ["project.read", "ownership.read"],
        enrolledAt: Int64? = nil,
        expiresAt: Int64? = nil
    ) throws -> BridgeHubBinding {
        try BridgeHubBinding(
            desktopHubId: hubId ?? "desktop-hub-0001",
            endpoint: endpoint ?? self.endpoint,
            certificateSHA256: pin ?? self.pin,
            scopes: scopes,
            enrolledAtEpochMs: enrolledAt ?? self.enrolledAt,
            expiresAtEpochMs: expiresAt ?? self.enrolledAt + oneHour
        )
    }

    func testAcceptsAndCanonicalizesWindowsHubBinding() throws {
        let value = try binding()
        XCTAssertEqual(value.scopes, ["ownership.read", "project.read"])
        XCTAssertEqual(value.endpoint, endpoint)
        XCTAssertEqual(value.certificateSHA256, pin)
        XCTAssertEqual(value.transportOrigin, "https://192.168.1.20:8443")
    }

    func testCanonicalizesSchemeAndHostAndProvidesTransportOrigin() throws {
        let value = try binding(endpoint: "HTTPS://192.168.1.20:8443/bridge/v1")
        XCTAssertEqual(value.endpoint, endpoint)
        XCTAssertEqual(value.transportOrigin, "https://192.168.1.20:8443")
    }

    func testRejectsPercentEncodedAliasesOfCanonicalHubPath() {
        for path in [
            "/b%72idge/v1",
            "/bridge/%76%31",
            "/%62ridge/v1"
        ] {
            XCTAssertThrowsError(
                try binding(endpoint: "https://192.168.1.20:8443\(path)"),
                "Unexpectedly accepted noncanonical encoded path: \(path)"
            )
        }
    }

    func testCanonicalizesEquivalentIPv6SpellingsAndChecksUniqueLocalPrefixBits() throws {
        let compressed = try binding(endpoint: "HTTPS://[FD12:3456:789A::1]:8443/bridge/v1")
        let expanded = try binding(endpoint: "https://[fd12:3456:789a:0000:0000:0000:0000:0001]:8443/bridge/v1")
        XCTAssertEqual(compressed.endpoint, "https://[fd12:3456:789a::1]:8443/bridge/v1")
        XCTAssertEqual(expanded.endpoint, compressed.endpoint)
        XCTAssertEqual(expanded, compressed)
        XCTAssertThrowsError(try binding(endpoint: "https://[fe80::1]:8443/bridge/v1"))
    }

    func testRejectsPublicLoopbackAndHostnameEndpoints() {
        for invalid in [
            "https://8.8.8.8:8443/bridge/v1",
            "https://127.0.0.1:8443/bridge/v1",
            "https://localhost:8443/bridge/v1",
            "https://example.com:8443/bridge/v1",
            "http://192.168.1.20:8443/bridge/v1"
        ] {
            XCTAssertThrowsError(try binding(endpoint: invalid), "Unexpectedly accepted (invalid)")
        }
    }

    func testDefaultsToWindowsHubPortAndRejectsPrivilegedPorts() throws {
        let defaulted = try binding(endpoint: "https://192.168.1.20/bridge/v1")
        XCTAssertEqual(defaulted.endpoint, "https://192.168.1.20:8443/bridge/v1")
        XCTAssertNoThrow(try binding(endpoint: "https://192.168.1.20:1024/bridge/v1"))
        XCTAssertThrowsError(try binding(endpoint: "https://192.168.1.20:443/bridge/v1"))
    }

    func testHubIdentifierMatchesWindowsHostLengthAndCharacterPolicy() throws {
        XCTAssertThrowsError(try binding(hubId: "hub-1"))
        let value = try binding(hubId: "hub.id-0001")
        XCTAssertEqual(value.desktopHubId, "hub.id-0001")
    }

    func testOversizedScopeListIsRejected() {
        XCTAssertThrowsError(try binding(scopes: Array(repeating: "project.read", count: 33)))
    }

    func testRejectsWrongPathAndNoncanonicalPin() {
        XCTAssertThrowsError(try binding(endpoint: "https://192.168.1.20:8443/bridge/v1/requests"))
        XCTAssertThrowsError(try binding(pin: pin.uppercased()))
        let nonASCIIHexWith64UTF8Bytes = String(repeating: "a", count: 61) + "Ａ"
        XCTAssertEqual(nonASCIIHexWith64UTF8Bytes.utf8.count, 64)
        XCTAssertThrowsError(try binding(pin: nonASCIIHexWith64UTF8Bytes))
    }

    func testRejectsScopesOutsideWindowsEnrollmentPolicy() {
        XCTAssertThrowsError(try binding(scopes: ["desktop.task.delete"]))
        XCTAssertThrowsError(try binding(scopes: ["project.read", "project.read"]))
        XCTAssertThrowsError(try binding(scopes: []))
    }

    func testAcceptsWindowsSupportedScopes() throws {
        let supported = [
            "approval.request", "conversation.append", "conversation.read",
            "desktop.task.claim", "desktop.task.result", "ownership.read", "project.read"
        ]
        let value = try binding(scopes: supported)
        XCTAssertEqual(value.scopes, supported.sorted())
    }

    func testRejectsTimestampOutsideWindowsDateTimeOffsetRange() {
        XCTAssertThrowsError(try binding(enrolledAt: 253_402_300_800_000))
        XCTAssertThrowsError(try binding(expiresAt: 253_402_300_800_000))
    }

    func testRequiresWindowsCredentialLifetimeBetweenOneHourAnd365Days() throws {
        XCTAssertNoThrow(try binding(expiresAt: enrolledAt + oneHour))
        XCTAssertNoThrow(try binding(expiresAt: enrolledAt + 365 * 24 * 60 * 60 * 1_000))
        XCTAssertThrowsError(try binding(expiresAt: enrolledAt + oneHour - 1))
        XCTAssertThrowsError(try binding(expiresAt: enrolledAt + 365 * 24 * 60 * 60 * 1_000 + 1))
    }

    func testBoundedPersistenceAPIIsTheOnlySupportedBindingCodec() throws {
        let bindingType: Any.Type = BridgeHubBinding.self
        XCTAssertFalse(bindingType is Encodable.Type)
        XCTAssertFalse(bindingType is Decodable.Type)
        let persisted = try BridgeHubBindingPersistence.encode(binding())
        XCTAssertEqual(try BridgeHubBindingPersistence.decode(persisted), try binding())
    }

    func testPersistenceRejectsInvalidBindingFields() throws {
        let encoded = try BridgeHubBindingPersistence.encode(binding())
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        var nested = try XCTUnwrap(envelope["binding"] as? [String: Any])
        nested["endpoint"] = "https://8.8.8.8:8443/bridge/v1"
        envelope["binding"] = nested
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope))) { error in
            XCTAssertEqual(error as? BridgeHubBindingError, .invalidBinding)
        }
    }

    func testPersistenceRoundTripsCanonicalBindingWithStableKeyOrder() throws {
        let original = try binding(endpoint: "HTTPS://192.168.1.20:8443/bridge/v1")
        let encoded = try BridgeHubBindingPersistence.encode(original)
        XCTAssertLessThanOrEqual(encoded.count, BridgeHubBindingPersistence.maximumPayloadBytes)
        let decoded = try BridgeHubBindingPersistence.decode(encoded)
        XCTAssertEqual(decoded, original)
        XCTAssertEqual(decoded.endpoint, endpoint)
        let expected = #"{"binding":{"certificateSHA256":"\#(pin)","desktopHubId":"desktop-hub-0001","endpoint":"\#(endpoint)","enrolledAtEpochMs":\#(enrolledAt),"expiresAtEpochMs":\#(enrolledAt + oneHour),"scopes":["ownership.read","project.read"]},"schemaVersion":1}"#
        XCTAssertEqual(String(data: encoded, encoding: .utf8), expected)
    }

    func testPersistenceRejectsUnknownEnvelopeAndBindingFields() throws {
        let encoded = try BridgeHubBindingPersistence.encode(binding())
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        envelope["unexpected"] = true
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }

        var nested = try XCTUnwrap(envelope["binding"] as? [String: Any])
        nested["unexpectedBindingField"] = "must-not-be-ignored"
        envelope.removeValue(forKey: "unexpected")
        envelope["binding"] = nested
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }
    }

    func testPersistenceRejectsDuplicateDecodedJSONMemberNames() throws {
        let validJSON = String(data: try BridgeHubBindingPersistence.encode(binding()), encoding: .utf8)!
        let duplicateEnvelope = validJSON.replacingOccurrences(
            of: #""schemaVersion":1"#,
            with: #""schemaVersion":1,"schemaVersion":1"#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(duplicateEnvelope.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .duplicateJSONKey)
        }

    }

    func testPersistenceMapsMalformedJSONAndUnsupportedVersionToSpecificErrors() throws {
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data("{".utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }
        XCTAssertNoThrow(try BridgeHubBindingPersistence.validatePayloadSize(Data()))
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data())) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(
            with: BridgeHubBindingPersistence.encode(binding())
        ) as? [String: Any])
        envelope["schemaVersion"] = 99
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }
    }

    func testPersistenceRejectsInt64MaxTimestampAsInvalidBindingWithoutDoubleRounding() throws {
        let validJSON = String(data: try BridgeHubBindingPersistence.encode(binding()), encoding: .utf8)!
        let maximumInt64 = validJSON.replacingOccurrences(
            of: #""enrolledAtEpochMs":\#(enrolledAt)"#,
            with: #""enrolledAtEpochMs":9223372036854775807"#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(maximumInt64.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingError, .invalidBinding)
        }
    }

    func testPersistencePreservesInt64MaximumTimestampValidationError() throws {
        let validJSON = String(data: try BridgeHubBindingPersistence.encode(binding()), encoding: .utf8)!
        let payload = validJSON.replacingOccurrences(
            of: "\"enrolledAtEpochMs\":\(enrolledAt)",
            with: "\"enrolledAtEpochMs\":\(Int64.max)"
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(payload.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingError, .invalidBinding)
        }
    }

    func testPersistenceRejectsNonIntegerNumericTokens() throws {
        let validJSON = String(data: try BridgeHubBindingPersistence.encode(binding()), encoding: .utf8)!
        let expiresAt = enrolledAt + oneHour
        let invalidVariants = [
            validJSON.replacingOccurrences(of: "\"schemaVersion\":1", with: "\"schemaVersion\":1.0"),
            validJSON.replacingOccurrences(of: "\"schemaVersion\":1", with: "\"schemaVersion\":1e0"),
            validJSON.replacingOccurrences(of: "\"enrolledAtEpochMs\":\(enrolledAt)", with: "\"enrolledAtEpochMs\":\(enrolledAt).0"),
            validJSON.replacingOccurrences(of: "\"expiresAtEpochMs\":\(expiresAt)", with: "\"expiresAtEpochMs\":\(expiresAt)e0"),
            validJSON.replacingOccurrences(of: "\"schemaVersion\":1", with: "\"schemaVersion\":1E+0"),
            validJSON.replacingOccurrences(of: "\"enrolledAtEpochMs\":\(enrolledAt)", with: "\"enrolledAtEpochMs\":\(enrolledAt).000")
        ]
        for payload in invalidVariants {
            XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(payload.utf8))) { error in
                XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
            }
        }
    }

    func testPersistenceAcceptsWhitespaceBeforeIntegerPrimitives() throws {
        let validJSON = String(data: try BridgeHubBindingPersistence.encode(binding()), encoding: .utf8)!
        let variants = [
            validJSON.replacingOccurrences(of: #""schemaVersion":1"#, with: #""schemaVersion":   1"#),
            validJSON.replacingOccurrences(of: "\"enrolledAtEpochMs\":\(enrolledAt)", with: "\"enrolledAtEpochMs\":   \(enrolledAt)"),
            validJSON.replacingOccurrences(of: "\"expiresAtEpochMs\":\(enrolledAt + oneHour)", with: "\"expiresAtEpochMs\":   \(enrolledAt + oneHour)")
        ]
        for variant in variants {
            XCTAssertEqual(try BridgeHubBindingPersistence.decode(Data(variant.utf8)), try binding())
        }
    }

    func testPersistenceEnforcesExactPayloadAndNestingBoundaries() throws {
        XCTAssertEqual(BridgeHubBindingPersistence.maximumPayloadBytes, 4_096)
        XCTAssertEqual(BridgeHubBindingPersistence.maximumJSONNestingDepth, 16)
        let validJSON = String(data: try BridgeHubBindingPersistence.encode(binding()), encoding: .utf8)!
        let paddingCount = 4_096 - validJSON.utf8.count
        let exactSize = Data((validJSON + String(repeating: " ", count: paddingCount)).utf8)
        XCTAssertEqual(exactSize.count, 4_096)
        XCTAssertNoThrow(try BridgeHubBindingPersistence.decode(exactSize))
        let oneByteOver = Data(exactSize + Data([0x20]))
        XCTAssertEqual(oneByteOver.count, 4_097)
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(oneByteOver)) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .payloadTooLarge)
        }
        XCTAssertThrowsError(try BridgeHubBindingPersistence.validatePayloadSize(oneByteOver)) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .payloadTooLarge)
        }

        let atLimit = String(repeating: "[", count: 16) + "0" + String(repeating: "]", count: 16)
        let overLimit = String(repeating: "[", count: 17) + "0" + String(repeating: "]", count: 17)
        XCTAssertNoThrow(try BridgeHubBindingPersistence.validateJSONStructure(Data(atLimit.utf8)))
        XCTAssertThrowsError(try BridgeHubBindingPersistence.validateJSONStructure(Data(overLimit.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .payloadTooDeep)
        }

        let fifteenNestedArrays = String(repeating: "[", count: 15) + "0" + String(repeating: "]", count: 15)
        let sixteenNestedArrays = String(repeating: "[", count: 16) + "0" + String(repeating: "]", count: 16)
        let decoderAtLimit = validJSON.replacingOccurrences(
            of: #""schemaVersion":1"#,
            with: #""schemaVersion":\#(fifteenNestedArrays)"#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(decoderAtLimit.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }
        let decoderOverLimit = validJSON.replacingOccurrences(
            of: #""schemaVersion":1"#,
            with: #""schemaVersion":\#(sixteenNestedArrays)"#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(decoderOverLimit.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .payloadTooDeep)
        }
    }

    func testPersistenceDetectsEscapedDuplicateKeysAndPrefersSyntaxErrors() throws {
        let validJSON = String(data: try BridgeHubBindingPersistence.encode(binding()), encoding: .utf8)!
        let escapedDuplicate = validJSON.replacingOccurrences(
            of: #""desktopHubId":"desktop-hub-0001""#,
            with: #""desktopHubId":"desktop-hub-0001","\u0064esktopHubId":"desktop-hub-0001""#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(escapedDuplicate.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .duplicateJSONKey)
        }

        let malformedDuplicate = validJSON.replacingOccurrences(
            of: #""desktopHubId":"desktop-hub-0001""#,
            with: #""desktopHubId":"desktop-hub-0001","desktopHubId""#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(malformedDuplicate.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }

        let invalidPrimitiveDuplicate = validJSON.replacingOccurrences(
            of: #""desktopHubId":"desktop-hub-0001""#,
            with: #""desktopHubId":"desktop-hub-0001","desktopHubId":tru"#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(invalidPrimitiveDuplicate.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }

        for numeric in ["1.5", "1e2"] {
            let numericDuplicate = validJSON.replacingOccurrences(
                of: #""desktopHubId":"desktop-hub-0001""#,
                with: #""desktopHubId":"desktop-hub-0001","desktopHubId":\#(numeric)"#
            )
            XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(numericDuplicate.utf8))) { error in
                XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .duplicateJSONKey)
            }
        }

        let leadingZeroDuplicate = validJSON.replacingOccurrences(
            of: #""desktopHubId":"desktop-hub-0001""#,
            with: #""desktopHubId":"desktop-hub-0001","desktopHubId":01"#
        )
        XCTAssertThrowsError(try BridgeHubBindingPersistence.decode(Data(leadingZeroDuplicate.utf8))) { error in
            XCTAssertEqual(error as? BridgeHubBindingPersistenceError, .invalidPayload)
        }
    }

    func testJSONScannerComparesDecodedUTF8MemberNamesWithoutUnicodeNormalization() throws {
        let distinctNames = #"{"é":1,"é":1}"#
        XCTAssertNoThrow(try BridgeHubBindingPersistence.validateJSONStructure(Data(distinctNames.utf8)))
    }

    func testPersistenceDecodeCanonicalizesEndpointBeforeReturning() throws {
        let encoded = try BridgeHubBindingPersistence.encode(binding())
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        var nested = try XCTUnwrap(envelope["binding"] as? [String: Any])
        nested["endpoint"] = "HTTPS://192.168.1.20:8443/bridge/v1"
        envelope["binding"] = nested
        let decoded = try BridgeHubBindingPersistence.decode(JSONSerialization.data(withJSONObject: envelope))
        XCTAssertEqual(decoded.endpoint, endpoint)
        XCTAssertEqual(decoded.transportOrigin, "https://192.168.1.20:8443")
    }

    func testPersistenceRejectsPercentEncodedEndpointAliasesBeforeCanonicalStorage() throws {
        let encoded = try BridgeHubBindingPersistence.encode(binding())
        for path in ["/b%72idge/v1", "/bridge/%76%31"] {
            var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
            var nested = try XCTUnwrap(envelope["binding"] as? [String: Any])
            nested["endpoint"] = "https://192.168.1.20:8443\(path)"
            envelope["binding"] = nested
            let payload = try JSONSerialization.data(withJSONObject: envelope)
            XCTAssertThrowsError(
                try BridgeHubBindingPersistence.decode(payload),
                "Unexpectedly decoded noncanonical endpoint path: \(path)"
            )
        }
    }

}
