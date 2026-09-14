import AppKit
import ContinuityCore
import Foundation
import UserNotifications

@MainActor
final class BridgeHostModel: ObservableObject {
    static let shared = BridgeHostModel()

    static var hasSavedLaunchConfiguration: Bool {
        let defaults = UserDefaults.standard
        return LaunchIntent.hasSavedConfiguration(endpoint: defaults.string(forKey: "relayEndpoint"),
                                                   pin: defaults.string(forKey: "leafPin"))
    }

    @Published var status: ConnectionStatus = .stopped
    @Published var endpoint: String
    @Published var pin: String
    @Published var token = ""
    @Published var detailText: String?
    @Published var notificationPermissionText = "확인 중"
    @Published var openAtLogin = false
    @Published var loginItemText = LoginItemState.disabled.koreanText
    @Published private(set) var isRunning = false

    private let defaults = UserDefaults.standard
    private let keychain = KeychainTokenStore()
    private let loginController = LoginItemController()
    private let notificationCenter = SystemNotificationCenterClient()
    private var connection: ConnectionActor?
    private var pasteboard: PasteboardSynchronizer?
    private var statusTask: Task<Void, Never>?

    init() {
        endpoint = defaults.string(forKey: "relayEndpoint") ?? "https://localhost:8443"
        pin = defaults.string(forKey: "leafPin") ?? Self.bundledPin()
    }

    func saveConfiguration() {
        defaults.set(endpoint, forKey: "relayEndpoint")
        defaults.set(pin.lowercased(), forKey: "leafPin")
        if !token.isEmpty {
            do { try keychain.save(token); detailText = "보안 설정을 저장했습니다" }
            catch { detailText = "인증 토큰을 저장하지 못했습니다" }
            token = ""
        }
    }

    func start() async {
        guard !isRunning else { return }
        do {
            let configuration = try makeConfiguration()
            _ = try keychain.read()
            let state = try DurableStateStore(url: DurableStateStore.applicationStateURL())
            let board = PasteboardSynchronizer(pasteboard: .general, state: state)
            let applier = RemoteEventApplier(
                pasteboard: board,
                notifications: AndroidNotificationRenderer(center: notificationCenter)
            )
            let dependencies = ConnectionDependencies(
                state: state,
                tokenProvider: { try KeychainTokenStore().read() },
                apply: { event in try await applier.apply(event) }
            )
            let actor = ConnectionActor(configuration: configuration, dependencies: dependencies)
            pasteboard = board
            connection = actor
            isRunning = true
            status = .connecting
            detailText = nil
            board.start(onEvent: { _ in await actor.outboxChanged() }, onError: { [weak self] in
                await MainActor.run { self?.detailText = "클립보드를 읽지 못했습니다" }
            })
            let run = await actor.start()
            statusTask = Task { [weak self] in
                while !Task.isCancelled {
                    guard let self, let connection = self.connection else { break }
                    self.status = await connection.status
                    if run.isCancelled { break }
                    try? await Task.sleep(for: .milliseconds(250))
                }
            }
        } catch is KeychainError {
            status = .authenticationFailed
            detailText = "설정에서 인증 토큰을 저장해 주세요"
        } catch {
            status = .tlsFailed
            detailText = "릴레이 주소, 로컬 CA, 인증서 핀을 확인해 주세요"
        }
    }

    func stop() async {
        statusTask?.cancel()
        statusTask = nil
        pasteboard?.stop()
        await connection?.stop()
        connection = nil
        pasteboard = nil
        isRunning = false
        status = .stopped
    }

    func restart() async { await stop(); await start() }

    func refreshSystemStates() async {
        notificationPermissionText = Self.permissionText(await notificationCenter.authorizationStatus())
        let login = loginController.refresh()
        openAtLogin = login == .enabled
        loginItemText = login.koreanText
    }

    func requestNotificationPermission() async {
        do { _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) }
        catch { detailText = "알림 권한 요청을 완료하지 못했습니다" }
        await refreshSystemStates()
    }

    func openNotificationSettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") else { return }
        NSWorkspace.shared.open(url)
    }

    func setOpenAtLogin(_ enabled: Bool) {
        let state = loginController.setEnabled(enabled)
        openAtLogin = state == .enabled
        loginItemText = state.koreanText
    }

    private func makeConfiguration() throws -> ConnectionConfiguration {
        guard let url = URL(string: endpoint), url.scheme == "https", let host = url.host else {
            throw URLError(.badURL)
        }
        let policy: TLSPolicy
        if pin.isEmpty {
            policy = .systemTrust
        } else {
            guard let caDER = Self.bundledCA() else { throw TLSValidationError.invalidCertificate }
            policy = .local(caDER: caDER, leafPinHex: pin.lowercased(), expectedHost: host)
        }
        return ConnectionConfiguration(endpoint: url, tlsPolicy: policy)
    }

    private static func bundledCA() -> Data? {
        guard let url = Bundle.main.url(forResource: "ca", withExtension: "pem"),
              let pem = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let body = pem.components(separatedBy: .newlines).filter { !$0.hasPrefix("---") }.joined()
        return Data(base64Encoded: body)
    }

    private static func bundledPin() -> String {
        guard let url = Bundle.main.url(forResource: "server-cert", withExtension: "sha256"),
              let value = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return value.split(whereSeparator: \.isWhitespace).first.map(String.init)?.lowercased() ?? ""
    }

    private static func permissionText(_ permission: NotificationAuthorization) -> String {
        switch permission {
        case .authorized: "허용됨"
        case .provisional: "임시 허용됨"
        case .ephemeral: "일시 허용됨"
        case .denied: "거부됨"
        case .notDetermined: "아직 요청하지 않음"
        }
    }
}
