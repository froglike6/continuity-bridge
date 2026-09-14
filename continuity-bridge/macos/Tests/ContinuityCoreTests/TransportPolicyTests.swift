import XCTest
@testable import ContinuityCore

final class TransportPolicyTests: XCTestCase {
    func testAccessFailuresAndRedirects_areTerminalAuthentication() {
        for status in [301, 302, 303, 307, 308, 401, 403] {
            XCTAssertEqual(RetryPolicy.classify(statusCode: status, error: nil), .terminalAuthentication)
        }
        XCTAssertEqual(RetryPolicy.classify(statusCode: nil, error: TransportError.accessCredentialsUnavailable), .terminalAuthentication)
        XCTAssertEqual(RetryPolicy.classify(statusCode: nil, error: KeychainError.corruptItem), .terminalAuthentication)
        XCTAssertEqual(RetryPolicy.classify(statusCode: 503, error: nil), .retryable)
        XCTAssertEqual(RetryPolicy.classify(statusCode: 400, error: nil), .terminal)
    }

    func testRetryPolicy_when401_isTerminalButTimeoutAnd5xxBackOff() {
        XCTAssertEqual(RetryPolicy.classify(statusCode: 401, error: nil), .terminalAuthentication)
        XCTAssertEqual(RetryPolicy.classify(statusCode: 503, error: nil), .retryable)
        XCTAssertEqual(RetryPolicy.classify(statusCode: nil, error: URLError(.timedOut)), .retryable)
        XCTAssertEqual(RetryPolicy(baseMilliseconds: 100, capMilliseconds: 1_000).delay(attempt: 3, jitterUnit: 0.5), 600)
    }

    func testStatusLabels_whenTerminalStates_areTruthfulKorean() {
        XCTAssertEqual(ConnectionStatus.authenticationFailed.koreanText, "인증 실패")
        XCTAssertEqual(ConnectionStatus.tlsFailed.koreanText, "보안 연결 확인 필요")
        XCTAssertNotEqual(ConnectionStatus.stopped.koreanText, ConnectionStatus.connected.koreanText)
    }
}
