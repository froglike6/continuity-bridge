public struct AuthenticatedActor: Equatable, Sendable {
    public let role: DeviceRole
    public let deviceId: String
    public init(role: DeviceRole, deviceId: String) { self.role = role; self.deviceId = deviceId }
}

public enum RolePolicy {
    public static func authorize(_ event: BridgeEvent, actor: AuthenticatedActor) throws {
        guard event.originRole == actor.role, event.originDeviceId == actor.deviceId else {
            throw ProtocolError.identityMismatch
        }
        switch (actor.role, event.kind) {
        case (.android, .clipboard), (.android, .notification), (.macOS, .clipboard): return
        case (.macOS, .notification): throw ProtocolError.directionForbidden
        }
    }
}
