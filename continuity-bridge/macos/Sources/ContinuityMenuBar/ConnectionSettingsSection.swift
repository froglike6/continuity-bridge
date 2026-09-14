import SwiftUI

struct ConnectionSettingsSection: View {
    @ObservedObject var model: BridgeHostModel
    @Binding var showsConfiguration: Bool
    let onEdit: @MainActor () async -> Void

    var body: some View {
        Section("연결 설정") {
            DisclosureGroup(isExpanded: $showsConfiguration) {
                VStack(alignment: .leading, spacing: 16) {
                    if model.isRunning {
                        Text("연결 설정은 중지한 뒤 변경할 수 있습니다.")
                            .foregroundStyle(.secondary)
                        Button("중지하고 설정 변경") { Task { await onEdit() } }
                    } else {
                        Text("두 기기에서 같은 릴레이 주소를 사용하고, 토큰은 기기별로 입력해 주세요.")
                            .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                    Group {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("릴레이 HTTPS 주소").font(.headline)
                        TextField("https://서버주소:포트", text: $model.endpoint)
                            .labelsHidden()
                            .textFieldStyle(.roundedBorder)
                            .accessibilityLabel("릴레이 HTTPS 주소")
                        fieldError(model.endpointError)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("서버 인증서 SHA-256 핀").font(.headline)
                        TextField("64자리 인증서 핀", text: $model.pin, axis: .vertical)
                            .labelsHidden()
                            .lineLimit(2...3).font(.system(.callout, design: .monospaced))
                            .textFieldStyle(.roundedBorder)
                            .accessibilityLabel("서버 인증서 SHA-256 핀")
                        Text("로컬 인증서는 핀을 입력하세요. 비우면 시스템 신뢰 저장소를 사용합니다.")
                            .font(.caption).foregroundStyle(.secondary)
                        fieldError(model.pinError)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("이 Mac의 인증 토큰").font(.headline)
                        SecureField(model.hasStoredToken ? "변경할 때만 입력" : "인증 토큰 입력", text: $model.token)
                            .labelsHidden()
                            .textFieldStyle(.roundedBorder)
                            .accessibilityLabel("이 Mac의 인증 토큰 보안 입력")
                        Text(model.hasStoredToken ? "키체인에 저장됨 · 비워 두면 기존 토큰을 유지합니다." : "아직 저장된 토큰이 없습니다.")
                            .font(.caption).foregroundStyle(.secondary)
                        fieldError(model.tokenError)
                    }
                    accessFields
                    HStack(spacing: 8) {
                        Button("설정 저장") { model.saveConfiguration() }
                        if let message = model.configurationMessage {
                            Label(message, systemImage: "checkmark.circle")
                                .foregroundStyle(.secondary)
                        } else if model.hasUnsavedConfiguration {
                            Text("저장하지 않은 변경 사항").foregroundStyle(.secondary)
                        }
                    }
                    }.disabled(model.isRunning)
                }.padding(.top, 16)
            } label: {
                HStack {
                    Text("서버 연결 설정").font(.headline)
                    Spacer()
                    Text(model.isRunning ? "사용 중" : model.hasMissingCredentials ? "입력 필요" : model.hasUnsavedConfiguration ? "변경됨" : "저장됨")
                        .font(.callout).foregroundStyle(.secondary)
                }.contentShape(Rectangle())
                    .onTapGesture { showsConfiguration.toggle() }
            }.padding(8)
        }
    }

    private var accessFields: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle("Cloudflare Access 사용", isOn: $model.cloudflareAccessEnabled)
            if model.cloudflareAccessEnabled {
                Text("이 Mac에 발급된 Access 서비스 자격증명을 입력해 주세요. 릴레이 토큰도 함께 사용합니다.")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Client ID").font(.headline)
                    TextField("이 Mac의 Client ID", text: $model.accessClientID)
                        .labelsHidden().textFieldStyle(.roundedBorder)
                        .accessibilityLabel("Cloudflare Access Client ID")
                    fieldError(model.accessClientIDError)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Client Secret").font(.headline)
                    SecureField(model.hasReusableAccessCredentials ? "변경할 때만 입력" : "Client Secret 입력",
                                text: $model.accessClientSecret)
                        .labelsHidden().textFieldStyle(.roundedBorder)
                        .accessibilityLabel("Cloudflare Access Client Secret 보안 입력")
                    Text(accessCredentialHint)
                        .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    fieldError(model.accessClientSecretError)
                }
            }
        }
    }

    private var accessCredentialHint: String {
        if model.hasReusableAccessCredentials {
            return "키체인에 저장됨 · 같은 Client ID에서 비워 두면 기존 Secret을 유지합니다."
        }
        if model.hasStoredAccessCredentials {
            return "Client ID가 바뀌면 새 Client Secret을 입력해야 합니다."
        }
        return "아직 저장된 Access 자격증명이 없습니다."
    }

    @ViewBuilder private func fieldError(_ error: String?) -> some View {
        if let error {
            Label(error, systemImage: "exclamationmark.circle")
                .font(.callout).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
        }
    }

}
