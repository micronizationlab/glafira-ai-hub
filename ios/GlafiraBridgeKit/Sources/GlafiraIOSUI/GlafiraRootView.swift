import SwiftUI
import GlafiraBridgeKit

/// Native adaptive iPhone/iPad client. Live project data is supplied by the
/// authoritative Windows Hub; this view intentionally contains no mock projects.
public struct GlafiraRootView: View {
    @State private var pairingStates: [MobileHubPairingState] = []
    @State private var credentialStatus = "Проверяем локальные учётные данные…"
    @StateObject private var workspace = GlafiraWorkspaceViewModel()
    @StateObject private var ownership = OwnershipSyncViewModel()

    public init() {}

    public var body: some View {
        NavigationSplitView {
            List {
                Section("Подключение и устройства") {
                    Label(credentialStatus, systemImage: hasActivePairing ? "checkmark.shield" : "lock")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ForEach(pairingStates, id: \.deviceId) { pairingState in
                        Label(
                            "\(pairingState.deviceId ?? "Неизвестное устройство") — \(stateTitle(pairingState.status))",
                            systemImage: pairingState.status == .paired ? "iphone" : "exclamationmark.shield"
                        )
                        .font(.subheadline)
                    }
                    NavigationLink {
                        BridgePairingView()
                    } label: {
                        Label("Сопряжение через Windows Hub", systemImage: "desktopcomputer.and.arrow.down")
                    }
                    Text("iPhone остаётся клиентом. Одноразовый допуск и отзыв устройства подтверждаются владельцем на Windows Hub.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Рабочее пространство") {
                    NavigationLink {
                        GlafiraProjectsView(model: workspace)
                    } label: {
                        Label("Проекты", systemImage: "folder")
                    }

                    NavigationLink {
                        GlafiraAgentsPlaceholderView()
                    } label: {
                        Label("Агенты", systemImage: "person.3")
                    }

                    NavigationLink {
                        GlafiraOwnershipView(model: ownership, projects: workspace.projects)
                    } label: {
                        Label("Владение", systemImage: "person.crop.circle.badge.checkmark")
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
            GlafiraWelcomeView(hasPairedDevice: hasActivePairing)
        }
        .navigationSplitViewStyle(.balanced)
        .task {
            refreshCredentialState()
            await workspace.reload()
            await ownership.reload()
        }
        .onReceive(NotificationCenter.default.publisher(for: .glafiraBridgeCredentialDidChange)) { _ in
            refreshCredentialState()
            Task {
                await workspace.reload()
                await ownership.reload()
            }
        }
    }

    private func refreshCredentialState() {
        do {
            pairingStates = try KeychainBridgeCredentialStore().pairingStates()
            credentialStatus = pairingStates.isEmpty
                ? "Нет сохранённых сопряжённых устройств"
                : hasActivePairing
                    ? "Активный credential сохранён в Keychain"
                    : "Подключения требуют внимания"
        } catch {
            pairingStates = []
            credentialStatus = "Не удалось прочитать состояние Keychain"
        }
    }

    private var hasActivePairing: Bool {
        pairingStates.contains { $0.status == .paired }
    }

    private func stateTitle(_ status: MobileHubPairingStatus) -> String {
        switch status {
        case .unpaired: return "не подключено"
        case .paired: return "подключено"
        case .expired: return "истёк"
        case .revoked: return "локально отозвано"
        case .invalid: return "ошибка состояния"
        }
    }
}

private struct GlafiraOwnershipView: View {
    @ObservedObject var model: OwnershipSyncViewModel
    let projects: [MobileWorkspaceProjectSummary]

    var body: some View {
        List {
            Section("Состояние") {
                Text(model.status)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Text("iPhone только читает подписанное состояние. Передача владения, lease и разрешение выполнения остаются на Windows Hub.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if !projects.isEmpty {
                Section("Проверить Windows Hub") {
                    ForEach(model.pairings, id: \.deviceId) { pairing in
                        if let deviceId = pairing.deviceId, pairing.status == .paired {
                            ForEach(projects) { project in
                                Button {
                                    Task {
                                        await model.discoverTransfers(
                                            projectId: project.id,
                                            deviceId: deviceId
                                        )
                                    }
                                } label: {
                                    Label(
                                        "\(project.name) · \(deviceId)",
                                        systemImage: "arrow.triangle.2.circlepath"
                                    )
                                }
                                .disabled(model.isDiscovering(
                                    projectId: project.id,
                                    deviceId: deviceId
                                ))
                            }
                        }
                    }
                    Text("Список transfer ID получает только подписанный project-scoped запрос; ручной ввод ID недоступен.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if !model.pending.isEmpty {
                Section("Ожидают синхронизации") {
                    ForEach(model.pairings, id: \.deviceId) { pairing in
                        if let deviceId = pairing.deviceId,
                           model.pendingCount(deviceId: deviceId) > 0 {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(deviceId)
                                    .font(.headline)
                                Text("Проектов в очереди: \(model.pendingCount(deviceId: deviceId))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Button("Безопасно повторить чтение") {
                                    Task { await model.syncNext(deviceId: deviceId) }
                                }
                                .disabled(pairing.status != .paired ||
                                          model.syncingDeviceIds.contains(deviceId))
                            }
                        }
                    }
                    ForEach(model.pending.filter { work in
                        !model.pairings.contains { $0.deviceId == work.deviceId }
                    }) { work in
                        Label(
                            "Проект \(work.projectId): требуется новое сопряжение",
                            systemImage: "exclamationmark.shield"
                        )
                        .font(.caption)
                    }
                }
            }

            if !model.projections.isEmpty {
                Section("Проверенные владельцы") {
                    ForEach(model.projections) { projection in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(projection.taskId)
                                .font(.headline)
                            Text("Проект: \(projection.projectId)")
                            Text("Владелец: \(projection.ownerCoreId) · поколение \(projection.ownerGeneration)")
                            Text("Версия состояния: \(projection.stateVersion)")
                                .foregroundStyle(.secondary)
                        }
                        .font(.caption)
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
        .task { await model.reload() }
        .refreshable { await model.reload() }
        .navigationTitle("Владение")
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
                 ? "Ключ хранится в Keychain. Проекты и диалоги загружаются только из авторитетного Windows Hub."
                 : "После безопасного сопряжения с Windows Hub здесь появятся разрешённые проекты и диалоги.")
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

private struct GlafiraProjectsView: View {
    @ObservedObject var model: GlafiraWorkspaceViewModel

    var body: some View {
        List {
            Section("Синхронизация") {
                Text(model.status)
                    .font(.callout)
                    .foregroundStyle(model.conflictCount > 0 ? Color.orange : Color.secondary)

                if model.pendingCount > 0 {
                    ForEach(model.pairings, id: \.deviceId) { pairing in
                        if let deviceId = pairing.deviceId {
                            Button {
                                Task { await model.syncNext(deviceId: deviceId) }
                            } label: {
                                Label(
                                    "Отправить следующее сообщение через \(deviceId)",
                                    systemImage: "arrow.up.circle"
                                )
                            }
                            .disabled(pairing.status != .paired ||
                                      model.syncingDeviceIds.contains(deviceId))
                        }
                    }
                    if !model.pairings.contains(where: { $0.status == .paired }) {
                        Label(
                            "Для отправки восстановите сопряжение с Windows Hub",
                            systemImage: "exclamationmark.shield"
                        )
                        .font(.caption)
                    }
                    Text("Отправляется одна durable-операция только после нажатия владельцем. Автоматическая фоновая отправка не выполняется.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if model.projects.isEmpty {
                Section("Проекты") {
                    Label("Разрешённых проектов пока нет", systemImage: "folder")
                        .foregroundStyle(.secondary)
                }
            } else {
                Section("Проекты") {
                    ForEach(model.projects) { project in
                        NavigationLink {
                            GlafiraProjectConversationsView(model: model, project: project)
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(project.name)
                                    .font(.headline)
                                Text("Диалогов: \(project.conversationCount) · ожидают: \(project.pendingCount)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if project.conflictCount > 0 {
                                    Label("Нужно разрешить конфликт: \(project.conflictCount)", systemImage: "exclamationmark.triangle")
                                        .font(.caption)
                                        .foregroundStyle(.orange)
                                }
                            }
                        }
                        .accessibilityLabel("Открыть проект \(project.name)")
                    }
                }
            }
        }
        .task { await model.reload() }
        .refreshable { await model.reload() }
        .navigationTitle("Проекты")
    }
}

private struct GlafiraProjectConversationsView: View {
    @ObservedObject var model: GlafiraWorkspaceViewModel
    let project: MobileWorkspaceProjectSummary
    @State private var conversations: [MobileConversationSummary] = []

    var body: some View {
        Group {
            if conversations.isEmpty {
                ContentPlaceholder(
                    title: project.name,
                    message: "Для этого проекта пока нет синхронизированных диалогов.",
                    symbol: "bubble.left.and.bubble.right"
                )
            } else {
                List(conversations) { conversation in
                    NavigationLink {
                        GlafiraConversationView(model: model, project: project, summary: conversation)
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(conversation.title)
                                .font(.headline)
                            Text("Сообщений: \(conversation.messageCount) · ожидают: \(conversation.pendingCount)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if conversation.conflictCount > 0 {
                                Label("Конфликт версии — требуется решение владельца", systemImage: "exclamationmark.triangle")
                                    .font(.caption)
                                    .foregroundStyle(.orange)
                            }
                        }
                    }
                    .accessibilityLabel("Открыть диалог \(conversation.title)")
                }
            }
        }
        .task { conversations = await model.conversations(projectId: project.id) }
        .navigationTitle(project.name)
    }
}

private struct GlafiraConversationView: View {
    @ObservedObject var model: GlafiraWorkspaceViewModel
    let project: MobileWorkspaceProjectSummary
    let summary: MobileConversationSummary
    @State private var conversation: MobileConversation?
    @State private var outbox: [MobileOutboxEntry] = []
    @State private var draft = ""
    @State private var errorMessage: String?

    var body: some View {
        VStack(spacing: 0) {
            List {
                if let conversation {
                    ForEach(conversation.messages) { message in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(message.isOwner ? "Вы" : summary.title)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            Text(message.text)
                            if message.origin == .ownerOffline {
                                Label("Ожидает синхронизации", systemImage: "clock.arrow.circlepath")
                                    .font(.caption2)
                                    .foregroundStyle(.orange)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                }

                let conflicts = outbox.filter { $0.state == .requiresOwnerResolution }
                if !conflicts.isEmpty {
                    Section("Конфликты") {
                        ForEach(conflicts) { entry in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(entry.text)
                                    .lineLimit(3)
                                Text("Windows Hub изменил диалог после создания этого сообщения.")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                HStack {
                                    Button("Повторить поверх текущей версии") {
                                        Task { await resolve(entry, choice: .rebaseOntoCurrentRemoteVersion) }
                                    }
                                    Button("Отменить локальное", role: .destructive) {
                                        Task { await resolve(entry, choice: .discardLocalMessage) }
                                    }
                                }
                                .font(.caption)
                            }
                        }
                    }
                }
            }

            Divider()
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    TextField("Сообщение для офлайн-очереди", text: $draft, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                    Button("В очередь") {
                        Task { await queueDraft() }
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              outbox.contains { $0.state == .requiresOwnerResolution })
                }
                if let errorMessage {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                } else {
                    Text("Сообщение сохраняется локально и не отправляется повторно без точного ACK Windows Hub.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding()
        }
        .task { await reload() }
        .navigationTitle(summary.title)
    }

    private func reload() async {
        conversation = await model.conversation(
            projectId: project.id,
            conversationId: summary.conversationId
        )
        outbox = await model.outbox(projectId: project.id, conversationId: summary.conversationId)
    }

    private func queueDraft() async {
        guard let conversation else { return }
        let message = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !message.isEmpty else { return }
        errorMessage = await model.queueOfflineMessage(
            projectId: project.id,
            conversationId: summary.conversationId,
            title: summary.title,
            remoteVersion: conversation.remoteVersion,
            text: message
        )
        if errorMessage == nil { draft = "" }
        await reload()
    }

    private func resolve(_ entry: MobileOutboxEntry, choice: MobileConflictChoice) async {
        errorMessage = await model.resolveConflict(operationId: entry.operationId, choice: choice)
        await reload()
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
