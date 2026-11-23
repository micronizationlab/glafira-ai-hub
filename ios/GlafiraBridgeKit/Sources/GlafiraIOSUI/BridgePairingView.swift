import SwiftUI
import GlafiraBridgeKit

/// Owner-created Windows Hub invitation -> TLS-pinned redemption -> biometric Keychain storage.
public struct BridgePairingView: View {
    @State private var invitationText = ""
    @State private var invitation: BridgePairingInvitation?
    @State private var pairedDeviceIds: [String] = []
    @State private var status = "Вставьте одноразовое приглашение, созданное в Windows Hub."
    @State private var isBusy = false

    public init() {}

    public var body: some View {
        Form {
            Section {
                Label("Защищённое сопряжение с Windows Hub", systemImage: "lock.shield")
                    .font(.headline)
                Text("Windows Hub остаётся авторитетным HTTPS-хостом. iPhone проверяет точный endpoint, TLS fingerprint и ownership signing key.")
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

            Section("Сохранённые подключения") {
                if pairedDeviceIds.isEmpty {
                    Text("Нет сохранённых подключений Windows Hub.")
                        .foregroundStyle(.secondary)
                }
                ForEach(pairedDeviceIds, id: \.self) { deviceId in
                    Label(deviceId, systemImage: "checkmark.shield")
                }
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
                Text("Одноразовый код отправляется только на указанный HTTPS endpoint с проверкой TLS pin. Полученный 256-битный ключ сохраняется с Hub binding только в защищённом Keychain.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Сопряжение")
        .task { refreshPairedDeviceIds() }
    }

    private func parseInvitation() {
        do {
            invitation = try BridgePairingInvitation.parseDesktopHubBundle(Data(invitationText.utf8))
            status = "Приглашение Windows Hub проверено; можно выполнить одноразовое сопряжение."
        } catch {
            invitation = nil
            status = "Приглашение отклонено. Создайте новое приглашение в Windows Hub и вставьте его целиком."
        }
    }

    @MainActor
    private func redeemPairing() async {
        guard let selectedInvitation = invitation else { return }
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
                ownerConfirmationReason: "Подтвердите сопряжение GLAFIRA с Windows Hub"
            )
            NotificationCenter.default.post(name: .glafiraBridgeCredentialDidChange, object: nil)
            refreshPairedDeviceIds()
            status = "Сопряжение завершено: credential и Hub binding сохранены в защищённом Keychain."
        } catch {
            status = "Сопряжение не завершено. Не используйте приглашение повторно; создайте новое в Windows Hub."
        }
    }

    private func refreshPairedDeviceIds() {
        do {
            pairedDeviceIds = try KeychainBridgeCredentialStore().listDeviceIds()
        } catch {
            pairedDeviceIds = []
            status = "Не удалось прочитать локальное состояние подключений."
        }
    }
}
