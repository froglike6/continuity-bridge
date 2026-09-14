# Shizuku 클립보드 공유

이 문서는 이전 외부 Shizuku 방식의 검증 기록입니다. 현재 기본 빌드는 [브리지 내장 도우미](EMBEDDED_HELPER.md)를 사용합니다.

이 브랜치는 Shizuku의 별도 도우미 프로세스에서 클립보드를 읽습니다. 복사할 때 Activity나 오버레이를 띄우지 않으며, 기존 키보드를 그대로 사용합니다. `READ_LOGS` 또는 포커스를 가져오는 방식으로 돌아가는 대체 경로는 없습니다.

## 설정

1. [공식 다운로드 안내](https://shizuku.rikka.app/download/)에서 Shizuku를 설치합니다. 현재 검증에 사용한 관리자는 **13.6.0**입니다.
2. [공식 시작 안내](https://shizuku.rikka.app/guide/setup/)에 따라 무선 디버깅 또는 ADB로 Shizuku를 시작합니다. 이 구현은 **비루트 shell UID 2000**만 지원합니다. root·Sui 실행은 지원하지 않습니다.
3. 연속성 브리지 앱의 Shizuku 설정에서 **Shizuku 사용 허용**을 누르고 권한을 허용합니다. 앱은 이 버튼을 누르기 전에 권한 창을 자동으로 띄우지 않습니다.
4. 브리지 공유 서비스를 시작하고 도우미가 준비되었는지 확인합니다. 재부팅 후에는 Shizuku 실행 상태를 확인하고 필요하면 다시 시작합니다. 자동 시작 여부는 Shizuku와 기기 설정에 따라 달라집니다.

Shizuku가 설치되지 않았거나, 실행 중이 아니거나, 권한이 없으면 앱이 해당 상태를 표시합니다. 이때 Android에서 복사한 내용은 창을 띄워 우회 수집하지 않습니다. 도우미 없이도 클립보드 공유가 계속된다고 가정하면 안 됩니다. 알림 미러링의 Android 알림 접근 권한은 별도로 필요합니다.

Android가 **“Shell이 클립보드를 읽었다”**와 같은 시스템 안내를 표시할 수 있습니다. 이 구현은 그 안내를 숨기지 않습니다.

## 동작과 제한

- 실제 시스템 클립보드 리스너 등록이 확인된 뒤에 준비 상태를 표시합니다. Shizuku 서버는 실행 중이고 클립보드 도우미 프로세스만 종료된 경우, 공유 서비스가 활성화된 동안 최대 30초 간격의 재시도로 다시 연결합니다.
- 도우미는 설치된 브리지 앱의 UID만 클립보드 IPC 호출자로 허용합니다. 임의 명령 실행이나 임의 파일 읽기 API를 제공하지 않습니다.
- 사진은 현재 클립보드의 URI와 시각이 요청한 항목과 일치할 때만 읽습니다. 이미지 스트림은 최대 **8 MiB**이며 기존 PNG·JPEG 형식 및 해상도 검사를 거칩니다. 멈춘 이미지 전송에는 **10초** 제한을 적용하고 도우미를 다시 연결합니다.
- 기존 `READ_LOGS`, 다른 앱 위에 표시 권한, 일시적인 클립보드 캡처 Activity는 이 브랜치에서 사용하지 않습니다.

## 빌드

현재 저장소의 Android SDK·Android Studio JBR·디버그 키 설정을 사용하는 수동 빌드입니다. `continuity-bridge/android` 디렉터리에서 실행합니다.

```sh
./build.sh
```

공식 **Shizuku SDK 13.1.5**의 API·provider·AIDL·shared 아티팩트와 컴파일 전용 AndroidX annotation을 사용합니다. [의존성 잠금 파일](dependencies/shizuku.lock)에 다운로드 주소와 SHA-256을 고정하고, 캐시도 매번 검사합니다. SDK 라이선스는 APK에 포함됩니다.

생성 위치는 다음과 같습니다.

- 중간 빌드: `continuity-bridge/android/build/shizuku/`
- 설치 APK: 저장소 루트의 `outputs/continuity-bridge-android-shizuku-debug.apk`
- 체크섬: 같은 위치의 `.apk.sha256`

기존 `continuity-bridge-android-debug.apk` 출력은 덮어쓰지 않습니다. APK의 앱 패키지는 기존 브리지와 같으므로 같은 장치에 설치하면 별도 앱이 아닌 기존 앱의 업데이트가 됩니다.

## 검증 범위

현재 **Android API 37 에뮬레이터와 Gboard**, 공식 Shizuku 관리자 13.6.0 조합에서 확인했습니다.

- 텍스트 복사 **20회 모두 성공**, 키보드 내려감과 원래 앱 포커스 이동 없음
- PNG·JPEG 캡처의 바이트 일치
- 잘못된 이미지, 용량 초과, 해상도 초과 이미지 거부
- 원격 텍스트·이미지 적용 확인과 실제 붙여넣기, 반대 방향 재전송 없음
- 이미지 제공 앱이 멈춘 상황에서 약10초 뒤 복구, 도우미 강제 종료 후 새 복사 처리
- Shizuku가 없거나 연결되지 않은 상태의 안내 및 창 전환 없는 대기

### 실제 Galaxy 검증 — 2026-09-12

Galaxy SM-S948N의 Android16/API36과 삼성 키보드에서 같은 APK를 설치해 실제 Mac 앱과 검증했습니다. 기존 연결 설정과 암호화된 인증 정보는 유지했습니다.

- 텍스트 반복 복사10회와 삼성 기본 선택 메뉴의 복사가 Mac에 도착했고, 키보드 내려감·원래 앱 포커스 이동은 없었습니다.
- 복사 후 삼성 키보드의 숫자 키 입력도 계속됐습니다.
- Mac→Galaxy 텍스트 붙여넣기와 PNG·JPEG 양방향 붙여넣기에서 원본 바이트가 일치했습니다.
- 이 Galaxy에서는 Shizuku 서버 재시작 후 도우미가 자동으로 재연결됐고, 새 복사도 Mac에 도착했습니다. 앞서 적은 API37 에뮬레이터의 재실행 제약과 다른 관찰 결과입니다.
- 설치 APK SHA-256: `fe9b0c40b5713c701cf4a3671bccdecd31af2c7e0e93d9e3843a4a8c422751cf`.

테스트 전용 앱은 제거했고, 브리지와 Shizuku는 실행 상태로 유지했습니다. 휴대폰 전체 재부팅·장시간 절전·모든 앱의 복사 동작까지 확인한 결과는 아닙니다.

### Shizuku 서버 재시작 시 확인된 제한

API 37 에뮬레이터와 관리자 13.6.0에서 **Shizuku 서버 자체를 종료했다가 다시 시작하면**, 이미 실행 중이던 브리지 앱이 새 Binder를 받지 못해 자동으로 복구되지 않는 경우를 확인했습니다. 브리지 앱을 **강제 종료한 뒤 다시 열고 공유 서비스를 시작**하면 실행 중인 Shizuku 서버에 다시 연결되는 것을 확인했습니다. 클립보드 도우미 프로세스만 종료되는 경우의 자동 재연결과는 다른 상황입니다.

현재 SDK 통합에서 관찰한 제한이며 정확한 원인은 확정하지 않았습니다. Shizuku 서버 재시작 후 자동 복구를 보장하지 않습니다.

## 공식 자료

- [Shizuku 설정 안내](https://shizuku.rikka.app/guide/setup/)
- [Shizuku 13.6.0 릴리스](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)
- [Shizuku SDK 및 UserService 설명](https://github.com/RikkaApps/Shizuku-API/tree/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5)
- [Android ClipboardManager의 백그라운드 읽기 제한](https://developer.android.com/reference/android/content/ClipboardManager#getPrimaryClip())
