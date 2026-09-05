# macOS–Android Continuity Bridge

개인용 macOS–Android 연속성 브리지입니다.

- Android 알림을 macOS 네이티브 알림으로 전달
- Android ↔ macOS 일반 텍스트 클립보드 공유
- 로컬 Debian 계열 Docker 릴레이
- TLS 인증서 고정과 역할별 토큰 인증
- Android 37 AVD용 독립 fixture APK

현재 단계는 개발용 프로토타입입니다. 검증된 범위, 남은 문제, 새 머신에서 이어서 작업하는 순서는 [HANDOFF.md](HANDOFF.md)에 정리되어 있습니다.

## 디렉터리

- `continuity-bridge/protocol`: 프로토콜 v1 명세와 계약 fixture
- `continuity-bridge/relay`: 의존성 없는 Node HTTPS 릴레이와 Docker 구성
- `continuity-bridge/android`: Android 앱, 독립 fixture, 호스트 테스트와 빌드 스크립트
- `continuity-bridge/macos`: Swift 메뉴 막대 앱, 테스트와 패키징 스크립트
- `continuity-bridge/integration`: Docker 릴레이 통합 시나리오와 검증기
- `work/android-clipboard-probe`: 초기 Android/ClipCascade 방식 탐색용 앱
- `outputs`: 현재 빌드된 APK와 macOS 앱 ZIP

## 빠른 정적 검증

```sh
node continuity-bridge/protocol/test-contract.mjs
node --test continuity-bridge/relay/test/*.test.mjs
./continuity-bridge/android/run-host-tests.sh
./continuity-bridge/android/fixture/run-host-tests.sh
(cd continuity-bridge/macos && swift test)
node --test continuity-bridge/integration/test-*.mjs
```

런타임 인증서와 토큰은 의도적으로 저장소에 포함하지 않습니다. 새 머신에서 실행하기 전에 [HANDOFF.md](HANDOFF.md)의 TLS·인증 bootstrap 절차를 먼저 따라야 합니다.
