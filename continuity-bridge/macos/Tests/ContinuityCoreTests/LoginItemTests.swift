import XCTest
@testable import ContinuityCore

@MainActor
final class LoginItemTests: XCTestCase {
    func testLoginItem_whenReadOnlyStatusRequested_doesNotMutateRegistration() async {
        let client = RecordingLoginItemClient(status: .requiresApproval)
        let controller = LoginItemController(client: client)

        let state = controller.refresh()

        XCTAssertEqual(state, .requiresApproval)
        XCTAssertEqual(client.mutations, 0)
    }

    func testLoginItem_whenRegistrationFails_exposesErrorWithoutFalseEnabledState() async {
        let client = RecordingLoginItemClient(status: .disabled, registrationFails: true)
        let controller = LoginItemController(client: client)

        let state = controller.setEnabled(true)

        XCTAssertEqual(state, .error)
        XCTAssertEqual(client.mutations, 1)
    }
}

@MainActor
private final class RecordingLoginItemClient: LoginItemClient {
    let status: LoginItemState
    let registrationFails: Bool
    private(set) var mutations = 0

    init(status: LoginItemState, registrationFails: Bool = false) {
        self.status = status
        self.registrationFails = registrationFails
    }

    func currentState() -> LoginItemState { status }
    func register() throws {
        mutations += 1
        if registrationFails { throw CocoaError(.featureUnsupported) }
    }
    func unregister() throws { mutations += 1 }
}
