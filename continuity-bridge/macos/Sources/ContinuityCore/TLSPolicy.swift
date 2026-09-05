import CryptoKit
import Foundation
import Security

public enum TLSValidationError: Error, Equatable { case invalidCertificate, invalidPin, trustFailed }

public enum TLSPolicy: Sendable {
    case local(caDER: Data, leafPinHex: String, expectedHost: String)
    case systemTrust

    public func evaluate(leafDER: Data, host: String, verifyDate: Date? = nil) throws {
        if case .local(_, _, let expectedHost) = self, host != expectedHost { throw TLSValidationError.trustFailed }
        guard let leaf = SecCertificateCreateWithData(nil, leafDER as CFData) else {
            throw TLSValidationError.invalidCertificate
        }
        let policy = SecPolicyCreateSSL(true, host as CFString)
        var trustValue: SecTrust?
        guard SecTrustCreateWithCertificates(leaf, policy, &trustValue) == errSecSuccess, let trust = trustValue else {
            throw TLSValidationError.trustFailed
        }
        try configure(trust: trust)
        if let verifyDate { SecTrustSetVerifyDate(trust, verifyDate as CFDate) }
        var evaluationError: CFError?
        guard SecTrustEvaluateWithError(trust, &evaluationError) else { throw TLSValidationError.trustFailed }
        if case .local(_, let expectedPin, _) = self {
            let actual = SHA256.hash(data: leafDER).map { String(format: "%02x", $0) }.joined()
            guard expectedPin.count == 64, expectedPin == expectedPin.lowercased(), actual == expectedPin else {
                throw TLSValidationError.invalidPin
            }
        }
    }

    public func evaluate(trust: SecTrust, requestedHost: String) throws {
        if case .local(_, _, let expectedHost) = self, requestedHost != expectedHost { throw TLSValidationError.trustFailed }
        SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, requestedHost as CFString))
        try configure(trust: trust)
        var evaluationError: CFError?
        guard SecTrustEvaluateWithError(trust, &evaluationError) else { throw TLSValidationError.trustFailed }
        if case .local(_, let expectedPin, _) = self {
            guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                  let certificate = chain.first else { throw TLSValidationError.invalidCertificate }
            let data = SecCertificateCopyData(certificate) as Data
            let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard actual == expectedPin else { throw TLSValidationError.invalidPin }
        }
    }

    private func configure(trust: SecTrust) throws {
        guard case .local(let caDER, _, _) = self else { return }
        guard let anchor = SecCertificateCreateWithData(nil, caDER as CFData),
              SecTrustSetAnchorCertificates(trust, [anchor] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess else {
            throw TLSValidationError.invalidCertificate
        }
    }
}

final class PinnedSessionDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let policy: TLSPolicy
    init(policy: TLSPolicy) { self.policy = policy }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        do {
            try policy.evaluate(trust: trust, requestedHost: challenge.protectionSpace.host)
            completionHandler(.useCredential, URLCredential(trust: trust))
        } catch {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
