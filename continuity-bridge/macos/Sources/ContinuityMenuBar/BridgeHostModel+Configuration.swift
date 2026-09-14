import ContinuityCore
import Foundation

extension BridgeHostModel {
    @discardableResult
    func saveConfiguration() -> Bool {
        guard !isRunning else { return false }
        clearConfigurationFeedback()
        let address = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        let fingerprint = pin.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let url = URLComponents(string: address)
        if url?.scheme != "https" || url?.host?.isEmpty != false || url?.user != nil
            || url?.password != nil || url?.query != nil || url?.fragment != nil {
            endpointError = "https://로 시작하는 서버 주소를 입력해 주세요."
        }
        if !fingerprint.isEmpty && (fingerprint.count != 64 || !fingerprint.allSatisfy({ $0.isASCII && $0.isHexDigit })) {
            pinError = "SHA-256 핀은 64자리 영문·숫자(0–9, a–f)입니다."
        }
        guard !configurationHasError else { return false }
        let accessCredentials: CloudflareAccessCredentials?
        do {
            accessCredentials = cloudflareAccessEnabled
                ? try CloudflareAccessCredentials.resolve(clientID: accessClientID, clientSecret: accessClientSecret,
                                                          stored: { try accessKeychain.read() })
                : nil
        } catch CloudflareAccessCredentialError.invalidClientID {
            accessClientIDError = "이 Mac의 Client ID를 공백·줄바꿈 없이 입력해 주세요."
            return false
        } catch CloudflareAccessCredentialError.invalidClientSecret {
            accessClientSecretError = "Client Secret을 공백·줄바꿈 없이 입력해 주세요."
            return false
        } catch CloudflareAccessCredentialError.clientSecretRequired {
            accessClientSecretError = "Client ID가 바뀌었습니다. 해당 ID의 새 Client Secret을 입력해 주세요."
            return false
        } catch KeychainError.missingItem {
            hasStoredAccessCredentials = false
            accessClientSecretError = "이 Mac의 Client Secret을 입력해 주세요."
            return false
        } catch KeychainError.corruptItem {
            hasStoredAccessCredentials = false
            accessClientSecretError = "저장된 Access 자격증명을 읽을 수 없습니다. Client Secret을 다시 입력해 주세요."
            return false
        } catch {
            accessClientSecretError = "Access 키체인에 접근하지 못했습니다. 잠금을 해제한 뒤 다시 저장해 주세요."
            return false
        }
        do {
            if !token.isEmpty { try keychain.save(token) }
            else { _ = try keychain.read() }
        } catch KeychainError.missingItem {
            hasStoredToken = false
            tokenError = "이 Mac의 인증 토큰을 입력해 주세요."
            return false
        } catch {
            tokenError = "키체인에 접근하지 못했습니다. 잠금을 해제한 뒤 다시 저장해 주세요."
            return false
        }
        if let accessCredentials {
            do { try accessKeychain.save(accessCredentials) }
            catch {
                accessClientSecretError = "Access 자격증명을 저장하지 못했습니다. 키체인 잠금을 확인해 주세요."
                return false
            }
            savedAccessClientID = accessCredentials.clientID
            hasStoredAccessCredentials = true
        }
        defaults.set(address, forKey: "relayEndpoint")
        defaults.set(fingerprint, forKey: "leafPin")
        defaults.set(cloudflareAccessEnabled, forKey: "cloudflareAccessEnabled")
        endpoint = address
        pin = fingerprint
        savedEndpoint = address
        savedPin = fingerprint
        savedAccessEnabled = cloudflareAccessEnabled
        token = ""
        accessClientSecret = ""
        hasStoredToken = true
        configurationMessage = "설정을 저장했습니다."
        return true
    }

    func clearConfigurationFeedback() {
        endpointError = nil
        pinError = nil
        tokenError = nil
        accessClientIDError = nil
        accessClientSecretError = nil
        configurationMessage = nil
    }

    func makeConfiguration() throws -> ConnectionConfiguration {
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
        return ConnectionConfiguration(endpoint: url, tlsPolicy: policy,
                                       cloudflareAccessEnabled: cloudflareAccessEnabled)
    }

    private static func bundledCA() -> Data? {
        guard let url = Bundle.main.url(forResource: "ca", withExtension: "pem"),
              let pem = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let body = pem.components(separatedBy: .newlines).filter { !$0.hasPrefix("---") }.joined()
        return Data(base64Encoded: body)
    }

    static func bundledPin() -> String {
        guard let url = Bundle.main.url(forResource: "server-cert", withExtension: "sha256"),
              let value = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return value.split(whereSeparator: \.isWhitespace).first.map(String.init)?.lowercased() ?? ""
    }

}
