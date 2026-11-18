import Foundation
import LocalAuthentication

public enum OwnerAuthenticationError: Error, Equatable, Sendable {
    case unavailable
    case cancelled
    case failed
    case systemError(code: Int)
}

/// Explicit owner-presence check for sensitive local actions such as pairing,
/// revoking a device, exporting private data, or approving privileged work.
public final class OwnerBiometricAuthenticator {
    public init() {}

    public func isAvailable(context: LAContext = LAContext()) -> Bool {
        var error: NSError?
        return context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    public func authenticate(
        reason: String,
        context: LAContext = LAContext()
    ) async throws {
        guard !reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw OwnerAuthenticationError.failed
        }

        var availabilityError: NSError?
        guard context.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            error: &availabilityError
        ) else {
            throw map(availabilityError)
        }

        do {
            let accepted = try await context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: reason
            )
            guard accepted else { throw OwnerAuthenticationError.failed }
        } catch let error as LAError {
            throw map(error)
        } catch {
            throw OwnerAuthenticationError.failed
        }
    }

    private func map(_ error: NSError?) -> OwnerAuthenticationError {
        guard let error else { return .unavailable }
        return map(LAError(_nsError: error))
    }

    private func map(_ error: LAError) -> OwnerAuthenticationError {
        switch error.code {
        case .biometryNotAvailable, .biometryNotEnrolled, .biometryLockout, .passcodeNotSet:
            return .unavailable
        case .userCancel, .appCancel, .systemCancel:
            return .cancelled
        default:
            return .systemError(code: error.errorCode)
        }
    }
}
