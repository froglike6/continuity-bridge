import Foundation

enum NotificationInboxPresentation {
    static func progressState(for payload: NotificationPayload,
                              previous: NotificationInboxPresentationRecord?) -> NotificationInboxProgressState {
        if payload.category == "err" || payload.category == "error" { return .failed }
        if let progress = payload.progress {
            return !progress.indeterminate && progress.max > 0 && progress.value == progress.max ? .completed : .active
        }
        guard let previous, previous.packageName == payload.packageName else { return .none }
        switch previous.progressState {
        case .active, .unknown: return .unknown
        case .completed: return .completed
        case .failed: return .failed
        case .none: return .none
        }
    }

    static func shouldPresent(_ item: NotificationInboxItem,
                              replacing previous: NotificationInboxPresentationRecord?) -> Bool {
        guard let previous, previous.packageName == item.packageName else { return true }
        switch item.progressState {
        case .active:
            return previous.progressState != .active && previous.progressState != .unknown
        case .completed, .failed:
            return previous.progressState != .completed && previous.progressState != .failed
        case .unknown:
            return false
        case .none:
            return NotificationInboxPresentationRecord.digest(item) != previous.contentDigest
        }
    }
}
