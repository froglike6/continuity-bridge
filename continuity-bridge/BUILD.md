# 소스 빌드와 로컬 검증

저장소 루트에서 실행합니다. 배포된 앱을 그대로 설치하려면 [README](../README.md)를 따릅니다. 아래 작업은 운영 서버·설치된 앱 데이터를 변경할 필요가 없습니다.

## Android

현재 수동 빌드는 macOS/Linux 셸, Android SDK Platform 35·Build Tools 35.0.0·Command-line Tools latest, JDK 17 이상, Python 3, OpenSSL, `curl`, `zip`, `unzip`, `perl`, `shasum`, `rg`를 사용합니다. Android Studio의 SDK Manager에서 플랫폼과 도구를 먼저 설치합니다. 환경에 맞는 SDK와 JDK 경로를 지정합니다.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools" "cmdline-tools;latest"
./continuity-bridge/android/build.sh
```

Linux는 `ANDROID_HOME`과 `JAVA_HOME`을 해당 머신의 경로로 바꿉니다. `ANDROID_SDK_ROOT`도 지원합니다. SDK 경로를 자동 탐색할 수 없는 경우 명시적으로 설정합니다. 비표준 설치는 `CONTINUITY_ANDROID_BUILD_TOOLS`, `CONTINUITY_ANDROID_PLATFORM_DIR`, `CONTINUITY_APKANALYZER`에 해당 도구 디렉터리·플랫폼 디렉터리·실행 파일의 절대 경로를 지정합니다.

서명 키는 기본 `$HOME/.android/debug.keystore`를 사용하고, 없으면 개발용 키를 만듭니다. 다른 키 파일은 `CONTINUITY_DEBUG_KEYSTORE`로 지정합니다. 기존 앱 업데이트에는 같은 키가 필요합니다. 개인 서명 키를 Git에 넣지 않습니다.

빌드 결과:

- `outputs/continuity-bridge-android-embedded-debug.apk`
- `outputs/continuity-bridge-android-embedded-debug.apk.sha256`

의존성은 [embedded.lock](android/dependencies/embedded.lock)의 HTTPS URL과 SHA-256으로 검증합니다. 첫 빌드에는 다운로드가 필요합니다. `CONTINUITY_EMBEDDED_CACHE`로 다운로드 캐시 위치를 지정할 수 있습니다.

## Mac

macOS 13 이상과 Swift 6.2 이상이 필요합니다. 설치된 Xcode 도구 체인이 선택되어 있는지 확인합니다.

```sh
swift --version
./continuity-bridge/macos/scripts/build-app.sh
```

현재 머신 아키텍처로 빌드하며 `outputs/ContinuityBridge.app.zip`과 체크섬을 만듭니다. 기본 빌드는 공인 HTTPS를 위한 시스템 신뢰 모드입니다. 서버의 인증서 개인키나 개발자 전용 인증서 지문이 필요하지 않습니다.

## 자체 CA를 쓰는 로컬 테스트

공인 도메인으로 접속하는 일반 사용에는 이 단계가 필요하지 않습니다.

```sh
./continuity-bridge/relay/scripts/generate-local-tls.sh
./continuity-bridge/relay/scripts/generate-local-auth.sh
```

생성된 `continuity-bridge/runtime/`은 Git에서 제외합니다. 인증서 생성기는 기존 유효한 묶음을 재사용하며, 인증 토큰 생성기는 새 토큰을 쓰므로 기존 서버에서 다시 실행하지 않습니다.

Mac에 로컬 공개 CA와 서버 인증서 핀을 넣으려면 두 경로를 함께 지정합니다.

```sh
CONTINUITY_CA_PEM="$PWD/continuity-bridge/runtime/tls/ca.pem" \
CONTINUITY_LEAF_PIN_FILE="$PWD/continuity-bridge/runtime/tls/server-cert.sha256" \
./continuity-bridge/macos/scripts/build-app.sh
```

Android 기본 빌드는 저장소의 공개 테스트 CA를 검사하며 `runtime/`이 필요 없습니다. 자신의 CA로 시험할 때는 별도 개발 복사본의 `continuity-bridge/android/app/src/main/res/raw/continuity_local_ca.pem`을 해당 공개 CA로 교체하고 같은 파일을 명시합니다.

```sh
CONTINUITY_ANDROID_CA_PEM="$PWD/continuity-bridge/runtime/tls/ca.pem" \
./continuity-bridge/android/build.sh
```

빌드는 명시한 CA의 형식·유효기간·CA 제약과 앱 리소스의 정확한 일치를 검사합니다. 리소스만 바꾸는 암묵적 교체나 개인키가 섞인 파일은 거부합니다. 공개 테스트 CA를 대상으로 하는 negative 검사 자체는 수정하지 않습니다. 공인 도메인에 연결할 때는 앱에서 **시스템 신뢰 저장소 사용**을 선택하면 됩니다.

로컬 릴레이를 직접 실행할 때는 소스의 기본 환경 변수와 본인이 만든 TLS·인증 파일을 사용합니다. 토큰을 코드·셸 기록에 직접 붙여 넣지 않습니다. 서버의 파일 배치와 변수는 [서버 설치 안내](deploy/lxc/README.md)에 있습니다.

## 자동 검사

```sh
node continuity-bridge/protocol/test-contract.mjs
node --test continuity-bridge/relay/test/*.test.mjs
./continuity-bridge/android/run-host-tests.sh
./continuity-bridge/android/run-embedded-lifecycle-tests.sh
./continuity-bridge/android/run-embedded-discovery-tests.sh
./continuity-bridge/android/fixture/run-host-tests.sh
swift test --package-path continuity-bridge/macos
node --test continuity-bridge/integration/test-*.mjs
```

TLS 경계 호스트 테스트에는 위에서 생성한 로컬 인증서가 필요합니다. 기기 테스트는 별도의 [fixture 안내](android/fixture/README.md)를 따릅니다. AVD로 클립보드를 검증할 때는 Android Studio와 에뮬레이터의 자동 클립보드 공유를 꺼 브리지의 전달과 구분합니다.

## 재배포 자료

Android 의존성의 고정 소스와 라이브러리 교체 절차는 [의존성 안내](android/dependencies/embedded-README.md), 현재 APK의 대응 자료는 [소스 ZIP](../outputs/continuity-bridge-android-embedded-source.zip)에 있습니다. 앱 소스나 의존성을 수정해 APK를 배포한다면 그 APK와 대응되는 자료도 갱신합니다.
