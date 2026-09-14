import ContinuityCore
import SwiftUI

struct NotificationContentView: View {
    let item: NotificationInboxItem
    var compact = false
    var expanded = false

    private var bodyText: String {
        item.isRedacted ? "휴대폰에서 알림 내용을 숨겼어요." : item.body
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(item.appLabel).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(Date(timeIntervalSince1970: Double(item.createdAtMs) / 1_000), style: .relative)
                    .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
            }
            Text(item.isRedacted ? item.appLabel : item.title)
                .font(.headline).lineLimit(compact ? 2 : expanded ? nil : 3)
                .fixedSize(horizontal: false, vertical: true)
            if !bodyText.isEmpty {
                Text(bodyText).font(.callout).foregroundStyle(item.isRedacted ? .secondary : .primary)
                    .lineLimit(compact ? 3 : expanded ? nil : 4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            NotificationProgressView(item: item)
        }
    }
}

struct NotificationProgressView: View {
    let item: NotificationInboxItem

    private var progressColor: Color {
        switch item.progressState {
        case .completed: .green
        case .failed: .red
        default: .accentColor
        }
    }

    var body: some View {
        if let progress = item.progress {
            VStack(alignment: .leading, spacing: 6) {
                if progress.indeterminate {
                    ProgressView().controlSize(.small)
                        .accessibilityLabel("진행 중")
                } else if progress.max > 0 {
                    ProgressView(value: Double(progress.value), total: Double(progress.max))
                        .tint(progressColor)
                        .accessibilityLabel("진행률")
                    HStack {
                        Text(statusText).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Text("\(Int(Double(progress.value) / Double(progress.max) * 100))%")
                            .font(.caption.monospacedDigit().weight(.medium))
                            .foregroundStyle(progressColor)
                    }
                }
            }.padding(.top, 4)
        } else if item.progressState == .failed || item.progressState == .unknown {
            Label(statusText, systemImage: item.progressState == .failed
                  ? "exclamationmark.circle" : "pause.circle")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private var statusText: String {
        switch item.progressState {
        case .active: "진행 중"
        case .completed: "완료"
        case .failed: "실패"
        case .unknown: "진행 상태 확인 대기"
        case .none: ""
        }
    }
}

struct NotificationRow: View {
    let item: NotificationInboxItem
    let dismiss: () -> Void
    @State private var expanded = false

    var body: some View {
        InsetSurface {
            HStack(alignment: .top, spacing: 12) {
                SourceAppIcon(encoded: item.iconPngBase64, name: item.appLabel)
                NotificationContentView(item: item, expanded: expanded)
                VStack(spacing: 6) {
                    Button(action: dismiss) {
                        Image(systemName: "xmark").frame(width: 20, height: 20)
                    }
                    .help("이 알림 지우기").accessibilityLabel("이 알림 지우기")
                    if !item.isRedacted && (!item.title.isEmpty || !item.body.isEmpty) {
                        Button { expanded.toggle() } label: {
                            Image(systemName: expanded ? "chevron.up" : "chevron.down")
                                .frame(width: 20, height: 20)
                        }
                        .help(expanded ? "내용 접기" : "전체 내용 보기")
                        .accessibilityLabel(expanded ? "내용 접기" : "전체 내용 보기")
                    }
                }.buttonStyle(.borderless).foregroundStyle(.secondary)
            }
        }
    }
}

struct NotificationBannerView: View {
    let item: NotificationInboxItem
    let close: () -> Void
    let open: () -> Void
    let hover: (Bool) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SourceAppIcon(encoded: item.iconPngBase64, name: item.appLabel, size: 44)
            Button(action: open) {
                NotificationContentView(item: item, compact: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(item.appLabel) 알림 열기")
            Button(action: close) {
                Image(systemName: "xmark").font(.caption.weight(.semibold))
                    .frame(width: 20, height: 20)
            }.buttonStyle(.borderless).foregroundStyle(.secondary)
                .accessibilityLabel("배너 닫기")
        }
        .padding(BridgeTheme.bannerInset)
        .frame(width: BridgeTheme.bannerWidth)
        .background(.regularMaterial, in: RoundedRectangle(
            cornerRadius: BridgeTheme.surfaceRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: BridgeTheme.surfaceRadius, style: .continuous)
                .stroke(Color(nsColor: .separatorColor).opacity(0.35), lineWidth: 0.5)
        }
        .onHover(perform: hover)
    }
}
