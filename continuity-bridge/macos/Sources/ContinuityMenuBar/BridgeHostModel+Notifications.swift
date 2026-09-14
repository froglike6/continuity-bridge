import ContinuityCore
import Foundation

extension BridgeHostModel {
    func receiveNotification(_ event: BridgeEvent) throws {
        guard let notificationInbox else { throw NotificationApplyError.addFailed }
        do {
            let receipt = try notificationInbox.receive(event)
            notificationError = nil
            notificationBanners.receive(receipt)
        } catch {
            notificationError = "알림을 저장하지 못했어요. 저장 공간과 파일 접근 권한을 확인해 주세요."
            throw error
        }
    }

    func setAppBlocked(_ package: String, blocked: Bool) {
        performNotificationChange {
            try $0.setBlocked(packageName: package, blocked: blocked)
            notificationBanners.retainOnly(Set($0.entries.map(\.id)))
        }
    }

    func dismissNotification(_ id: String) {
        performNotificationChange {
            try $0.dismiss(id: id)
            notificationBanners.dismiss(id)
        }
    }

    func clearNotifications() {
        performNotificationChange {
            try $0.clear()
            notificationBanners.dismissAll()
        }
    }

    func setBannersPaused(_ paused: Bool) {
        performNotificationChange {
            try $0.setPauseBanners(paused)
            if paused { notificationBanners.dismissAll() }
        }
    }

    private func performNotificationChange(_ change: (NotificationInboxStore) throws -> Void) {
        guard let notificationInbox else { return }
        do {
            try change(notificationInbox)
            notificationError = nil
        } catch {
            notificationError = "변경을 저장하지 못했어요. 저장 공간과 파일 접근 권한을 확인해 주세요."
        }
    }
}
