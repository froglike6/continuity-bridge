import ServiceManagement

public enum LoginItemState: String, Equatable, Sendable {
    case enabled, disabled, requiresApproval, error

    public var koreanText: String {
        switch self {
        case .enabled: "로그인 시 열기 켜짐"
        case .disabled: "로그인 시 열기 꺼짐"
        case .requiresApproval: "시스템 설정에서 승인 필요"
        case .error: "로그인 항목 변경 실패"
        }
    }
}

@MainActor
public protocol LoginItemClient {
    func currentState() -> LoginItemState
    func register() throws
    func unregister() throws
}

@MainActor
public final class SystemLoginItemClient: LoginItemClient {
    public init() {}

    public func currentState() -> LoginItemState {
        switch SMAppService.mainApp.status {
        case .enabled: .enabled
        case .notRegistered: .disabled
        case .requiresApproval: .requiresApproval
        case .notFound: .error
        @unknown default: .error
        }
    }

    public func register() throws { try SMAppService.mainApp.register() }
    public func unregister() throws { try SMAppService.mainApp.unregister() }
}

@MainActor
public final class LoginItemController {
    private let client: LoginItemClient
    public private(set) var state: LoginItemState = .disabled

    public init(client: LoginItemClient = SystemLoginItemClient()) { self.client = client }

    public func refresh() -> LoginItemState {
        state = client.currentState()
        return state
    }

    public func setEnabled(_ enabled: Bool) -> LoginItemState {
        do {
            if enabled { try client.register() } else { try client.unregister() }
            state = client.currentState()
        } catch {
            state = .error
        }
        return state
    }
}
