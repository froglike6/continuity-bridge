import AppKit
import ContinuityCore
import SwiftUI
import UserNotifications

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private let navigation = WorkspaceNavigation()
    private var permitsTermination = false
    private var didRequestLaunchStart = false
    private var settingsWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.accessory)
        UNUserNotificationCenter.current().delegate = self
        BridgeHostModel.shared.notificationBanners.openNotifications = { [weak self] in
            self?.showWorkspace(.notifications)
        }
        switch LaunchIntent.resolve(arguments: CommandLine.arguments,
                                   hasSavedConfiguration: BridgeHostModel.hasSavedLaunchConfiguration) {
        case .bootSmoke:
            print("BOOT_SMOKE_EXECUTABLE_STARTED=1")
            print("BOOT_SMOKE_MENU_HOST_INITIALIZED=1")
            print("BOOT_SMOKE_STATUS=service_stopped")
            print("BOOT_SMOKE_LOGIN_ITEM_STATE=\(SystemLoginItemClient().currentState().rawValue)")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { self.terminateFromMenu() }
        case .start:
            guard !didRequestLaunchStart else { return }
            didRequestLaunchStart = true
            Task { await BridgeHostModel.shared.start() }
        case .stopped:
            showSettings()
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                           withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showSettings()
        return true
    }

    func showSettings() {
        showWorkspace(navigation.page)
    }

    func showWorkspace(_ page: WorkspacePage) {
        navigation.page = page
        if settingsWindow == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 940, height: 700),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable],
                                  backing: .buffered, defer: false)
            window.title = "연속성 브리지"
            let hosting = NSHostingView(rootView: WorkspaceView(
                model: BridgeHostModel.shared, navigation: navigation))
            hosting.sizingOptions = []
            window.contentView = hosting
            window.minSize = NSSize(width: 780, height: 600)
            window.isReleasedWhenClosed = false
            window.center()
            settingsWindow = window
        }
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow?.makeKeyAndOrderFront(nil)
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        permitsTermination ? .terminateNow : .terminateCancel
    }

    func terminateFromMenu() {
        permitsTermination = true
        NSApp.terminate(nil)
    }
}
@main
struct ContinuityMenuBarApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = BridgeHostModel.shared

    var body: some Scene {
        MenuBarExtra("Continuity Bridge", systemImage: "arrow.left.arrow.right") {
            Text("릴레이: \(model.status.koreanText)")
                .accessibilityLabel("연결 상태: \(model.status.koreanText)")
            if let detail = model.detailText { Text(detail).foregroundStyle(.secondary) }
            Divider()
            if let inbox = model.notificationInbox {
                NotificationMenuItems(store: inbox, model: model,
                                      open: { appDelegate.showWorkspace(.notifications) })
                Divider()
            }
            Button(model.isRunning ? "중지" : model.hasUnsavedConfiguration ? "저장하고 시작" : "시작") {
                Task { model.isRunning ? await model.stop() : await model.start() }
            }
            Button("브리지 열기") {
                appDelegate.showWorkspace(.overview)
            }
            Button("연결 설정") {
                appDelegate.showWorkspace(.connection)
            }
            Divider()
            Button("종료") {
                Task {
                    await model.stop()
                    await MainActor.run { appDelegate.terminateFromMenu() }
                }
            }
        }
    }
}
