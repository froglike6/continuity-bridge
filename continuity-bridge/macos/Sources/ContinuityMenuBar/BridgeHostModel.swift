import AppKit
import ContinuityCore
import Foundation
import UserNotifications

@MainActor
final class BridgeHostModel: ObservableObject {
    static let shared: BridgeHostModel = {
        guard CommandLine.arguments.contains("--boot-smoke") else { return BridgeHostModel() }
        let identifier = "com.froglike6.continuitybridge.boot-smoke.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: identifier) else {
            preconditionFailure("Unable to create isolated boot-smoke defaults")
        }
        return BridgeHostModel(
            defaults: defaults,
            keychain: KeychainTokenStore(service: identifier),
            accessKeychain: KeychainAccessCredentialStore(service: identifier),
            stateDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(identifier),
            pasteboard: NSPasteboard(name: .init(identifier)))
    }()

    static var hasSavedLaunchConfiguration: Bool {
        let defaults = UserDefaults.standard
        return LaunchIntent.hasSavedConfiguration(endpoint: defaults.string(forKey: "relayEndpoint"),
                                                   pin: defaults.string(forKey: "leafPin"))
    }

    @Published var status: ConnectionStatus = .stopped
    @Published var endpoint: String { didSet { clearConfigurationFeedback() } }
    @Published var pin: String { didSet { clearConfigurationFeedback() } }
    @Published var token = "" { didSet { clearConfigurationFeedback() } }
    @Published var cloudflareAccessEnabled: Bool { didSet { clearConfigurationFeedback() } }
    @Published var accessClientID = "" { didSet { clearConfigurationFeedback() } }
    @Published var accessClientSecret = "" { didSet { clearConfigurationFeedback() } }
    @Published var detailText: String?
    @Published var endpointError: String?
    @Published var pinError: String?
    @Published var tokenError: String?
    @Published var accessClientIDError: String?
    @Published var accessClientSecretError: String?
    @Published var configurationMessage: String?
    @Published var hasStoredToken = false
    @Published var hasStoredAccessCredentials = false
    @Published private(set) var notificationAuthorization: NotificationAuthorization = .notDetermined
    @Published var notificationPermissionText = "확인 중"
    @Published var openAtLogin = false
    @Published var loginItemText = LoginItemState.disabled.koreanText
    @Published private(set) var isRunning = false
    @Published var clipboardIssue: String?
    @Published var notificationError: String?
    @Published private(set) var notificationInbox: NotificationInboxStore?

    let defaults: UserDefaults
    let keychain: KeychainTokenStore
    let accessKeychain: KeychainAccessCredentialStore
    let notificationBanners = NotificationBannerController()
    private let stateDirectory: URL?
    private let systemPasteboard: NSPasteboard
    private let loginController = LoginItemController()
    private let notificationCenter = SystemNotificationCenterClient()
    private var connection: ConnectionActor?
    private var pasteboard: PasteboardSynchronizer?
    private var statusTask: Task<Void, Never>?
    var savedEndpoint: String
    var savedPin: String
    var savedAccessEnabled: Bool
    var savedAccessClientID = ""

    var hasUnsavedConfiguration: Bool {
        endpoint != savedEndpoint || pin != savedPin || !token.isEmpty
            || cloudflareAccessEnabled != savedAccessEnabled
            || (cloudflareAccessEnabled && (accessClientID != savedAccessClientID || !accessClientSecret.isEmpty))
    }
    var configurationHasError: Bool {
        endpointError != nil || pinError != nil || tokenError != nil
            || accessClientIDError != nil || accessClientSecretError != nil
    }
    var hasReusableAccessCredentials: Bool { hasStoredAccessCredentials && accessClientID == savedAccessClientID }
    var hasMissingCredentials: Bool { !hasStoredToken || (cloudflareAccessEnabled && !hasStoredAccessCredentials) }
    var needsConfiguration: Bool { status == .authenticationFailed || status == .tlsFailed }

    init(defaults: UserDefaults = .standard,
         keychain: KeychainTokenStore = KeychainTokenStore(),
         accessKeychain: KeychainAccessCredentialStore = KeychainAccessCredentialStore(),
         stateDirectory: URL? = nil, pasteboard: NSPasteboard = .general) {
        self.defaults = defaults
        self.keychain = keychain
        self.accessKeychain = accessKeychain
        self.stateDirectory = stateDirectory
        systemPasteboard = pasteboard
        endpoint = defaults.string(forKey: "relayEndpoint") ?? "https://localhost:8443"
        pin = defaults.string(forKey: "leafPin") ?? Self.bundledPin()
        savedEndpoint = defaults.string(forKey: "relayEndpoint") ?? ""
        savedPin = defaults.string(forKey: "leafPin") ?? ""
        cloudflareAccessEnabled = defaults.bool(forKey: "cloudflareAccessEnabled")
        savedAccessEnabled = defaults.bool(forKey: "cloudflareAccessEnabled")
        if let credentials = try? accessKeychain.read() {
            accessClientID = credentials.clientID
            savedAccessClientID = credentials.clientID
            hasStoredAccessCredentials = true
        }
        do {
            notificationInbox = try NotificationInboxStore(
                url: stateDirectory?.appendingPathComponent("notification-inbox.json"))
        } catch {
            notificationError = "알림 기록을 불러오지 못했어요. 저장 공간과 파일 접근 권한을 확인해 주세요."
        }
    }

    func start() async {
        guard !isRunning else { return }
        guard saveConfiguration() else { return }
        do {
            let configuration = try makeConfiguration()
            _ = try keychain.read()
            let state = try DurableStateStore(url: stateDirectory?.appendingPathComponent("state.json")
                                              ?? DurableStateStore.applicationStateURL())
            let board = PasteboardSynchronizer(pasteboard: systemPasteboard, state: state)
            let applier = RemoteEventApplier(
                pasteboard: board,
                notifications: AndroidNotificationRenderer(deliver: { [weak self] event in
                    guard let self else { throw NotificationApplyError.addFailed }
                    try await self.receiveNotification(event)
                })
            )
            let dependencies = ConnectionDependencies(
                state: state,
                tokenProvider: { [keychain] in try keychain.read() },
                apply: { event in try await applier.apply(event) },
                accessCredentialsProvider: { [accessKeychain] in try accessKeychain.read() }
            )
            let actor = ConnectionActor(configuration: configuration, dependencies: dependencies)
            pasteboard = board
            connection = actor
            isRunning = true
            status = .connecting
            detailText = nil
            board.start(onEvent: { [weak self] _ in
                await MainActor.run { self?.clipboardIssue = nil }
                await actor.outboxChanged()
            }, onError: { [weak self, weak board] in
                await MainActor.run {
                    self?.clipboardIssue = board?.lastCaptureError?.localizedDescription
                        ?? "클립보드를 읽지 못했어요. 다시 복사해 주세요."
                }
            })
            let run = await actor.start()
            statusTask = Task { [weak self] in
                while !Task.isCancelled {
                    guard let self, let connection = self.connection else { break }
                    let nextStatus = await connection.status
                    if self.status != nextStatus { self.status = nextStatus }
                    if nextStatus == .authenticationFailed {
                        let failure = await connection.failureDescription
                        if self.detailText != failure { self.detailText = failure }
                    }
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
        detailText = nil
    }

    func restart() async { await stop(); await start() }

    func refreshSystemStates() async {
        let permission = await notificationCenter.authorizationStatus()
        if notificationAuthorization != permission { notificationAuthorization = permission }
        let permissionText = Self.permissionText(permission)
        if notificationPermissionText != permissionText { notificationPermissionText = permissionText }
        let storedToken = (try? keychain.read()) != nil
        if hasStoredToken != storedToken { hasStoredToken = storedToken }
        let storedAccess = try? accessKeychain.read()
        hasStoredAccessCredentials = storedAccess != nil
        savedAccessClientID = storedAccess?.clientID ?? ""
        let login = loginController.refresh()
        if openAtLogin != (login == .enabled) { openAtLogin = login == .enabled }
        if loginItemText != login.koreanText { loginItemText = login.koreanText }
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
