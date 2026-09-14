import AppKit
import ContinuityCore
import SwiftUI

struct WorkspaceView: View {
    @ObservedObject var model: BridgeHostModel
    @ObservedObject var navigation: WorkspaceNavigation

    private var selection: Binding<WorkspacePage?> {
        Binding(get: { navigation.page }, set: { if let page = $0 { navigation.page = page } })
    }

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 8) {
                    BridgeIdentity(size: 36)
                    Text("연속성 브리지").font(.headline)
                }.padding(.horizontal, 12).padding(.top, 16)
                List(WorkspacePage.allCases, selection: selection) { page in
                    Label(page.rawValue, systemImage: page.symbol).tag(page)
                        .padding(.vertical, 4)
                }.listStyle(.sidebar).scrollContentBackground(.hidden)
                VStack(alignment: .leading, spacing: 6) {
                    Label(model.status.koreanText, systemImage: model.status == .connected
                          ? "checkmark.circle.fill" : "circle")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(model.status == .connected ? .green : .secondary)
                    Text("Mac ↔ Android").font(.caption2).foregroundStyle(.secondary)
                }.padding(16)
            }
            .frame(width: BridgeTheme.sidebarWidth)
            .background(.ultraThinMaterial)
            Divider()
            VStack(spacing: 0) {
                if let error = model.notificationError {
                    Label(error, systemImage: "exclamationmark.circle")
                        .font(.callout).foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, BridgeTheme.contentInset).padding(.vertical, 12)
                }
                switch navigation.page {
                case .overview:
                    OverviewPage(model: model, navigation: navigation)
                case .notifications:
                    if let store = model.notificationInbox {
                        NotificationsPage(store: store, model: model)
                    } else {
                        WorkspaceEmptyState(symbol: "exclamationmark.bubble",
                                            title: "알림 기록을 열 수 없어요",
                                            detail: "저장 공간과 접근 권한을 확인한 뒤 앱을 다시 시작해 주세요.")
                    }
                case .applications:
                    if let store = model.notificationInbox {
                        ApplicationFiltersPage(store: store, model: model)
                    } else {
                        WorkspaceEmptyState(symbol: "app.badge",
                                            title: "앱 목록을 열 수 없어요",
                                            detail: "알림 저장소를 확인한 뒤 앱을 다시 시작해 주세요.")
                    }
                case .connection:
                    ConnectionPreferencesView(model: model)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Color(nsColor: .windowBackgroundColor))
        }
        .frame(minWidth: 780, minHeight: 600)
        .task { await model.refreshSystemStates() }
    }
}

struct PageHeading: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.largeTitle.weight(.semibold))
            Text(detail).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ConnectionPreferencesView: View {
    @ObservedObject var model: BridgeHostModel
    @State private var showsConfiguration = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PageHeading(title: "연결 설정", detail: "두 기기가 연결할 서버와 시작 방식을 관리해요.")
                .padding(BridgeTheme.contentInset)
            Form {
                ConnectionSettingsSection(model: model, showsConfiguration: $showsConfiguration,
                                          onEdit: editConfiguration)
                Section("Mac 시작 옵션") {
                    Toggle("로그인 시 자동으로 시작", isOn: Binding(
                        get: { model.openAtLogin }, set: { model.setOpenAtLogin($0) }))
                    Text(model.loginItemText).font(.callout).foregroundStyle(.secondary)
                }
            }.formStyle(.grouped)
        }
        .onChange(of: model.configurationHasError) { hasError in
            if hasError { showsConfiguration = true }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await model.refreshSystemStates() }
        }
    }

    private func editConfiguration() async {
        if model.isRunning { await model.stop() }
        showsConfiguration = true
    }
}
