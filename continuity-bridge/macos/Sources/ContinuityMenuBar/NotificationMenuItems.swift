import ContinuityCore
import SwiftUI

struct NotificationMenuItems: View {
    @ObservedObject var store: NotificationInboxStore
    let model: BridgeHostModel
    let open: () -> Void

    var body: some View {
        Button("알림 \(store.entries.count)개 보기", action: open)
        ForEach(Array(store.entries.prefix(3))) { item in
            Button("\(item.appLabel) · \(item.isRedacted ? "내용 숨김" : String(item.title.prefix(30)))",
                   action: open)
        }
        Toggle("배너 잠시 끄기", isOn: Binding(
            get: { store.pauseBanners }, set: { model.setBannersPaused($0) }))
    }
}
