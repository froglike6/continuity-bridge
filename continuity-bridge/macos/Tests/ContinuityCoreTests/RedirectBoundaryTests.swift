import Foundation
import Network
import XCTest
@testable import ContinuityCore

@MainActor
final class RedirectBoundaryTests: XCTestCase {
    func testDelegate_refusesSameOriginAndCrossOriginRedirects() throws {
        let original = try XCTUnwrap(URL(string: "https://relay.example/v1/events"))
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: original)
        let delegate = PinnedSessionDelegate(policy: .systemTrust)
        for status in [301, 302, 303, 307, 308] {
            for destination in ["https://relay.example/redirected", "https://login.example/redirected"] {
                let response = try XCTUnwrap(HTTPURLResponse(url: original, statusCode: status,
                    httpVersion: "HTTP/1.1", headerFields: ["Location": destination]))
                let request = URLRequest(url: try XCTUnwrap(URL(string: destination)))
                let called = LockedFlag()
                delegate.urlSession(session, task: task, willPerformHTTPRedirection: response, newRequest: request) { next in
                    called.set()
                    XCTAssertNil(next)
                }
                XCTAssertTrue(called.value)
            }
        }
    }

    func testURLSession_actualRedirectResponsesNeverReachDestinationWithGETOrPOSTCredentials() async throws {
        for status in [301, 302, 303, 307, 308] {
            for method in ["GET", "POST"] {
                let server = try RedirectHTTPServer(status: status)
                let ready = expectation(description: "loopback redirect listener ready")
                server.start(ready: ready)
                await fulfillment(of: [ready], timeout: 2)
                defer { server.stop() }
                let port = try XCTUnwrap(server.port)
                let url = try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)/v1/events"))
                let session = URLSession(configuration: .ephemeral)
                defer { session.invalidateAndCancel() }
                var request = URLRequest(url: url, timeoutInterval: 2)
                request.httpMethod = method
                request.setValue("Bearer redirect-test-only", forHTTPHeaderField: "Authorization")
                let credentials = try CloudflareAccessCredentials(clientID: "redirect-test.access", clientSecret: "cfast_test_only")
                credentials.apply(to: &request)
                if method == "POST" { request.httpBody = Data("test-body".utf8) }
                let (_, response) = try await session.data(for: request, delegate: PinnedSessionDelegate(policy: .systemTrust))
                XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, status)
                XCTAssertEqual(server.requests.count, 1)
                let received = try XCTUnwrap(server.requests.first).lowercased()
                XCTAssertTrue(received.hasPrefix("\(method.lowercased()) /v1/events "))
                XCTAssertTrue(received.contains("authorization: bearer redirect-test-only"))
                XCTAssertTrue(received.contains("cf-access-client-id: redirect-test.access"))
                XCTAssertTrue(received.contains("cf-access-client-secret: cfast_test_only"))
                XCTAssertFalse(received.contains("/redirected"))
            }
        }
    }
}

private final class RedirectHTTPServer: @unchecked Sendable {
    private let listener: NWListener
    private let status: Int
    private let queue = DispatchQueue(label: "continuitybridge.tests.redirect")
    private let lock = NSLock()
    private var captured: [String] = []

    init(status: Int) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        self.status = status
    }

    var port: UInt16? { listener.port?.rawValue }
    var requests: [String] { lock.withLock { captured } }

    func start(ready: XCTestExpectation) {
        listener.stateUpdateHandler = { state in
            if case .ready = state { ready.fulfill() }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            connection.start(queue: self.queue)
            self.read(connection, buffered: Data())
        }
        listener.start(queue: queue)
    }

    func stop() { listener.cancel() }

    private func read(_ connection: NWConnection, buffered: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, complete, error in
            guard let self, error == nil else { connection.cancel(); return }
            let received = buffered + (data ?? Data())
            guard let request = String(data: received, encoding: .utf8), request.contains("\r\n\r\n") else {
                if complete { connection.cancel() }
                else { self.read(connection, buffered: received) }
                return
            }
            self.lock.withLock { self.captured.append(request) }
            guard let port = self.port else { connection.cancel(); return }
            let response = request.contains(" /redirected ")
                ? "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                : "HTTP/1.1 \(self.status) Redirect\r\nLocation: http://localhost:\(port)/redirected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
        }
    }
}
