import SwiftUI
import GlafiraBridgeKit

struct GlafiraPairingSettingsRow: Equatable, Identifiable {
    let id: String
    let statusTitle: String
    let symbol: String
    let deviceId: String
    let desktopHubId: String?
    let endpoint: String?
    let certificatePinSummary: String?
    let expiresAt: Date?
    let requiresAttention: Bool

    init(_ state: MobileHubPairingState) {
        deviceId = state.deviceId ?? "Неизвестное устройство"
        id = [state.deviceId, state.desktopHubId, state.endpoint, state.status.rawValue]
            .compactMap { $0 }
            .joined(separator: "|")
        desktopHubId = state.desktopHubId
        endpoint = state.endpoint
        certificatePinSummary = Self.pinSummary(state.certificateSHA256)
        expiresAt = state.expiresAtEpochMs.map {
            Date(timeIntervalSince1970: TimeInterval($0) / 1_000)
        }
        requiresAttention = state.status != .paired

        switch state.status {
        case .paired:
            statusTitle = "Подключено"
            symbol = "checkmark.shield"
        case .expired:
            statusTitle = "Срок credential истёк"
            symbol = "clock.badge.exclamationmark"
        case .revoked:
            statusTitle = "Локально отозвано"
            symbol = "xmark.shield"
        case .invalid:
            statusTitle = "Ошибка состояния"
            symbol = "exclamationmark.shield"
        case .unpaired:
            statusTitle = "Не подключено"
            symbol = "lock.shield"
        }
    }

    private static func pinSummary(_ pin: String?) -> String? {
        guard let pin, pin.count >= 16 else { return nil }
        return "\(pin.prefix(8))…\(pin.suffix(8))"
    }
}

struct GlafiraSettingsSummary: Equatable {
    let activePairingCount: Int
    let attentionPairingCount: Int
    let pendingOperationCount: Int
    let conflictCount: Int

    init(
        pairingStates: [MobileHubPairingState],
        pendingOperationCount: Int,
        conflictCount: Int
    ) {
        let rows = pairingStates.map(GlafiraPairingSettingsRow.init)
        activePairingCount = rows.filter { !$0.requiresAttention }.count
        attentionPairingCount = rows.filter(\.requiresAttention).count
        self.pendingOperationCount = pendingOperationCount
        self.conflictCount = conflictCount
    }
}

struct GlafiraSettingsView: View {
    let pairingStates: [MobileHubPairingState]
    let credentialStatus: String
    @ObservedObject var workspace: GlafiraWorkspaceViewModel

    private var rows: [GlafiraPairingSettingsRow] {
        pairingStates.map(GlafiraPairingSettingsRow.init)
    }

    private var summary: GlafiraSettingsSummary {
        GlafiraSettingsSummary(
            pairingStates: pairingStates,
            pendingOperationCount: workspace.pendingCount,
            conflictCount: workspace.conflictCount
        )
    }

    var body: some View {
        List {
            Section("Подключение") {
                Label(
                    credentialStatus,
                    systemImage: summary.activePairingCount > 0 ? "checkmark.shield" : "lock.shield"
                )

                if rows.isEmpty {
                    Text("Сохранённых устройств нет")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(rows) { row in
                        deviceRow(row)
                    }
                }

                NavigationLink {
                    BridgePairingView()
                } label: {
                    Label("Управление сопряжением", systemImage: "desktopcomputer.and.arrow.down")
                }
            }

            Section("Офлайн-синхронизация") {
                LabeledContent("Ожидают отправки", value: "\(summary.pendingOperationCount)")
                LabeledContent("Требуют решения владельца", value: "\(summary.conflictCount)")
                Text(workspace.status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Граница ответственности") {
                Label("Windows Hub управляет допуском и отзывом устройств", systemImage: "desktopcomputer")
                Label("iPhone хранит credential в Keychain и работает как клиент", systemImage: "iphone")
                Text("Параметры, не подтверждённые Windows Hub или GLAFIRA CORE, здесь не создаются локально.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Настройки")
        .task { await workspace.reload() }
    }

    @ViewBuilder
    private func deviceRow(_ row: GlafiraPairingSettingsRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(row.deviceId, systemImage: row.symbol)
                .font(.headline)
            Text(row.statusTitle)
                .foregroundStyle(row.requiresAttention ? Color.orange : Color.secondary)
            if let desktopHubId = row.desktopHubId {
                LabeledContent("Windows Hub", value: desktopHubId)
            }
            if let endpoint = row.endpoint {
                LabeledContent("HTTPS endpoint", value: endpoint)
            }
            if let pin = row.certificatePinSummary {
                LabeledContent("TLS pin", value: pin)
                    .font(.caption.monospaced())
            }
            if let expiresAt = row.expiresAt {
                LabeledContent("Credential до") {
                    Text(expiresAt, format: .dateTime.year().month().day().hour().minute())
                }
            }
        }
        .padding(.vertical, 4)
    }
}
