import AppKit
import ContinuityCore
import SwiftUI
import UserNotifications

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private var permitsTermination = false
    private var didRequestLaunchStart = false
    private var settingsWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.accessory)
        UNUserNotificationCenter.current().delegate = self
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
        if settingsWindow == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 540, height: 520),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable],
                                  backing: .buffered, defer: false)
            window.title = "Continuity Bridge 설정"
            window.contentView = NSHostingView(rootView: SettingsView(model: BridgeHostModel.shared))
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
            Text(model.status.koreanText)
                .accessibilityLabel("연결 상태: \(model.status.koreanText)")
            if let detail = model.detailText { Text(detail).foregroundStyle(.secondary) }
            Divider()
            Button(model.isRunning ? "중지" : "시작") {
                Task { model.isRunning ? await model.stop() : await model.start() }
            }
            Button("설정 보기") {
                appDelegate.showSettings()
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

private struct SettingsView: View {
    @ObservedObject var model: BridgeHostModel

    var body: some View {
        Form {
            Section("연결 상태") {
                LabeledContent("현재 상태", value: model.status.koreanText)
                if let detail = model.detailText { Text(detail).foregroundStyle(.secondary) }
                HStack(spacing: 8) {
                    Button(model.isRunning ? "중지" : "시작") {
                        Task { model.isRunning ? await model.stop() : await model.start() }
                    }
                    Button("다시 시도") { Task { await model.restart() } }
                        .disabled(!model.isRunning)
                }
            }
            Section("보안 연결") {
                TextField("릴레이 주소", text: $model.endpoint)
                TextField("서버 인증서 SHA-256", text: $model.pin)
                SecureField("인증 토큰", text: $model.token)
                Button("설정 저장") { model.saveConfiguration() }.disabled(model.endpoint.isEmpty)
            }
            Section("알림 권한") {
                LabeledContent("현재 상태", value: model.notificationPermissionText)
                HStack(spacing: 8) {
                    Button("알림 권한 요청") { Task { await model.requestNotificationPermission() } }
                    Button("시스템 설정 열기") { model.openNotificationSettings() }
                }
            }
            Section("로그인 항목") {
                Toggle("로그인 시 열기", isOn: Binding(
                    get: { model.openAtLogin },
                    set: { model.setOpenAtLogin($0) }
                ))
                Text(model.loginItemText).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding(16)
        .frame(minWidth: 480, minHeight: 440)
        .task { await model.refreshSystemStates() }
    }
}
