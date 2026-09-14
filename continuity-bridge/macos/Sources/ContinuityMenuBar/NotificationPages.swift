import ContinuityCore
import SwiftUI

struct NotificationsPage: View {
    @ObservedObject var store: NotificationInboxStore
    @ObservedObject var model: BridgeHostModel
    @State private var query = ""

    private var filtered: [NotificationInboxItem] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return store.entries }
        return store.entries.filter {
            "\($0.appLabel) \($0.title) \($0.body)".localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                PageHeading(title: "알림", detail: "휴대폰의 최근 알림을 한곳에서 확인해요.")
                Button("모두 지우기") { model.clearNotifications() }
                    .disabled(store.entries.isEmpty).padding(.top, 8)
            }
            HStack(spacing: 16) {
                TextField("앱 이름이나 알림 내용 검색", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel("알림 검색")
                Toggle("배너 잠시 끄기", isOn: Binding(
                    get: { store.pauseBanners }, set: { model.setBannersPaused($0) }))
                    .toggleStyle(.switch).controlSize(.small).fixedSize()
            }
            Text("진행률은 조용히 갱신하고, 완료·실패할 때 알려줘요.")
                .font(.caption).foregroundStyle(.secondary)
            if filtered.isEmpty {
                WorkspaceEmptyState(symbol: query.isEmpty ? "bell" : "magnifyingglass",
                                    title: query.isEmpty ? "아직 받은 알림이 없어요" : "일치하는 알림이 없어요",
                                    detail: query.isEmpty
                                    ? "Android에서 브리지를 시작하고 알림 접근을 허용하면 여기에 표시돼요."
                                    : "다른 앱 이름이나 단어로 검색해 보세요.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(filtered) { item in
                            NotificationRow(item: item, dismiss: { model.dismissNotification(item.id) })
                                .contextMenu {
                                    Button("\(item.appLabel) 알림 받지 않기") {
                                        model.setAppBlocked(item.packageName, blocked: true)
                                    }
                                }
                        }
                    }.padding(.bottom, 8)
                }
            }
            Text("최근 100개를 이 Mac에 보관해요. 배너는 macOS 집중 모드와 별도로 작동해요.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }.padding(BridgeTheme.contentInset)
    }
}

struct ApplicationFiltersPage: View {
    @ObservedObject var store: NotificationInboxStore
    @ObservedObject var model: BridgeHostModel
    @State private var query = ""

    private var filtered: [NotificationInboxApp] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return store.apps }
        return store.apps.filter {
            "\($0.appLabel) \($0.packageName)".localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            PageHeading(title: "앱별 수신", detail: "맥에서 보고 싶은 앱의 알림만 남겨두세요.")
            InsetSurface {
                Label("새로운 앱의 알림은 기본으로 받아요.", systemImage: "checkmark.circle")
                    .font(.callout)
                Text("알림을 한 번 보낸 앱이 아래에 나타나요. 끄면 이 Mac에서 해당 앱의 알림을 표시하거나 보관하지 않아요.")
                    .font(.caption).foregroundStyle(.secondary).padding(.top, 4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            TextField("앱 이름 검색", text: $query).textFieldStyle(.roundedBorder)
                .accessibilityLabel("앱 검색")
            if filtered.isEmpty {
                WorkspaceEmptyState(symbol: query.isEmpty ? "app.badge" : "magnifyingglass",
                                    title: query.isEmpty ? "아직 등록된 앱이 없어요" : "일치하는 앱이 없어요",
                                    detail: query.isEmpty ? "휴대폰에서 알림이 오면 앱별로 수신 여부를 정할 수 있어요."
                                    : "다른 앱 이름으로 검색해 보세요.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(filtered) { app in
                            InsetSurface {
                                HStack(spacing: 12) {
                                    SourceAppIcon(encoded: app.iconPngBase64, name: app.appLabel)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(app.appLabel).font(.headline)
                                        Text(app.packageName).font(.caption).foregroundStyle(.secondary)
                                            .lineLimit(1).truncationMode(.middle)
                                    }
                                    Spacer(minLength: 8)
                                    Toggle(app.isBlocked ? "차단" : "받기", isOn: Binding(
                                        get: { !app.isBlocked },
                                        set: { model.setAppBlocked(app.packageName, blocked: !$0) }))
                                        .toggleStyle(.switch).fixedSize()
                                        .accessibilityLabel("\(app.appLabel) 알림 받기")
                                }
                            }
                        }
                    }.padding(.bottom, 8)
                }
            }
        }.padding(BridgeTheme.contentInset)
    }
}
