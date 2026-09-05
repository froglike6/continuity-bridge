# Continuity Bridge 작업 인수인계

기준 시점: 2026-09-05

## 최종 목표와 범위

목표는 다음 세 구성요소를 이용하는 개인용 연속성 브리지입니다.

1. Android 앱: 일반 텍스트 클립보드 송수신, Android 알림 송신
2. macOS 메뉴 막대 앱: 일반 텍스트 클립보드 송수신, Android 알림을 네이티브 알림으로 표시
3. Debian 계열 Docker 릴레이: HTTPS long-poll, 역할별 인증, ACK, 재시작 가능한 내구 상태

Android 알림은 Android → macOS 단방향이고, 클립보드는 양방향입니다. 이미지·파일·클립보드 기록·알림 회신/동작·Mac → Android 알림은 범위 밖입니다.

## 해결된 것

### 프로토콜과 릴레이

- Android와 macOS 역할별 전송 방향과 기기 identity 검증
- HTTPS publish/fetch/ACK, 25초 long-poll, 요청 취소와 재연결
- event ID/epoch/sequence 기반 중복·stale·conflict 판정
- ACK 전 이벤트의 내구 보존과 ACK 후 제거
- 클립보드 최신값 supersession, 알림 TTL·개수·바이트 상한
- atomic state 교체, 손상된 상태 fail-closed, Docker 재시작 후 상태 복구
- constant-time 토큰 비교, 메타데이터 전용 로그, non-finite/중복 JSON key 거부
- non-root, read-only root filesystem, capability 제거, loopback 포트, 메모리/PID 제한 Docker 구성

### Android

- 토큰은 Android Keystore AES-GCM으로 저장하고 앱 상태는 암호화된 내구 파일에 저장
- foreground service와 실제 `NotificationListenerService` 연결 상태 표시
- Android 알림의 package/app label/title/body/stable notification key 매핑
- 브리지 자신의 정확한 foreground-service 알림만 제외
- Mac 클립보드를 Android `ClipboardManager`에 적용하고 event identity로 즉시 echo 방지
- Android 10+ 제한 시 ClipCascade 계열의 logcat denial 감지와 짧은 focusable overlay 재읽기 경로
- 독립 package `com.froglike6.continuityfixture`에서 클립보드 설정/읽기와 stable-ID 알림 게시/업데이트
- production APK와 fixture APK의 source/class/dex/resource 분리 검증
- production 및 fixture APK 빌드가 APK와 SHA-256 영수증을 함께 갱신하고 즉시 검증

### macOS

- Swift 메뉴 막대 앱, 숨김 Dock 아이콘, 설정/상태/시작·중지 UI
- Keychain 토큰 저장, 로컬 CA+hostname+leaf SHA-256 고정
- `NSPasteboard.changeCount`와 private event-ID type을 이용한 양방향 echo 방지
- 원격 클립보드 apply identity를 write 전에 내구화해 apply/poll race 해결
- 같은 텍스트를 나중에 사용자가 다시 복사하면 새 이벤트로 한 번 전송
- Android notification key를 안정적인 `UNNotificationRequest` identifier로 변환해 update replacement 구현
- relay 재연결, outbox/cursor/applied ID 내구화, `--start` 실행 경로
- ad-hoc signed `.app`와 ZIP 패키징

## 2026-09-05 현재 자동 검증

현재 checkout에서 아래 명령이 통과했습니다.

- protocol: `CONTRACT_FIXTURES_OK count=123 scenarios=12`
- relay: 40/40
- Android engine: 72/72
- Android host: 331/331
- Android adapter: 49/49
- Android encrypted state: 14/14
- Android fixture observation: 5/5
- macOS XCTest: 57/57
- integration verifier: 46/46

`continuity-bridge/android/fixture/run-host-tests.sh`의 빠져 있던 executable bit도 복구했습니다.

### 읽기 전용 검토 반영 상태

- 수정 완료: R01 relay 저장 실패 시 candidate state를 공개하지 않고 fail-closed 처리. 별도 `/v1/ready`가 검증된 디스크 상태를 reload한 뒤 실제 write probe까지 성공해야 복구하며 Docker healthcheck도 readiness를 사용
- 수정 완료: R02 retained 이벤트의 dedupe identity가 4,096개 경계에서 밀려나지 않도록 보존. retained identity와 최근 4,096개 retry 계약의 충돌은 남음
- 수정 완료: R03 Android 상태 갱신을 원자적 `store.update()` 경계로 통일해 최초 epoch·publish 완료 중 producer 상태 유실 방지
- 수정 완료: R04 Android `logcat -v brief` 형식의 `ClipboardService(PID):` 거부 로그 인식
- 수정 완료: R05 Android/macOS 모두 server epoch 변경 시 낮아진 cursor를 허용하고 0부터 재시도
- 수정 완료: R06 macOS `GET /v1/events`에 2 MiB batch 응답 상한을 분리하고 큰 정상 fetch의 전체 apply·ACK·최종 cursor 회귀 검사 추가
- 수정 완료: R09 backup 복구 직후 저장 실패가 정상 backup을 손상 primary로 덮지 않도록 보호
- 수정 완료: R11 fixture의 실제 `bigText` 우선 본문과 아래 native 확인 문구를 일치시킴
- 아직 남음: R02 retry 계약, R07, R08, R10, R12 및 stale relay temp 정리·Android clipboard 적용 확인 수준 보강

## 실제 표면에서 확인된 것

아래 결과는 로컬 Docker + Android 37 AVD + 현재 macOS 장치 조합에서 확인됐습니다.

- Android fixture 클립보드 → 실제 Mac pasteboard: 정확한 문자열 전달
- 실제 Mac pasteboard → Android fixture 읽기: 정확한 문자열 전달
- 원격 apply 직후 반대편 재발행 0회
- 동일 텍스트를 나중에 수동으로 다시 복사: 새 이벤트 1회
- Android 알림 post/update가 relay 이벤트가 되고 Mac ACK까지 도달
- ACK가 끝난 뒤 relay container 교체: retained 0, 재전달 0
- ACK 전 relay 상태 보존과 교체 후 재전달 자체는 관찰됨
- relay 재연결 뒤 Mac → Android 클립보드 동작
- Docker named volume의 relay state를 실제 쓰기 불가로 만들면 publish 500, liveness 200, readiness 503와 container unhealthy가 관찰되고, 권한 복구 뒤 readiness 200·재시도 201·단일 수신·container healthy로 회복

위 항목들은 전체 제품 완료 판정이 아닙니다. 아래 미해결 경계를 남겨 둡니다.

## 해결해야 할 것

### 1. macOS Notification Center 실제 표시 검증

가장 중요한 남은 항목입니다. relay event와 Mac ACK는 확인했지만, 실제 Notification Center에서 다음을 한 번의 fresh run으로 확인하지 못했습니다.

- `Fixture title` / `Fixture big body`가 `Continuity Bridge` 출처의 네이티브 알림 한 개로 표시
- 같은 Android notification key의 update 후 항목 수는 여전히 한 개
- 제목/본문은 `Fixture title 업데이트` / `Fixture big body 업데이트`로 바뀌고 이전 문자열은 사라짐
- Android 브리지 foreground-service 알림은 Mac에 나타나지 않음

주의: `com.apple.notificationcenterui`만 조회하면 닫힌 패널의 system widget만 보일 수 있습니다. 실제 패널은 macOS 메뉴 막대의 시계를 클릭해 연 다음 접근성 트리와 스크린샷을 확인해야 합니다.

### 2. ACK 전 재전달의 native surface idempotency

이전 실행에서 NotificationListener가 동일 stable notification key에 대해 복수 callback/event를 만들었습니다. 프로토콜은 각 callback마다 새 bridge event ID를 주도록 설계됐으므로 transport event 개수 자체가 실패 조건은 아닙니다. 확인할 것은 다음입니다.

- 모든 callback이 동일 Android notification key를 유지하는지
- Mac의 안정적인 request identifier 때문에 재연결 전후에도 native 항목이 한 개뿐인지
- ACK 이후 relay 재시작에서는 다시 표시되지 않는지

listener 활성화 직후 기존 active notification들이 다시 들어올 수 있으므로, controlled fixture post 전에 baseline cursor와 callback을 분리해야 합니다.

### 3. 전체 E2E 한 번에 완료

마지막 v5 실행은 Android와 relay가 연결된 상태에서 Mac의 새 토큰을 Keychain에 저장하려는 순간 중단됐습니다. 현재 Docker Desktop daemon은 켜져 있지만 AVD, Mac 앱, 8443 relay listener가 없으므로 기존 runtime을 이어 쓰지 말고 fresh runtime으로 다시 시작해야 합니다.

### 4. 실제 장치와 배포 환경

- Galaxy/One UI에서 이 브리지 APK의 전체 기능은 아직 검증하지 않음
- 일반 background clipboard 접근은 Android 플랫폼 제한 때문에 검증 보류
- 초기 ClipCascade 계열 probe에서 denial-log + overlay 방식은 관찰됐지만 제품 실기기 통과로 간주하면 안 됨
- 실제 Proxmox LXC 설치·부팅·재시작·방화벽·인증서 배포는 아직 검증하지 않음
- 현재 Docker 검증은 이후 Proxmox LXC 배포를 위한 Debian 계열 후보 검증일 뿐임

### 5. 새 머신용 TLS trust bootstrap

런타임 TLS 개인키는 저장소에서 제외했습니다. 따라서 `outputs/`의 현재 앱들은 원래 로컬 CA에 묶여 있으며, 새로 생성한 relay 인증서와 그대로 조합하면 안 됩니다. 새 머신에서는 새 CA를 생성한 뒤 두 클라이언트를 다시 빌드해야 합니다.

## 새 머신에서 재개하는 순서

### 1. clone과 도구 준비

```sh
git clone https://github.com/froglike6/temp.git
cd temp
```

현재 확인된 개발 환경은 arm64 macOS, Node 26, Swift 6.3, Android Studio JBR 21입니다. 스크립트는 Apple Silicon Homebrew의 `/opt/homebrew`와 `/Applications/Android Studio.app`을 기본 경로로 사용합니다. 다른 경로라면 스크립트 변수부터 조정해야 합니다.

필요한 Android 항목:

- platform `android-35`
- build-tools `35.0.0`
- Android 37 AVD용 `google_apis_playstore_ps16k;arm64-v8a` system image
- debug keystore

### 2. 새 TLS와 역할별 토큰 생성

```sh
./continuity-bridge/relay/scripts/generate-local-tls.sh
./continuity-bridge/relay/scripts/generate-local-auth.sh
```

생성된 `continuity-bridge/runtime/` 전체는 커밋하지 않습니다. 새 CA를 클라이언트 build input에 동기화합니다.

```sh
cp continuity-bridge/runtime/tls/ca.pem \
  continuity-bridge/android/app/src/main/res/raw/continuity_local_ca.pem
```

그 다음 새 값에 맞춰 아래 build guard를 갱신합니다.

- `continuity-bridge/android/verify-adapter-negative.sh`: `APPROVED_PUBLIC_CA_SHA256`
- `continuity-bridge/macos/scripts/build-app.sh`: `EXPECTED_CA_SHA256`, `EXPECTED_LEAF_PIN`

CA SHA-256은 `shasum -a 256 continuity-bridge/runtime/tls/ca.pem`, leaf pin은 `continuity-bridge/runtime/tls/server-cert.sha256`에서 얻습니다. 토큰 값은 로그나 문서에 복사하지 않습니다.

### 3. 테스트와 빌드

```sh
node continuity-bridge/protocol/test-contract.mjs
node --test continuity-bridge/relay/test/*.test.mjs
./continuity-bridge/android/run-host-tests.sh
./continuity-bridge/android/fixture/run-host-tests.sh
./continuity-bridge/android/build.sh
./continuity-bridge/android/build-fixture.sh
(cd continuity-bridge/macos && swift test)
./continuity-bridge/macos/scripts/build-app.sh --boot-smoke
node --test continuity-bridge/integration/test-*.mjs
```

### 4. client identity와 relay auth 맞추기

`identity_mismatch`를 피하려면 토큰뿐 아니라 relay `auth.json`의 `deviceId`가 각 앱의 실제 durable device ID와 같아야 합니다.

1. Android 앱을 한 번 시작해 `device_id`를 생성
2. debug APK에서는 `run-as com.froglike6.continuitybridge`로 `shared_prefs/bridge_configuration.xml`의 `device_id` 확인
3. Mac 앱을 한 번 시작해 `~/Library/Application Support/ContinuityBridge/state.json`의 `deviceId` 확인
4. `continuity-bridge/runtime/auth/auth.json`의 활성 Android/macOS credential `deviceId`를 각각 맞춤
5. relay를 시작하거나 재생성

오래된 프로세스의 403 로그 뒤에 새 프로세스의 `CONNECTED`가 나타날 수 있으므로, 마지막 상태는 현재 PID 기준으로 판정합니다.

### 5. 로컬 relay와 클라이언트 설정

```sh
(cd continuity-bridge/relay && docker compose up -d --build)
```

- Android endpoint: `https://10.0.2.2:8443`
- macOS endpoint: `https://localhost:8443`
- 양쪽 leaf pin: `continuity-bridge/runtime/tls/server-cert.sha256`
- 토큰: 각 역할에 대응하는 generated credential을 앱의 보안 입력 UI에만 입력
- Android: notification access, POST_NOTIFICATIONS, READ_LOGS, SYSTEM_ALERT_WINDOW 확인
- macOS: 알림 권한을 허용하고 Settings에서 저장한 뒤 시작

### 6. 남은 E2E의 최소 순서

1. 양쪽 `CONNECTED`와 정확한 현재 PID를 고정
2. listener 활성화 직후 stale notification baseline을 먼저 소진/기록
3. fixture 알림 post → 실제 열린 Mac Notification Center에서 한 항목 확인
4. 같은 fixture 알림 update → 같은 항목의 문자열 교체 확인
5. 브리지 FGS 알림 부재 확인
6. Mac을 중지한 상태에서 controlled notification 한 번 생성
7. relay container를 같은 state mount로 교체하고 Mac 재연결
8. native 항목이 중복되지 않고 ACK되는지 확인
9. ACK 후 relay를 다시 교체하고 무재전달 확인
10. 재연결 후 양방향 클립보드 한 번씩 확인

## 현재 배포 산출물

이 파일들은 원래 로컬 CA에 묶인 개발용 산출물입니다.

```text
6d22d4dbd105826e12d34d8972ce4bc5f381f69a596c587c2e28a436cfd65b28  ContinuityBridge.app.zip
e0e7288f65bdf5c497cfe8cf3680cb152b6208c8b47fbf01d6adb1cf5a66c6bb  continuity-bridge-android-debug.apk
b2921691bbbce82be0aa4e0fc9079179354734f36502875a6393598c31e38254  continuity-fixture-debug.apk
```

## 보안 및 커밋 경계

저장소에는 소스, 테스트, 문서와 개발용 빌드 산출물만 넣습니다. 다음 항목은 의도적으로 제외합니다.

- TLS CA/server 개인키와 runtime state
- 역할별 bearer token과 auth.json
- Android debug keystore
- Keychain 내용과 macOS Application Support state
- AVD image/data, Docker volume, 빌드 cache
- 로컬 실행 로그와 내부 작업 증거

최종 완료라고 보고할 수 있는 기준은 native macOS 알림 post/update/reconnect 화면 검증까지 통과하고, 이후 별도로 Galaxy와 Proxmox LXC 검증 범위를 명시하는 것입니다.
