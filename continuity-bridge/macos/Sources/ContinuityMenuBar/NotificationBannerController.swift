import AppKit
import ContinuityCore
import CoreGraphics
import SwiftUI

private final class NotificationPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

@MainActor
final class NotificationBannerController {
    private var windows: [String: NSPanel] = [:]
    private var order: [String] = []
    private var timers: [String: Task<Void, Never>] = [:]
    private var observers: [NSObjectProtocol] = []
    private var sessionActive = true
    var openNotifications: (() -> Void)?

    init() {
        let workspace = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.sessionDidResignActiveNotification, NSWorkspace.screensDidSleepNotification] {
            observers.append(workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    self?.sessionActive = false
                    self?.dismissAll()
                }
            })
        }
        for name in [NSWorkspace.sessionDidBecomeActiveNotification, NSWorkspace.screensDidWakeNotification] {
            observers.append(workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.sessionActive = true }
            })
        }
        let distributed = DistributedNotificationCenter.default()
        observers.append(distributed.addObserver(forName: .init("com.apple.screenIsLocked"),
                                                 object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.sessionActive = false
                self?.dismissAll()
            }
        })
        observers.append(distributed.addObserver(forName: .init("com.apple.screenIsUnlocked"),
                                                 object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.sessionActive = true }
        })
    }

    func receive(_ receipt: NotificationInboxReceipt) {
        guard let item = receipt.item else { return }
        if windows[item.id] != nil {
            update(item)
            if receipt.shouldPresent { scheduleDismissal(item.id) }
        } else if receipt.shouldPresent {
            present(item)
        }
    }

    func dismiss(_ id: String) {
        timers.removeValue(forKey: id)?.cancel()
        windows.removeValue(forKey: id)?.orderOut(nil)
        order.removeAll { $0 == id }
        arrange()
    }

    func dismissAll() {
        for task in timers.values { task.cancel() }
        timers.removeAll()
        for window in windows.values { window.orderOut(nil) }
        windows.removeAll()
        order.removeAll()
    }

    func retainOnly(_ ids: Set<String>) {
        for id in order where !ids.contains(id) { dismiss(id) }
    }

    private func present(_ item: NotificationInboxItem) {
        guard sessionActive, !screenLocked else { return }
        if order.count >= 3, let oldest = order.first { dismiss(oldest) }
        let panel = NotificationPanel(contentRect: .zero,
                                      styleMask: [.borderless, .nonactivatingPanel],
                                      backing: .buffered, defer: false)
        panel.title = "\(item.appLabel) 알림"
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.level = .floating
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        windows[item.id] = panel
        order.append(item.id)
        update(item)
        arrange()
        panel.orderFrontRegardless()
        scheduleDismissal(item.id)
    }

    private func update(_ item: NotificationInboxItem) {
        guard let window = windows[item.id] else { return }
        let root = NotificationBannerView(item: item, close: { [weak self] in self?.dismiss(item.id) },
                                          open: { [weak self] in
            self?.dismiss(item.id)
            self?.openNotifications?()
        }, hover: { [weak self] hovering in
            if hovering { self?.timers.removeValue(forKey: item.id)?.cancel() }
            else { self?.scheduleDismissal(item.id) }
        })
        let view = NSHostingView(rootView: root)
        window.contentView = view
        window.setContentSize(NSSize(width: BridgeTheme.bannerWidth,
                                     height: min(300, max(100, view.fittingSize.height))))
        arrange()
    }

    private func scheduleDismissal(_ id: String) {
        timers.removeValue(forKey: id)?.cancel()
        timers[id] = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(7)) }
            catch { return }
            self?.dismiss(id)
        }
    }

    private func arrange() {
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }
        let available = screen.visibleFrame
        var top = available.maxY - BridgeTheme.bannerInset
        for id in order.reversed() {
            guard let window = windows[id] else { continue }
            top -= window.frame.height
            window.setFrameOrigin(NSPoint(x: available.maxX - window.frame.width - BridgeTheme.bannerInset,
                                          y: max(available.minY, top)))
            top -= 12
        }
    }

    private var screenLocked: Bool {
        guard let session = CGSessionCopyCurrentDictionary() as? [String: Any] else { return true }
        return session["CGSSessionScreenIsLocked"] as? Bool ?? false
    }
}
