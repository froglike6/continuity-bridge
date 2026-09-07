# Continuity Bridge 구현 및 검증 인수인계

기준: 2026-09-07. 요청한 현재 Mac + Debian Docker + Android Studio API 37 AVD 환경에서 구현과 실제 전달 검증을 마쳤습니다. 이 문서는 9월 5일의 미완료 목록을 대체합니다. 개발용 서명 산출물이며, 별도 Galaxy 실기기나 Proxmox 호스트의 검증 결과는 포함하지 않습니다.

## 범위

- Android ↔ macOS 일반 텍스트 클립보드
- Android 알림 → macOS 네이티브 알림, 같은 Android 알림의 업데이트 교체
- HTTPS long-poll 릴레이, 역할/기기별 토큰, CA·호스트명·인증서 핀 검증, 내구 큐와 ACK
- 이미지·파일·히스토리·알림 회신은 포함하지 않습니다.

## 이번에 마무리한 구현

### Android

- ClipboardManager 쓰기가 실제 반영됐는지 확인한 뒤 ACK합니다. 쓰기 AppOp 거부, 읽기 불가, 타임아웃, 중지 시에는 성공 처리하지 않습니다.
- 원격 적용 의도를 OS 쓰기 전에 내구 저장하고, 적용 확인과 관찰 identity로 echo를 막습니다. 실패한 로컬 저장은 같은 관찰을 다시 시도할 수 있습니다.
- 프로세스가 죽었는데 과거 CONNECTED 값만 남는 상태를 바로잡았습니다. 실제 서비스 실행이 없으면 Start를 사용할 수 있습니다.
- 새 클립보드/알림 저장 시 대기 중인 GET만 깨웁니다. publish·ACK·연결 소유권은 보존하며 25초 long-poll 자체는 유지합니다.
- 클립보드 권한 실패를 알림 권한 허용만으로 지우지 않습니다. 오류 원인과 재시작 안내를 표시합니다.

### macOS

- 로컬 클립보드 이벤트의 내구 저장 실패 시 changeCount를 소비하지 않아 재시도가 가능합니다.
- 새 outbox 이벤트가 대기 GET을 깨워 불필요한 전송 지연을 줄입니다.
- 릴레이 재시작 중 일시적인 TLS handshake 실패는 재시도하고, 실제 인증서/핀 불일치는 차단합니다.
- 앱 실행·다시 열기에서 설정 창이 실제로 열립니다. 전경에서도 네이티브 알림 표시를 요청합니다.
- 로그인 항목 미등록을 변경 실패로 잘못 표시하던 문구를 수정했습니다. 실제 등록/해제도 확인했습니다.

### 릴레이와 검증기

- 최근 4,096개 retry identity와 아직 ACK되지 않은 오래된 이벤트 identity를 함께 보존합니다.
- 원자적 저장 임시 파일은 자신이 소유한 파일만 정리하며, 충돌한 기존 파일을 삭제하지 않습니다. 재시작 시 정확한 형식의 잔여 임시 파일만 정리합니다.
- 제품 Compose와 통합 검증기 모두 쓰기 가능한 내구 저장소를 확인하는 `/v1/ready`를 사용합니다.

## 자동 검증

| 검사 | 통과 결과 |
|---|---:|
| 프로토콜 계약 | 123 fixtures / 12 scenarios |
| 릴레이 | 47 tests |
| Android host | 442 assertions, engine 74와 wakeup 44 포함 |
| Android adapter / 암호화 | 64 / 14 |
| macOS XCTest | 61 tests |
| 통합 검증기 | 49 tests |

Java는 `-Xlint:all -Werror`, Swift는 컴파일·XCTest, Node는 테스트·구문 검사로 검증했습니다. APK manifest/production wiring/격리 경계와 서명·SHA 영수증, Mac codesign·ZIP·boot smoke도 통과했습니다. LSP 도구는 이 작업의 별도 cwd 바깥 경로를 거부했으므로 LSP 통과로 표현하지 않습니다.

실행 명령:

```sh
node continuity-bridge/protocol/test-contract.mjs
node --test continuity-bridge/relay/test/*.test.mjs
./continuity-bridge/android/run-host-tests.sh
./continuity-bridge/android/build.sh
(cd continuity-bridge/macos && swift test)
./continuity-bridge/macos/scripts/build-app.sh --boot-smoke
node --test continuity-bridge/integration/test-*.mjs
```

## 실제 표면에서 검증한 결과

- 최종 APK의 한글 텍스트: Android → 실제 Mac pasteboard 2,275 ms, Mac → 실제 Android ClipboardManager 확인/ACK 1,939 ms. 각각 한 번의 통제된 측정이며 성능 보장값은 아닙니다.
- Android Studio와 독립 emulator 양쪽의 자동 클립보드 공유를 끄고, 브리지 중지 시 양방향 전송이 일어나지 않는 대조 시험을 먼저 통과했습니다.
- 원격 적용은 echo를 만들지 않고, 같은 텍스트를 나중에 Android에서 직접 다시 복사하면 새 이벤트 한 개로 전달됩니다.
- Android WRITE_CLIPBOARD를 거부하면 이전 실제 클립보드가 그대로이며 이벤트를 ACK하지 않습니다. 최종 UI는 `권한 필요`와 복구 안내, 활성화된 Start를 유지합니다. 권한 복구 후 Start로 보류 이벤트가 전달·ACK됩니다.
- API 37에서 새 프로세스 실행 후 STOPPED/Start 가능 상태, 실제 Start와 연결, 로그 읽기 일회성 허용 흐름을 확인했습니다.
- 실제 실행 중인 Mac 앱에서 공개 `UNUserNotificationCenter.getDeliveredNotifications` API로 알림 한 개와 정확한 제목/본문을 확인했습니다. 같은 notification key의 업데이트는 같은 request identifier 한 개를 유지했습니다. 릴레이 재시작 뒤에도 한 개이며 브리지 자신의 FGS 알림은 0개였습니다.
- 알림 확인은 임시 LLDB 읽기 전용 호출과 OS 전달 로그로 수행했습니다. 디버거는 분리했고 제품에 테스트 API를 넣지 않았습니다. 화면 제어 도구는 알림 센터 대신 데스크톱 위젯 창을 선택하므로 알림 센터 패널의 직접 스크린샷 확인은 하지 못했습니다.
- ACK 전 알림은 Docker 재시작 후에도 동일 epoch·event ID로 남고 Mac 재연결 후 전달·ACK됩니다. ACK 후 재시작에는 retained 이벤트가 없습니다.
- 릴레이 중단 시 두 앱이 재시도 상태를 표시했습니다. 컨테이너 재시작 후 앱을 수동 재시작하지 않고 복구했고, 중단 중 Mac에서 복사한 값이 약 17.7초 후 Android에 도착했습니다.
- 틀린 Mac leaf pin은 `보안 연결 확인 필요`로 차단되며 올바른 핀 복구 후 연결됩니다.
- 로그인 항목의 등록/해제를 실제로 실행하고 최종 OFF로 돌렸습니다. 실제 Mac 재로그인은 수행하지 않았습니다.
- 최신 네이티브 설정 화면 9개를 독립 검토자 두 명이 확인해 통과했습니다. 상세 로그·영수증은 `work/completion-20260907/`에 있습니다.

## 이 Mac에서 다시 실행

검증된 Mac 앱은 `continuity-bridge/macos/dist/ContinuityBridge.app`, Android 앱은 `ContinuityBridge_API_37` AVD에 설치되어 있습니다. 양쪽 설정과 보안 저장소의 토큰, 대응하는 Docker named volume은 보존합니다. 테스트 종료 후 서비스와 전용 컨테이너/AVD는 중지합니다.

```sh
docker start cb-completion-20260907
curl --cacert continuity-bridge/runtime/tls/ca.pem https://localhost:8443/v1/ready
open continuity-bridge/macos/dist/ContinuityBridge.app
```

Android Studio의 Device Manager에서 `ContinuityBridge_API_37`을 실행하고 연속성 브리지 앱의 Start를 누릅니다. Mac 설정에서도 Start를 누릅니다.

- Mac endpoint: `https://localhost:8443`
- AVD endpoint: `https://10.0.2.2:8443`
- pin: `continuity-bridge/runtime/tls/server-cert.sha256`
- Android: 알림 접근, POST_NOTIFICATIONS, READ_LOGS, 다른 앱 위 표시 권한 필요
- API 37에서는 Start 후 로그 읽기 일회성 허용 대화상자가 다시 나올 수 있습니다. 이는 배경 클립보드 관찰 경로에 필요합니다.
- 독립 검증을 재현할 때는 Android Studio Settings → Tools → Emulator의 Synchronize clipboard와 emulator의 clipboard sharing을 모두 끄고 AVD를 다시 시작해야 합니다.

전용 Docker 구성은 UID 1000, read-only root filesystem, capability 제거, no-new-privileges, loopback 포트, 메모리/PID 제한을 적용했습니다. 설정 볼륨 `cb-completion-20260907-config`는 read-only, 상태 볼륨 `cb-completion-20260907-state`만 쓰기 가능합니다. 해당 볼륨을 삭제하면 저장된 인증 설정/상태가 사라지므로 재실행 시 재생성할 필요가 없습니다.

## 새 환경으로 옮길 때

저장소의 `continuity-bridge/relay/compose.yaml`을 사용합니다. 새 CA/서버 인증서와 역할별 토큰을 만든 뒤 실제 클라이언트 device ID와 auth 설정을 맞춰야 합니다.

```sh
./continuity-bridge/relay/scripts/generate-local-tls.sh
./continuity-bridge/relay/scripts/generate-local-auth.sh
```

새 CA는 Android `app/src/main/res/raw/continuity_local_ca.pem`에 반영합니다. Android `verify-adapter-negative.sh`의 `APPROVED_PUBLIC_CA_SHA256`, Mac `scripts/build-app.sh`의 `EXPECTED_CA_SHA256`와 `EXPECTED_LEAF_PIN`을 실제 생성 값에 맞춰 다시 빌드합니다. 현재 배포 파일은 기존 로컬 CA용입니다.

각 앱을 한 번 실행해 device ID를 만든 뒤 `auth.json`의 역할별 deviceId와 일치시킵니다. Linux Docker에서는 UID 1000이 TLS·auth 파일을 읽고 state 디렉터리를 쓸 수 있어야 하며, 비밀 파일의 공개 읽기를 허용하지 않습니다. 실제 Proxmox 호스트의 주소·방화벽·인증서 SAN·부팅 설정은 그 환경에서 별도로 맞춰야 합니다.

## 산출물과 보존 경계

| 파일 | SHA-256 |
|---|---|
| ContinuityBridge.app.zip | `55c6b3cd52bed74f96a4d72a87d74b19a8e2b2c9632ea6d9598da67a9c7abda4` |
| continuity-bridge-android-debug.apk | `af118b0626f51105b3314b4298db927bffe2e828b92adbc6df7f17102a2f6cf7` |

Mac은 ad-hoc, Android는 debug 서명입니다. App Store/Play 배포용 서명·notarization 결과가 아닙니다. 일반 Galaxy/One UI 배경 제약도 AVD 결과만으로 보장하지 않습니다.

인증서 개인키, 토큰, Keychain 내용, 기기 데이터, Docker 상태는 커밋하지 않습니다. 초기 Mac 상태/설정과 Android 데이터 백업은 `work/completion-20260907/private/`에 보호된 상태로 남깁니다. 기존 Mac Keychain 토큰은 바꾸지 않았습니다. 검증에 맞춘 현재 device ID·앱 설정을 보존하며 예전 상태 파일을 덮어씌우지 않습니다. Android 초기 데이터 백업은 Keystore와 함께 복원한 것이 아니므로 단순 tar 복원으로 사용할 수 있다고 간주하지 않습니다.
