# Android 의존성

현재 빌드는 [내장 ADB 도우미 의존성](embedded-README.md)과 `embedded.lock`을 사용합니다. APK 하나로 설치하며 외부 Shizuku SDK/provider를 포함하지 않습니다.

## 이전 Shizuku 빌드 기록

아래 항목은 이전 실험 빌드의 출처와 재현 자료입니다. 현재 `build.sh`의 의존성 목록이 아닙니다.

`shizuku.lock` pins official Maven artifacts and their SHA-256 checksums. `fetch.sh` verifies every cached artifact before extracting classes; it never executes downloaded build scripts. Shizuku API, provider, aidl and shared are MIT licensed (see LICENSE-Shizuku.txt). AndroidX annotations 1.3.0 are compile-only, Apache-2.0 licensed and are not packaged in the APK.

Upstream integration guide: https://github.com/RikkaApps/Shizuku-API/tree/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5

The hand-built manifest incorporates the provider AAR's API_V23 permission and V3_SUPPORT metadata. The Shizuku APK is written separately as `outputs/continuity-bridge-android-shizuku-debug.apk`; the previously built default APK is preserved.
