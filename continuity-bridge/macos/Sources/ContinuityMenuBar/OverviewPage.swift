import ContinuityCore
import SwiftUI

struct OverviewPage: View {
    @ObservedObject var model: BridgeHostModel
    @ObservedObject var navigation: WorkspaceNavigation

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 16) {
                    BridgeIdentity()
                    VStack(alignment: .leading, spacing: 6) {
                        Text("기기 사이를 가볍게").font(.largeTitle.weight(.semibold))
                        Text("복사한 것과 필요한 알림을 Mac으로 이어줘요.")
                            .foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 8)
                InsetSurface {
                    VStack(alignment: .leading, spacing: 20) {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: symbol).font(.title2).foregroundStyle(color)
                                .frame(width: 32, height: 32).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 6) {
                                Text("릴레이 연결").font(.caption).foregroundStyle(.secondary)
                                Text(model.status.koreanText).font(.title2.weight(.semibold))
                                Text(model.detailText ?? statusDescription)
                                    .font(.callout).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        HStack(spacing: 12) {
                            Button(actionTitle) {
                                if needsSettings { navigation.page = .connection }
                                else { Task { model.isRunning ? await model.stop() : await model.start() } }
                            }.buttonStyle(.borderedProminent).controlSize(.large)
                            if model.status == .retrying || model.status == .disconnected {
                                Button("지금 다시 시도") { Task { await model.restart() } }
                                    .controlSize(.large)
                            }
                            Spacer()
                            Button("연결 설정") { navigation.page = .connection }
                                .buttonStyle(.link)
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text("함께 쓰는 기능").font(.headline)
                    InsetSurface {
                        HStack(alignment: .top, spacing: 16) {
                            Image(systemName: "doc.on.clipboard").font(.title2)
                                .frame(width: 32).foregroundStyle(.secondary).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 8) {
                                Text("텍스트와 사진 복사").font(.headline)
                                Text("한 기기에서 복사하고, 다른 기기에 붙여넣으세요.")
                                    .font(.callout).foregroundStyle(.secondary)
                                Text("PNG·JPEG 원본 · 최대 8 MiB")
                                    .font(.caption).foregroundStyle(.secondary)
                                if let issue = model.clipboardIssue {
                                    Label(issue, systemImage: "exclamationmark.circle")
                                        .font(.callout).foregroundStyle(.orange)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                    InsetSurface {
                        HStack(alignment: .top, spacing: 16) {
                            Image(systemName: "bell.badge").font(.title2)
                                .frame(width: 32).foregroundStyle(.secondary).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 8) {
                                Text("휴대폰 알림").font(.headline)
                                Text("앱 아이콘으로 구분하고, 진행률은 한 알림에서 확인해요.")
                                    .font(.callout).foregroundStyle(.secondary)
                                HStack(spacing: 16) {
                                    Button("알림 보기") { navigation.page = .notifications }
                                    Button("받을 앱 고르기") { navigation.page = .applications }
                                }.buttonStyle(.link)
                            }
                        }
                    }
                }
                Text("사진 공유를 사용하려면 Mac·Android 앱과 릴레이를 함께 업데이트해 주세요.")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }.padding(BridgeTheme.contentInset)
        }
    }

    private var needsSettings: Bool { model.needsConfiguration || model.hasMissingCredentials }
    private var actionTitle: String {
        if needsSettings { return "연결 설정하기" }
        if model.isRunning { return "중지" }
        return model.hasUnsavedConfiguration ? "저장하고 시작" : "시작"
    }

    private var statusDescription: String {
        switch model.status {
        case .connected: "서버에 연결했어요. 휴대폰에서도 브리지를 시작해 주세요."
        case .connecting: "서버의 연결과 인증을 확인하고 있어요."
        case .retrying: "서버에 다시 연결하는 중이에요."
        case .authenticationFailed: "연결 설정에서 이 기기의 인증 정보를 확인해 주세요."
        case .tlsFailed: "서버 주소와 인증서 설정을 확인해 주세요."
        case .permissionRequired: "계속하려면 필요한 권한을 확인해 주세요."
        case .disconnected: "서버와 연결이 끊겼어요. 다시 시도할 수 있어요."
        case .stopped: "시작하면 텍스트·사진과 휴대폰 알림을 이어줘요."
        }
    }

    private var symbol: String {
        switch model.status {
        case .connected: "checkmark.circle.fill"
        case .connecting, .retrying: "arrow.triangle.2.circlepath"
        case .authenticationFailed, .tlsFailed, .permissionRequired: "exclamationmark.circle.fill"
        case .disconnected, .stopped: "pause.circle"
        }
    }

    private var color: Color {
        switch model.status {
        case .connected: .green
        case .authenticationFailed, .tlsFailed, .permissionRequired: .orange
        case .connecting, .retrying: .accentColor
        case .disconnected, .stopped: .secondary
        }
    }
}
