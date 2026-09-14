# 기존 설치 업데이트

새 설치는 [README](../README.md)를 따릅니다. 업데이트할 때는 **릴레이 → Mac 앱 → Android 앱** 순서로 진행하고, 교체하는 동안 양쪽 앱에서 공유를 중지합니다. 이전 텍스트 전용 버전은 사진 이벤트를 처리하지 못합니다.

## 설정과 데이터 보존

- 앱을 삭제하거나 데이터를 초기화하지 않습니다.
- 릴레이의 `/etc/continuity-bridge/`와 상태 파일, 방화벽 규칙을 보존합니다.
- 기존 서버에서 인증 토큰 생성기를 다시 실행하면 토큰이 바뀝니다. 업데이트에 새 토큰이나 새 인증서가 필요한 것은 아닙니다.
- 사진 상태를 저장한 뒤 구버전 코드만 덮어쓰면 복구에 실패할 수 있습니다. 되돌릴 때는 코드와 당시 상태 백업을 함께 검토합니다. 상태를 되돌리면 백업 이후 대기 이벤트가 사라질 수 있습니다.

## 프록시와 메모리

NPM Advanced의 기존 본문 크기 설정을 `client_max_body_size 12m;`으로 바꿉니다. 중복으로 추가하지 않습니다. 8 MiB 사진의 Base64와 JSON 크기를 포함하는 한도입니다. 도메인, 기존 Access 정책, 인증서 설정은 유지합니다.

릴레이 서버에 메모리 1 GiB 이상을 배정하고, 서비스의 `MemoryMax`는 `512M`으로 설정합니다. [현재 서비스 예제](deploy/lxc/continuity-bridge.service)를 참고하되 기존 방화벽 의존성과 환경 설정을 덮어쓰지 않습니다.

## Linux 릴레이

본인 서버에 이 저장소의 새 소스를 준비합니다. 아래는 [배포 안내](deploy/lxc/README.md)의 경로로 설치한 서버에서 저장소 루트의 root 셸로 실행하는 예시입니다.

```sh
set -eu
upgrade_stamp=$(date +%Y%m%d-%H%M%S)
upgrade_next=/opt/continuity-bridge/relay/src.next-$upgrade_stamp
upgrade_backup=/opt/continuity-bridge/relay/src.before-$upgrade_stamp
mkdir "$upgrade_next"
cp -R continuity-bridge/relay/src/. "$upgrade_next/"
chown -R root:root "$upgrade_next"
for module in "$upgrade_next"/*.mjs; do /usr/bin/node --check "$module"; done

systemctl stop continuity-bridge.service
if test -f /var/lib/continuity-bridge/state.json; then
    cp -a /var/lib/continuity-bridge/state.json "/var/lib/continuity-bridge/state.before-$upgrade_stamp.json"
fi
mv /opt/continuity-bridge/relay/src "$upgrade_backup"
mv "$upgrade_next" /opt/continuity-bridge/relay/src
systemctl edit continuity-bridge.service
```

편집기에 다음 메모리 설정을 추가하고 저장합니다. 기존 drop-in 내용은 유지합니다.

```ini
[Service]
MemoryMax=512M
```

```sh
systemctl daemon-reload
systemctl start continuity-bridge.service
systemctl is-active continuity-bridge.service
systemctl show continuity-bridge.service -p MemoryMax
```

이어서 자신의 프록시 주소에서 readiness와 앱 연결을 확인합니다. 실패하면 `journalctl -u continuity-bridge.service -n 50 --no-pager`로 원인을 확인합니다.

## Mac과 Android

[설치 파일 목록](../outputs/README.md)에서 현재 파일과 체크섬을 받습니다.

- Mac: 기존 앱을 종료한 뒤 새 `ContinuityBridge.app`으로 교체합니다. UserDefaults와 Keychain은 지우지 않습니다. ad-hoc 서명이 달라지면 키체인 접근 승인을 다시 요청할 수 있습니다.
- Android: `continuity-bridge-android-embedded-debug.apk`를 기존 앱 위에 업데이트합니다. 기존 앱과 같은 서명이 필요합니다. 직접 만든 다른 키로 서명했다면 기존 앱을 삭제하기 전에 설정·복구 방법을 먼저 확인합니다.
- 이전 외부 Shizuku 방식에서 바꾸는 경우 [내장 도우미 최초 연결](android/EMBEDDED_HELPER.md)을 한 번 진행합니다. 외부 Shizuku의 기존 페어링과 내장 도우미의 페어링은 별개입니다.
- 주소·역할 토큰·Access 설정이 유지됐는지 확인하고 양쪽 앱에서 시작합니다.

짧은 텍스트를 양방향으로 복사하고 허용한 앱의 새 알림을 확인합니다. 사진 공유의 현재 미해결 항목은 [검증 기록](../HANDOFF.md)에 있습니다.
