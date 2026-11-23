import SwiftUI
import GlafiraBridgeKit

/// Complete owner-mediated iOS enrollment flow:
/// invitation -> proof submission -> Android owner approval -> correlated credential import.
public struct BridgePairingView: View {
    @State private var invitationText = ""
    @State private var credentialText = ""
    @State private var deviceLabel = "GLAFIRA iPhone"
    @State private var requestedScopesText = "project.read"
    @State private var submittedScopes: Set<String> = []
    @State private var invitation: BridgePairingInvitation?
    @State private var pending: BridgePairingPendingResponse?
    @State private var status = "Вставьте одноразовое приглашение, созданное на Android."
    @State private var isBusy = false

    public init() {}

    private var requestedScopes: Set<String> {
        Set(requestedScopesText.split(separator: ",").map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty })
    }

    public var body: some View {
        Form {
            Section {
                Label("Защищённое сопряжение с Android", systemImage: "lock.shield")
                    .font(.headline)
                Text("Сопряжение не выдаёт права автоматически. Android-владелец отдельно подтверждает запрос и выбирает разрешённые scopes.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(status)
                    .font(.footnote)
                    .textSelection(.enabled)
            }

            Section("1. Одноразовое приглашение Android") {
                TextEditor(text: $invitationText)
                    .frame(minHeight: 100)
                    .accessibilityLabel("JSON приглашения Android")
                Button("Проверить приглашение") {
                    parseInvitation()
                }
                .disabled(isBusy || invitationText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let invitation {
                    LabeledContent("Сервер", value: invitation.origin)
                    LabeledContent("Ticket ID", value: invitation.ticketId)
                    LabeledContent("TLS SHA-256", value: invitation.certificateSHA256)
                    Text("Приглашение действительно до \(Date(timeIntervalSince1970: Double(invitation.expiresAtEpochMs) / 1_000), style: .time).")
                        .font(.caption)
                }
            }

            Section("2. Запросить сопряжение") {
                TextField("Имя устройства", text: $deviceLabel)
                TextField("Запрашиваемые scopes через запятую", text: $requestedScopesText)
                    .autocorrectionDisabled()
                    .disabled(pending != nil)
                Button {
                    Task { await submitPairing() }
                } label: {
                    if isBusy { ProgressView() } else { Text("Отправить запрос владельцу Android") }
                }
                .disabled(isBusy || invitation == nil || pending != nil)
                if let pending {
                    LabeledContent("Approval ID", value: pending.approvalId)
                    LabeledContent("Статус", value: pending.status)
                    Text("Ожидайте решения владельца Android. После одобрения скопируйте там пакет GLAFIRA_BRIDGE_CREDENTIAL_V1 и вставьте его ниже.")
                        .font(.footnote)
                }
            }

            Section("3. Импорт ключа после одобрения Android") {
                TextEditor(text: $credentialText)
                    .frame(minHeight: 120)
                    .accessibilityLabel("JSON пакета одобренных учётных данных")
                Button {
                    Task { await importCredential() }
                } label: {
                    if isBusy { ProgressView() } else { Label("Проверить ID и импортировать в Keychain", systemImage: "faceid") }
                }
                .disabled(isBusy || pending == nil || invitation == nil ||
                          credentialText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Text("Импорт требует локального подтверждения Face ID / Touch ID. Пакет принимается только при совпадении approvalId, ticketId, адреса сервера и TLS fingerprint с текущим запросом.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Сопряжение")
    }

    private func parseInvitation() {
        do {
            let parsed = try BridgePairingInvitation.parseAndroidBundle(Data(invitationText.utf8))
            invitation = parsed
            pending = nil
            submittedScopes = []
            credentialText = ""
            status = "Приглашение проверено. Сервер и срок действия приняты; можно отправить запрос."
        } catch {
            invitation = nil
            pending = nil
            status = "Приглашение отклонено: \(error.localizedDescription)"
        }
    }

    @MainActor
    private func submitPairing() async {
        guard let invitation else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let scopes = requestedScopes
            guard !scopes.isEmpty else {
                status = "Укажите хотя бы один запрашиваемый scope."
                return
            }
            let submission = try BridgePairing.makeSubmission(
                invitation: invitation,
                deviceLabel: deviceLabel,
                requestedScopes: Array(scopes)
            )
            let client = try PinnedBridgeHTTPSClient(
                origin: invitation.origin,
                certificateSHA256: invitation.certificateSHA256
            )
            let response = try await BridgePairing.submit(
                submission,
                invitation: invitation,
                client: client
            )
            pending = response
            submittedScopes = scopes
            status = "Запрос принят Android и ожидает ручного решения владельца. Approval ID сохранён для проверки пакета."
        } catch {
            status = "Не удалось отправить запрос: \(error.localizedDescription)"
        }
    }

    @MainActor
    private func importCredential() async {
        guard let invitation, let pending else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let bundle = try OwnerApprovedBridgeCredentialBundle.parse(
                Data(credentialText.utf8),
                expectedOrigin: invitation.origin,
                expectedCertificateSHA256: invitation.certificateSHA256,
                expectedApprovalId: pending.approvalId,
                expectedTicketId: invitation.ticketId,
                requestedScopes: submittedScopes
            )
            try await BridgeCredentialBundleImporter.importOwnerBundle(
                bundle,
                credentialStore: KeychainBridgeCredentialStore()
            )
            credentialText = ""
            NotificationCenter.default.post(name: .glafiraBridgeCredentialDidChange, object: nil)
            status = "Ключ успешно сохранён в защищённый Keychain после биометрического подтверждения. Approval ID и Ticket ID совпали."
        } catch {
            status = "Пакет не импортирован: \(error.localizedDescription)"
        }
    }
}
