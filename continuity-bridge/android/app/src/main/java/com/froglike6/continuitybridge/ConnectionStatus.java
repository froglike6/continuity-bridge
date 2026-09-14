package com.froglike6.continuitybridge;

public enum ConnectionStatus {
    DISCONNECTED("연결 안 됨"), CONNECTING("연결 중"), CONNECTED("연결됨"),
    CLIPBOARD_WAIT("연결됨 · 복사 대기"),
    AUTH_FAILURE("인증 실패"), TLS_FAILURE("보안 연결 확인 필요"),
    SECURITY_FAILURE("보안 저장소 확인 필요"),
    PERMISSION_REQUIRED("권한 필요"), RETRY("잠시 후 다시 시도"), STOPPED("서비스 중지됨");

    private final String korean;
    ConnectionStatus(String korean) { this.korean = korean; }
    public String korean() { return korean; }
}
