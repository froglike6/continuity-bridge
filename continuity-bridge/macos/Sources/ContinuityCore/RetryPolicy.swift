import Foundation

public enum FailureDisposition: Equatable, Sendable { case success, terminalAuthentication, terminal, retryable }

public struct RetryPolicy: Sendable {
    public let baseMilliseconds: UInt64
    public let capMilliseconds: UInt64

    public init(baseMilliseconds: UInt64 = 500, capMilliseconds: UInt64 = 30_000) {
        self.baseMilliseconds = baseMilliseconds
        self.capMilliseconds = capMilliseconds
    }

    public static func classify(statusCode: Int?, error: Error?) -> FailureDisposition {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost, .cancelled: return .retryable
            default: return .terminal
            }
        }
        guard let statusCode else { return error == nil ? .success : .terminal }
        switch statusCode {
        case 200...299: return .success
        case 401: return .terminalAuthentication
        case 500...599: return .retryable
        default: return .terminal
        }
    }

    public func delay(attempt: Int, jitterUnit: Double) -> UInt64 {
        let exponent = min(max(attempt - 1, 0), 30)
        let multiplication = baseMilliseconds.multipliedReportingOverflow(by: 1 << exponent)
        let raw = multiplication.overflow ? capMilliseconds : min(capMilliseconds, multiplication.partialValue)
        let boundedJitter = min(max(jitterUnit, 0), 1)
        return min(capMilliseconds, UInt64(Double(raw) * (1 + boundedJitter)))
    }
}
