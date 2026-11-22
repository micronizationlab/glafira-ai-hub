import SwiftUI
import GlafiraBridgeKit

/// Owner-created Windows Hub invitation -> TLS-pinned redemption -> biometric Keychain storage.
public struct BridgePairingView: View {
    @State private var invitationText = ""
    @State private var invitation: BridgePairingInvitation?
    @State private var pairingStates: [MobileHubPairingState] = []
    @State private var pendingConfirmation: PairingConfirmation?
    @State private var repairTargetDeviceId: String?
    @State private var status = "Вставьте одноразовое приглашение, созданное в Windows Hub."
    @State private var isBusy = false
    private let lifecycleController: BridgePairingLifecycleController

    public init() {
        lifecycleController = .live()
    }

    public var body: some View {
        Form {
            Section {
                Label("Защищённое сопряжение с Windows Hub", systemImage: "lock.shield")
                    .font(.headline)
                Text("Windows Hub остаётся авторитетным HTTPS-хостом. iPhone проверяет точный endpoint и TLS fingerprint, а ключ сохраняет только после Face ID / Touch ID.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(status)
                    .font(.footnote)
                    .textSelection(.enabled)
            }

            Section("1. Одноразовое приглашение Windows Hub") {
                TextEditor(text: $invitationText)
                    .frame(minHeight: 120)
                    .accessibilityLabel("JSON приглашения Windows Hub")
                Button("Проверить приглашение") { parseInvitation() }
                    .disabled(isBusy || invitationText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let invitation {
                    LabeledContent("Endpoint", value: invitation.endpoint)
                    LabeledContent("Agent ID", value: invitation.agentId)
                    LabeledContent("TLS SHA-256", value: invitation.certificateSHA256)
                    LabeledContent("Scopes", value: invitation.scopes.sorted().joined(separator: ", "))
                    Text("Код действует до \(Date(timeIntervalSince1970: Double(invitation.expiresAtEpochMs) / 1_000), style: .time).")
                        .font(.caption)
                }
            }

            Section("Подключённые устройства") {
                if pairingStates.isEmpty {
                    Text("Нет сохранённых подключений Windows Hub.")
                        .foregroundStyle(.secondary)
                }
                ForEach(pairingStates, id: \.deviceId) { pairingState in
                    PairingStateRow(state: pairingState)
                    if let deviceId = pairingState.deviceId {
                        Menu("Управление подключением") {
                            Button("Переподключить") {
                                pendingConfirmation = .repair(deviceId)
                            }
                            Button("Отключить локально", role: .destructive) {
                                pendingConfirmation = .unpair(deviceId)
                            }
                            Button("Отозвать локальное доверие", role: .destructive) {
                                pendingConfirmation = .revoke(deviceId)
                            }
                        }
                        .disabled(isBusy)
                    }
                }
                Text("Локальный отзыв блокирует credential только на этом iPhone. Для отзыва enrollment на стороне Windows Hub используйте Windows Hub.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("2. Сопряжение и защищённое сохранение") {
                Button {
                    Task { await redeemPairing() }
                } label: {
                    if isBusy { ProgressView() } else {
                        Label("Сопрячь с Windows Hub", systemImage: "faceid")
                    }
                }
                .disabled(isBusy || invitation == nil)
                Text("Одноразовый код отправляется только на указанный HTTPS endpoint с проверкой TLS pin. Полученный 256-битный ключ не записывается в обычные настройки и не синхронизируется через iCloud.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Сопряжение")
        .task { refreshPairingStates() }
        .onReceive(NotificationCenter.default.publisher(for: .glafiraBridgeCredentialDidChange)) { _ in
            refreshPairingStates()
        }
        .alert(item: $pendingConfirmation) { confirmation in
            Alert(
                title: Text(confirmation.title),
                message: Text(confirmation.message),
                primaryButton: confirmation.primaryButton(action: {
                    confirm(confirmation)
                }),
                secondaryButton: .cancel()
            )
        }
    }

    private func parseInvitation() {
        do {
            let parsed = try BridgePairingInvitation.parseDesktopHubBundle(Data(invitationText.utf8))
            if let repairTargetDeviceId, parsed.agentId != repairTargetDeviceId {
                throw BridgeInvitationError.invalidEnrollmentResponse
            }
            invitation = parsed
            status = "Приглашение Windows Hub проверено; можно выполнить одноразовое сопряжение."
        } catch {
            invitation = nil
            status = "Приглашение отклонено. Создайте новое приглашение в Windows Hub и проверьте его целиком."
        }
    }

    @MainActor
    private func redeemPairing() async {
        guard let selectedInvitation = invitation else { return }
        // Remove the one-time code from observable SwiftUI state before any network wait.
        invitationText = ""
        invitation = nil
        isBusy = true
        defer { isBusy = false }
        do {
            let client = try PinnedBridgeHTTPSClient(
                origin: selectedInvitation.endpoint,
                certificateSHA256: selectedInvitation.certificateSHA256
            )
            let response = try await client.redeemPairing(selectedInvitation)
            let credential = try response.credential(matching: selectedInvitation)
            try await KeychainBridgeCredentialStore().upsert(
                credential,
                ownerConfirmationReason: "Подтвердите сопряжение или повторное сопряжение GLAFIRA с Windows Hub"
            )
            repairTargetDeviceId = nil
            NotificationCenter.default.post(name: .glafiraBridgeCredentialDidChange, object: nil)
            refreshPairingStates()
            status = "Сопряжение завершено: credential сохранён в защищённом Keychain."
        } catch {
            status = "Сопряжение не завершено. Не используйте это приглашение повторно; создайте новое в Windows Hub."
        }
    }

    private func confirm(_ confirmation: PairingConfirmation) {
        switch confirmation {
        case .repair(let deviceId):
            repairTargetDeviceId = deviceId
            invitation = nil
            invitationText = ""
            status = "Создайте новое одноразовое приглашение для \(deviceId) в Windows Hub."
        case .unpair(let deviceId):
            Task { await perform(.unpair, deviceId: deviceId) }
        case .revoke(let deviceId):
            Task { await perform(.revoke, deviceId: deviceId) }
        }
    }

    @MainActor
    private func perform(_ action: BridgeLocalPairingAction, deviceId: String) async {
        isBusy = true
        invitation = nil
        invitationText = ""
        defer { isBusy = false }
        let result = await lifecycleController.perform(action, deviceId: deviceId)
        repairTargetDeviceId = nil
        refreshPairingStates()
        NotificationCenter.default.post(name: .glafiraBridgeCredentialDidChange, object: nil)
        switch result {
        case .completed:
            status = action == .unpair
                ? "Локальное подключение удалено; ownership-read очередь устройства очищена."
                : "Локальное доверие отозвано; ownership-read очередь устройства очищена."
        case .credentialChangeFailed:
            status = "Не удалось изменить Keychain. Ownership-read очередь устройства отменена fail-closed."
        case .queueCancellationFailed:
            status = "Keychain обновлён. Очередь не очищена, но сетевой допуск повторно проверит состояние и заблокирует отправку."
        case .credentialAndQueueCancellationFailed:
            status = "Keychain и очередь не удалось изменить. Отправка всё равно требует повторной проверки credential перед сетью."
        }
    }

    private func refreshPairingStates() {
        do {
            pairingStates = try lifecycleController.pairingStates()
        } catch {
            pairingStates = []
            status = "Не удалось прочитать локальное состояние подключений."
        }
    }
}

private enum PairingConfirmation: Identifiable {
    case repair(String)
    case unpair(String)
    case revoke(String)

    var id: String {
        switch self {
        case .repair(let deviceId): return "repair:\(deviceId)"
        case .unpair(let deviceId): return "unpair:\(deviceId)"
        case .revoke(let deviceId): return "revoke:\(deviceId)"
        }
    }

    var title: String {
        switch self {
        case .repair: return "Переподключить устройство?"
        case .unpair: return "Удалить локальное подключение?"
        case .revoke: return "Отозвать локальное доверие?"
        }
    }

    var message: String {
        switch self {
        case .repair(let deviceId):
            return "Потребуется новое одноразовое приглашение Windows Hub для \(deviceId) и подтверждение владельца."
        case .unpair(let deviceId):
            return "Credential \(deviceId) и только его ownership-read очередь будут удалены с этого iPhone."
        case .revoke(let deviceId):
            return "Credential \(deviceId) будет заблокирован на этом iPhone. Enrollment Windows Hub останется активным до отдельного отзыва в Windows Hub."
        }
    }

    var confirmationTitle: String {
        switch self {
        case .repair: return "Продолжить"
        case .unpair: return "Отключить"
        case .revoke: return "Отозвать локально"
        }
    }

    func primaryButton(action: @escaping () -> Void) -> Alert.Button {
        switch self {
        case .repair:
            return .default(Text(confirmationTitle), action: action)
        case .unpair, .revoke:
            return .destructive(Text(confirmationTitle), action: action)
        }
    }
}

private struct PairingStateRow: View {
    let state: MobileHubPairingState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(state.deviceId ?? "Неизвестное устройство", systemImage: stateSymbol)
                .font(.headline)
            Text(stateTitle)
                .font(.caption.weight(.semibold))
            if let desktopHubId = state.desktopHubId {
                Text("Windows Hub: \(desktopHubId)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let endpoint = state.endpoint {
                Text(endpoint)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            if let expiresAtEpochMs = state.expiresAtEpochMs {
                Text("Действует до \(Date(timeIntervalSince1970: Double(expiresAtEpochMs) / 1_000), style: .date)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var stateTitle: String {
        switch state.status {
        case .unpaired: return "Не подключено"
        case .paired: return "Подключено"
        case .expired: return "Credential истёк"
        case .revoked: return "Локальное доверие отозвано"
        case .invalid: return "Локальное состояние повреждено"
        }
    }

    private var stateSymbol: String {
        switch state.status {
        case .paired: return "checkmark.shield"
        case .expired: return "clock.arrow.circlepath"
        case .revoked: return "hand.raised.slash"
        case .invalid: return "exclamationmark.shield"
        case .unpaired: return "lock"
        }
    }
}
