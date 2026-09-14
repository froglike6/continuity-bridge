package com.froglike6.continuitybridge;

final class HelperStatusText {
    private HelperStatusText() { }

    static String title(EmbeddedHelperManager.State state) {
        switch (state) {
            case UNPAIRED: return "처음 연결 필요";
            case PAIRING: return "코드 확인 중";
            case STARTING: return "도우미 연결 중";
            case READY: return "도우미 연결됨";
            case WIFI_REQUIRED: return "Wi-Fi 연결 필요";
            case DEBUGGING_REQUIRED: return "무선 디버깅 확인 필요";
            case PAIRING_REQUIRED: return "새 페어링 코드 필요";
            case ERROR: return "도우미 연결 오류";
            case STOPPED: return "도우미 중지됨";
            case UNSUPPORTED: return "지원되지 않는 Android 버전";
            default: throw new IllegalStateException("unknown_helper_state");
        }
    }

    static String explanation(EmbeddedHelperManager.State state, boolean paired) {
        switch (state) {
            case UNPAIRED:
                return "이 휴대폰의 무선 디버깅 코드로 도우미를 연결하세요.";
            case PAIRING:
                return "페어링 코드를 확인합니다. Android 설정의 코드 창을 열어두세요.";
            case STARTING:
                return "저장된 연결로 도우미를 시작합니다. 잠시 기다리세요.";
            case READY:
                return "도우미가 응답합니다. 실제 복사 감지 상태는 홈의 공유 기능에서 확인하세요.";
            case WIFI_REQUIRED:
                return "신뢰하는 Wi-Fi에 연결한 뒤 다시 시도하세요.";
            case DEBUGGING_REQUIRED:
                return "개발자 옵션의 무선 디버깅을 켠 뒤 다시 시도하세요.";
            case PAIRING_REQUIRED:
                return "연결에 실패했습니다. 설정에서 새 페어링 코드를 열고 입력하세요.";
            case ERROR:
                return paired ? "다시 연결하세요. 계속 실패하면 새 페어링 코드를 사용하세요."
                        : "연결에 실패했습니다. 현재 페어링 코드를 확인하고 다시 시도하세요.";
            case STOPPED:
                return paired ? "연결을 누르면 저장된 연결로 도우미를 시작합니다."
                        : "처음 연결하려면 이 휴대폰의 페어링 코드를 입력하세요.";
            case UNSUPPORTED:
                return "앱 안에서 도우미를 연결하려면 Android 11 이상이 필요합니다.";
            default: throw new IllegalStateException("unknown_helper_state");
        }
    }

    static boolean busy(EmbeddedHelperManager.State state) {
        return state == EmbeddedHelperManager.State.PAIRING || state == EmbeddedHelperManager.State.STARTING;
    }
}
