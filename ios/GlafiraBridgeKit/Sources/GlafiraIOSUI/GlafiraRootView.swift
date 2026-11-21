import SwiftUI
import GlafiraBridgeKit

/// Native adaptive iPhone/iPad shell. Live project data is supplied later by the
/// authenticated Core repository; this view intentionally contains no mock projects.
public struct GlafiraRootView: View {
    @State private var pairedDeviceIds: [String] = []
    @State private var credentialStatus = "Проверяем локальные учётные данные…"

    public init() {}

    public var body: some View {
        NavigationSplitView {
            List {
                Section("Подключение и устройства") {
                    Label(credentialStatus, systemImage: pairedDeviceIds.isEmpty ? "lock" : "checkmark.shield")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ForEach(pairedDeviceIds, id: \\.self) { deviceId in
                        Label(deviceId, systemImage: "iphone")
                            .font(.subheadline)
                    }
                    NavigationLink {
                        BridgePairingView()
                    } label: {
                        Label("Сопряжение Android", systemImage: "iphone.and.arrow.right.inward")
                    }
                }

                Section("Рабочее пространство") {
                    NavigationLink {
                        GlafiraProjectsPlaceholderView()
                    } label: {
                        Label("Проекты", systemImage: "folder")
                    }

                    NavigationLink {
                        GlafiraAgentsPlaceholderView()
                    } label: {
                        Label("Агенты", systemImage: "person.3")
                    }

                    NavigationLink {
                        GlafiraSettingsPlaceholderView()
                    } label: {
                        Label("Настройки", systemImage: "gearshape")
                    }
                }
            }
            .navigationTitle("GLAFIRA")
        } detail: {
            GlafiraWelcomeView(hasPairedDevice: !pairedDeviceIds.isEmpty)
        }
        .navigationSplitViewStyle(.balanced)
        .task { refreshCredentialState() }
        .onReceive(NotificationCenter.default.publisher(for: .glafiraBridgeCredentialDidChange)) { _ in
            refreshCredentialState()
        }
    }

    private func refreshCredentialState() {
        do {
            pairedDeviceIds = try KeychainBridgeCredentialStore().listDeviceIds()
            credentialStatus = pairedDeviceIds.isEmpty
                ? "Нет сохранённых сопряжённых устройств"
                : "Ключи сопряжения сохранены в Keychain"
        } catch {
            pairedDeviceIds = []
            credentialStatus = "Не удалось прочитать состояние Keychain"
        }
    }
}

private struct GlafiraWelcomeView: View {
    let hasPairedDevice: Bool

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 44, weight: .regular))
                .accessibilityHidden(true)

            Text("Глафира")
                .font(.largeTitle.weight(.semibold))

            Text("Единое рабочее пространство проектов и ИИ-агентов")
                .font(.title3)
                .multilineTextAlignment(.center)

            Label(
                hasPairedDevice ? "Ключ сопряжения сохранён" : "Устройство ещё не сопряжено",
                systemImage: hasPairedDevice ? "checkmark.shield" : "lock.shield"
            )
                .font(.callout)
                .foregroundStyle(.secondary)

            Text(hasPairedDevice
                 ? "Ключ хранится в Keychain. Проекты и диалоги появятся после подключения к доступному Core."
                 : "После безопасного сопряжения здесь появятся ваши проекты и диалоги.")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 440)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
    }
}

private struct GlafiraProjectsPlaceholderView: View {
    var body: some View {
        ContentPlaceholder(
            title: "Проекты",
            message: "Список проектов загрузится из GLAFIRA CORE после защищённого подключения.",
            symbol: "folder"
        )
        .navigationTitle("Проекты")
    }
}

private struct GlafiraAgentsPlaceholderView: View {
    var body: some View {
        ContentPlaceholder(
            title: "ИИ-агенты",
            message: "Роли и доступные агенты будут получены из общего реестра GLAFIRA CORE.",
            symbol: "person.3"
        )
        .navigationTitle("Агенты")
    }
}

private struct GlafiraSettingsPlaceholderView: View {
    var body: some View {
        ContentPlaceholder(
            title: "Настройки",
            message: "Здесь будут состояние подключения, управление устройством, голос и параметры приложения.",
            symbol: "gearshape"
        )
        .navigationTitle("Настройки")
    }
}

private struct ContentPlaceholder: View {
    let title: String
    let message: String
    let symbol: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 36))
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.weight(.semibold))
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 420)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
