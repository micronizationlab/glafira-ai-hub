import SwiftUI
import GlafiraBridgeKit

/// Reusable confirmation surface. The protected action is invoked only after
/// Face ID/Touch ID succeeds; cancellation and unavailable biometrics fail closed.
public struct OwnerBiometricGateView<ProtectedContent: View>: View {
    private let reason: String
    private let actionTitle: String
    private let protectedContent: ProtectedContent
    private let onAuthenticated: () -> Void

    @State private var isAuthenticating = false
    @State private var errorMessage: String?

    public init(
        reason: String,
        actionTitle: String,
        @ViewBuilder content: () -> ProtectedContent,
        onAuthenticated: @escaping () -> Void
    ) {
        self.reason = reason
        self.actionTitle = actionTitle
        self.protectedContent = content()
        self.onAuthenticated = onAuthenticated
    }

    public var body: some View {
        VStack(spacing: 16) {
            protectedContent

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }

            Button {
                Task { await confirmOwner() }
            } label: {
                if isAuthenticating {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
                    Label(actionTitle, systemImage: "lock.shield")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(isAuthenticating)
            .accessibilityHint("Требуется подтверждение владельца через Face ID или Touch ID")
        }
    }

    @MainActor
    private func confirmOwner() async {
        isAuthenticating = true
        errorMessage = nil
        defer { isAuthenticating = false }

        do {
            try await OwnerBiometricAuthenticator().authenticate(reason: reason)
            onAuthenticated()
        } catch OwnerAuthenticationError.cancelled {
            errorMessage = "Подтверждение отменено. Действие не выполнено."
        } catch OwnerAuthenticationError.unavailable {
            errorMessage = "Face ID или Touch ID недоступен или не настроен. Действие заблокировано."
        } catch {
            errorMessage = "Не удалось подтвердить владельца. Действие заблокировано."
        }
    }
}
