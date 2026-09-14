# 최초 서버 등록용 기기 ID 확인

현재 배포본에는 기기 ID를 보여주는 화면이 없습니다. 최초 설치에서만 아래 절차로 각 앱이 만든 ID를 확인하여 릴레이의 `auth.json`에 등록합니다. 기존 설치의 ID나 앱 데이터를 새로 만들지 않습니다.

## 1. 두 앱의 로컬 상태 만들기

새로 설치한 앱에서 다음 임시 설정을 저장하고 **시작**합니다. 몇 초 후 **중지**합니다. 서버에 연결하지 못하는 상태가 정상이며, 이 과정에서 기기 ID가 생성됩니다. 실제 릴레이 토큰은 이 단계에 필요 없습니다.

| 설정 | Mac | Android |
|---|---|---|
| 서버 주소 | `https://localhost:8443` | `https://127.0.0.1:8443` |
| 인증서 | 핀을 비움 | 시스템 신뢰 저장소 사용 |
| 임시 토큰 | `setup-pending` | `setup-pending` |
| Cloudflare Access | 끔 | 끔 |

이미 사용 중인 앱은 이 임시 설정을 입력하지 않고, 기존 ID를 읽습니다. ID 확인 후 [서버 설치](deploy/lxc/README.md)와 [실제 연결 설정](../README.md#3-두-앱에-같은-https-주소-입력)을 진행하며 임시 주소와 토큰을 교체합니다.

## 2. Mac ID 읽기

Mac 터미널에서 실행합니다. 상태 파일 전체 대신 ID 한 항목만 출력합니다.

```sh
plutil -extract deviceId raw -o - "$HOME/Library/Application Support/ContinuityBridge/state.json"
```

파일이 없다면 설정 저장 후 시작 단계까지 진행했는지 확인합니다. 앱을 삭제하거나 상태 파일을 직접 편집하지 않습니다.

## 3. Android ID 읽기

컴퓨터에 Android SDK Platform Tools의 `adb`와 Python 3가 필요합니다. 휴대폰의 **개발자 옵션 → USB 디버깅**을 켜 USB로 연결하고, 이 컴퓨터의 디버깅을 허용합니다. PC와 이미 무선 ADB 연결을 했다면 그 연결도 사용할 수 있습니다. 브리지 안의 도우미 페어링과 PC의 ADB 허용은 별개입니다.

[Android 공식 ADB 안내](https://developer.android.com/tools/adb#Enabling)

```sh
adb devices
```

표시된 대상의 일련번호로 `ANDROID_SERIAL`을 바꾸어 실행합니다. 제공 APK는 debug 빌드이므로 `run-as`로 자기 앱의 설정을 읽을 수 있습니다. 이 명령은 설정을 변경하지 않습니다.

```sh
adb -s ANDROID_SERIAL exec-out run-as com.froglike6.continuitybridge \
  cat shared_prefs/bridge_configuration.xml | \
  python3 -c 'import sys, xml.etree.ElementTree as E; v=next((e.text for e in E.parse(sys.stdin).getroot() if e.tag=="string" and e.get("name")=="device_id"), None); assert v, "먼저 앱에서 시작한 뒤 중지하세요"; print(v)'
```

출력은 `android-...` 형태입니다. ID 확인을 마치면 USB 케이블을 빼거나 PC의 무선 ADB 연결을 끊어도 됩니다. 브리지 도우미에 필요한 휴대폰의 무선 디버깅 설정은 별도로 유지합니다.

## 4. 서버에 역할별 등록

릴레이의 `auth.json`에서 `role: "macos"` 항목의 `deviceId`는 Mac ID로, `role: "android"` 항목은 Android ID로 바꿉니다. 각 역할의 랜덤 토큰은 생성된 값을 그대로 사용합니다. `device-android-local` 등의 예시 ID로는 실제 앱의 이벤트와 ACK가 거부됩니다.

운영 중인 서버에서 ID만 수정했다면 서비스를 재시작해야 반영됩니다. ID 불일치를 해결하려고 인증 검사나 TLS 검증을 끄지 않습니다.
