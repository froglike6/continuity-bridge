# 설치 파일

새 설치에는 다음 두 앱을 사용합니다. 앱에 개인 서버 토큰이나 ADB 페어링 키는 들어 있지 않습니다. 자신의 릴레이 연결 정보는 설치 후 입력합니다.

| 파일 | 용도 |
|---|---|
| [ContinuityBridge.app.zip](ContinuityBridge.app.zip) | Apple Silicon Mac 메뉴 막대 앱 |
| [continuity-bridge-android-embedded-debug.apk](continuity-bridge-android-embedded-debug.apk) | 현재 Android 앱. 클립보드 도우미 내장 |
| [continuity-bridge-android-embedded-source.zip](continuity-bridge-android-embedded-source.zip) | 위 APK의 대응 소스·고정 의존성·재빌드와 라이브러리 교체 자료 |

각 파일의 같은 이름 `.sha256` 파일을 함께 내려받고, 이 디렉터리에서 검사합니다.

```sh
shasum -a 256 -c ContinuityBridge.app.zip.sha256
shasum -a 256 -c continuity-bridge-android-embedded-debug.apk.sha256
shasum -a 256 -c continuity-bridge-android-embedded-source.zip.sha256
```

Mac은 ad-hoc, Android는 debug 서명입니다. Mac 공증·스토어 배포용 서명 결과가 아닙니다. 직접 다시 빌드한 APK는 서명이 다를 수 있으므로 기존 설치 위에 업데이트할 때 사용한 서명을 확인합니다.

APK를 다른 곳에 재배포할 때는 대응 소스 ZIP과 포함된 의존성 라이선스·재빌드 자료도 함께 제공합니다. [설치 안내](../README.md)와 [현재 제한](../HANDOFF.md)을 함께 확인합니다.

## 이전 빌드와 개발 검증 파일

다음 파일은 과거 구현 비교와 개발 검증용입니다. 새 설치에는 위의 `embedded` APK를 선택합니다.

| 파일 | 구분 |
|---|---|
| `continuity-bridge-android-debug.apk` | 내장 도우미 이전 Android 빌드 |
| `continuity-bridge-android-shizuku-debug.apk` | 외부 Shizuku를 사용하는 이전 실험 빌드 |
| `continuity-fixture-debug.apk` | 합성 클립보드·알림 검사 앱 |
| `continuity-download-fixture-debug.apk` | 다운로드 진행률 검사 앱 |
| `clipboard-probe-debug.apk`, `clipcascade-probe-debug.apk` | 초기 클립보드 조사 앱 |

검증 앱은 일반 사용에 필요하지 않습니다.
