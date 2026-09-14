# Ubuntu/Debian에 릴레이 설치하기

[처음 설치 순서](../../../README.md)와 [기기 ID 확인 안내](../../DEVICE_SETUP.md)를 따라 두 ID를 먼저 준비합니다. 이 안내는 본인이 관리하는 새 LXC/VM과 별도 NPM을 기준으로 합니다. Proxmox를 사용하지 않아도 Linux 서버와 방화벽을 준비하면 같은 릴레이를 실행할 수 있습니다.

예제 서버는 브리지만 실행하는 Ubuntu/Debian LXC이며, 릴레이는
Node.js HTTPS 프로세스를 systemd로 실행한다. 릴레이 LXC에 Docker, NPM 또는
별도의 웹 서버를 설치하지 않는다. 기존 NPM은 다른 LXC/VM/서버에서 실행된다.

```text
Mac / Android
    │ https://bridge.example.com:443
    ▼
Cloudflare Access (선택, 기기별 서비스 토큰)
    ▼
기존 Nginx Proxy Manager
    │ https://릴레이_LXC_사설_IP:8443
    ▼
전용 Ubuntu/Debian LXC
    └─ continuity-bridge.service → Node.js HTTPS 릴레이
```

`bridge.example.com`은 설명용 도메인이다. 실제 설치에는 NPM의 사설 IP,
릴레이 LXC의 사설 IP, 사용할 도메인을 정해야 한다. 앱 주소에는 NPM 도메인만
입력한다. 앱은 설정한 HTTPS 주소로 연결하며 다른 서버 주소로 자동 우회하지 않는다.

## 1. NPM만 릴레이에 접근하도록 제한

서비스를 시작하기 전에 **릴레이 LXC의 Proxmox 방화벽**에 다음 순서로
활성화된 규칙을 추가한다. 호스트 관리 포트용 규칙과 기존 SSH 접근 정책은
보존한다. 이 두 규칙은 기존의 광범위한 ACCEPT 규칙보다 위에 둔다.

| 순서 | 방향 | 동작 | 출발지 | 프로토콜 | 목적지 포트 |
|---|---|---|---|---|---|
| 1 | IN | ACCEPT | NPM 서버 사설 IPv4/32 | TCP | 8443 |
| 2 | IN | DROP | 전체 | TCP | 8443 |

Datacenter, 대상 LXC, 대상 네트워크 인터페이스에서 방화벽이 실제 활성화되어
있어야 한다. NAT가 있으면 릴레이에서 관찰되는 NPM의 실제 출발지 IP를 사용한다.
릴레이 8443을 공유기에서 외부로 포워딩하지 않는다. 외부 앱의 접속 경로는
NPM의 443이다. NPM에 도메인만 등록하는 것으로는 릴레이 직접 접근이 차단되지
않으므로, 아래의 다른 LAN 장치 차단 검사까지 통과해야 한다.

Proxmox의 VM/컨테이너 방화벽과 활성화 위치는
[Proxmox 공식 지원 안내](https://forum.proxmox.com/threads/firewall-management.57884/)를 따른다.

기존 Datacenter 방화벽이 꺼져 있으면 전체 서버에 영향을 줄 수 있는 전역 변경
대신 새 LXC 안의 nftables로 같은 출발지 제한을 적용할 수 있다. 이 경우
`continuity-bridge.service`의 drop-in에 `BindsTo=nftables.service`와
`After=nftables.service`를 설정해 방화벽과 서비스의 시작·중지를 연결한다. 방화벽 규칙 파일 변경 후에는 이미 실행 중인
nftables를 재시작해 새 규칙을 적용하고 릴레이를 다시 시작한다. 단순한
`systemctl enable --now nftables`는 이미 실행 중인 서비스의 규칙을 다시 읽지 않는다.

## 2. 새 LXC에 릴레이 설치

아래는 새 LXC의 최초 설치 절차이며 root 셸에서 실행한다. 소스 저장소 또는
배포 묶음을 풀어 `continuity-bridge/`가 보이는 디렉터리로 이동한다.

Debian 13/systemd 257에서 Proxmox가 nesting 경고를 내고 journald·mount unit이
AppArmor 거부로 실패하면 **새 LXC에만 nesting=1**을 설정하고 재부팅한다.
해당 오류가 없는 환경에 이 설정을 일괄 적용할 필요는 없다.

[Node.js 24](https://nodejs.org/en/download), OpenSSL, curl, systemd가 필요하다. TLS 생성 스크립트는
`openssl`, `shasum`, `rg`를 사용하므로 Debian/Ubuntu의 `openssl`, `perl`,
`ripgrep`도 준비한다. 릴레이 자체에는 설치할 npm 패키지가 없다.

사진 공유에는 LXC 메모리 1 GiB 이상을 배정한다. 서비스의 `MemoryMax`는
512 MiB이다. 8 MiB 이미지 한 장을 처리하는 로컬 검증에서 릴레이 RSS가
약 192 MiB까지 올라가 기존 128 MiB 제한을 초과했다. 운영체제와 동시 전송에
따라 사용량은 달라질 수 있다. 기존 설치는 unit 전체를 덮어쓰기보다
`systemctl edit continuity-bridge.service`에서 `[Service]` 아래
`MemoryMax=512M`을 추가하고, `systemctl daemon-reload`와 서비스 재시작으로
적용한다. 기존 방화벽 의존성과 환경·인증 설정은 유지한다.

```sh
/usr/bin/node --version
command -v openssl shasum rg systemctl
```

unit은 `/usr/bin/node`를 사용한다. Node 설치 위치가 다르면 service 파일의
`ExecStart`를 실제 절대 경로로 바꾼다.

```sh
useradd --system --user-group --home-dir /var/lib/continuity-bridge \
  --no-create-home --shell /usr/sbin/nologin continuity-bridge
install -d -o root -g root -m 0755 /opt/continuity-bridge/relay
cp -R continuity-bridge/relay/src /opt/continuity-bridge/relay/
chown -R root:root /opt/continuity-bridge
install -d -o root -g continuity-bridge -m 0750 /etc/continuity-bridge

./continuity-bridge/relay/scripts/generate-local-tls.sh continuity-bridge/runtime/lxc-tls
./continuity-bridge/relay/scripts/generate-local-auth.sh continuity-bridge/runtime/lxc-auth
```

생성된 `continuity-bridge/runtime/lxc-auth/auth.json`의 Android/macOS 역할별
`deviceId`를 앞에서 확인한 앱의 ID와 일치시킨다. 해당 역할의 토큰을 각 앱에
입력한다. 이 파일은 토큰을 포함하므로 화면 공유나 로그에 출력하지 않는다.
인증 생성 스크립트는 재실행하면 새 토큰을 만들기 때문에 최초 설치에서만 실행한다.

```sh
install -o root -g continuity-bridge -m 0640 \
  continuity-bridge/runtime/lxc-auth/auth.json /etc/continuity-bridge/auth.json
install -o root -g continuity-bridge -m 0640 \
  continuity-bridge/runtime/lxc-tls/server-key.pem /etc/continuity-bridge/server-key.pem
install -o root -g root -m 0644 \
  continuity-bridge/runtime/lxc-tls/server.pem /etc/continuity-bridge/server.pem
install -o root -g root -m 0644 \
  continuity-bridge/runtime/lxc-tls/ca.pem /etc/continuity-bridge/ca.pem
install -o root -g continuity-bridge -m 0640 \
  continuity-bridge/deploy/lxc/relay.env.example /etc/continuity-bridge/relay.env
install -o root -g root -m 0644 \
  continuity-bridge/deploy/lxc/continuity-bridge.service /etc/systemd/system/continuity-bridge.service
```

`/etc/continuity-bridge/relay.env`의 `RELAY_HOST`를 **릴레이 LXC의 사설 IPv4**로
수정한다. 기본 예제는 안전하게 `127.0.0.1`에만 바인딩하므로 수정 전에는
별도 NPM 서버가 접속할 수 없다. `0.0.0.0` 또는 NPM의 IP를 넣지 않는다. CA 개인키는 bootstrap 디렉터리에만
보존하며 실행 서비스와 NPM으로 복사하지 않는다.

방화벽 설정을 완료한 뒤 다음을 실행한다.

```sh
systemd-analyze verify /etc/systemd/system/continuity-bridge.service
systemctl daemon-reload
systemctl enable --now continuity-bridge.service
systemctl status continuity-bridge.service --no-pager
```

상태 파일은 systemd가 소유권을 설정하는 `/var/lib/continuity-bridge/state.json`에
저장한다. 서비스 사용자만 해당 디렉터리에 접근할 수 있다.

## 3. 기존 NPM에 Proxy Host 등록

| 항목 | 값 |
|---|---|
| Domain Names | 사용할 실제 도메인 |
| Scheme | **https** |
| Forward Hostname / IP | 릴레이 LXC 사설 IPv4 |
| Forward Port | **8443** |
| Cache Assets | OFF |
| Websockets Support | OFF, 현재 프로토콜은 HTTPS 25초 long-poll |
| SSL | 해당 도메인의 유효한 공인 인증서, Force SSL ON |
| Access List | 추가 Basic Auth 사용 안 함; 브리지의 Bearer 토큰 인증 유지 |

릴레이에서 생성한 **공개 CA 파일만** NPM 서버로 복사하여 nginx가 읽을 수 있는
`/etc/nginx/continuity-bridge-ca.pem`에 둔다. NPM 서버에서 일반적인 설치 명령은
`install -o root -g root -m 0644 ca.pem /etc/nginx/continuity-bridge-ca.pem`이다.
개인키와 `auth.json`은 NPM에 필요하지 않다.

Proxy Host의 Advanced 칸에 같은 폴더의 `npm-advanced.conf`를 넣는다.
`proxy_ssl_name localhost`는 생성한 내부 인증서의 DNS SAN을 검증하기 위한
이름이며, **접속 대상은 Forward Hostname에 입력한 LXC 사설 IP**다.
TLS 검증은 켜 둔다. 별도의 내부 인증서를 쓰면 그 인증서의 SAN과 CA에 맞춘다.
저장 후 NPM 서버의 `nginx -t`와 Proxy Host의 온라인 상태를 확인한다.

요청 본문 한도는 `12m`이다. 최대 8 MiB 사진의 Base64와 JSON 필드를 포함한다.
기존 NPM 설정에 `client_max_body_size 2m;`을 넣었다면 사진 업데이트 때
`client_max_body_size 12m;`으로 변경한다. 기존 내부 TLS 및 Access 설정과 별개인 항목이다.
API 캐시는 꺼 두고 25초 long-poll보다 긴 60초 읽기 제한을 사용한다.
[nginx 프록시 문서](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)와
[NPM의 인증 헤더 안내](https://nginxproxymanager.com/faq/#when-adding-username-and-password-access-control-to-a-proxy-host-i-can-no-longer-login-into-the-app)를 참고한다.

## 4. 앱 연결

- 두 앱의 서버 주소: `https://실제_NPM_도메인` (추가 경로 없이 입력)
- Mac: 인증서 핀을 비워 시스템 신뢰 저장소 사용
- Android: `시스템 신뢰 저장소 사용` 선택
- 양쪽의 기기 ID와 역할별 토큰은 릴레이 `auth.json`과 일치

앱은 NPM의 공인 인증서를 검증한다. NPM은 별도로 릴레이의 내부 인증서를
검증한다. 이 구성에는 로컬 CA를 앱에 다시 넣거나 앱을 재빌드할 필요가 없다.

## 5. 실제 배포 완료 판정

다음 네 가지를 실제 환경에서 모두 확인한다.

1. NPM 서버에서만 LXC 8443에 접근 가능. NPM 서버에서 아래 명령으로 CA와
   서버 이름 검증까지 성공해야 한다. `RELAY_LXC_PRIVATE_IPV4`는 실제 IP로 바꾼다.

   ```sh
   curl --fail --cacert /etc/nginx/continuity-bridge-ca.pem \
     --connect-to localhost:8443:RELAY_LXC_PRIVATE_IPV4:8443 \
     https://localhost:8443/v1/ready
   ```

2. 다른 LAN 장치에서 릴레이 사설 IP의 8443에 TCP 연결이 차단된다.
3. 두 앱이 사용하는 NPM 도메인에서 `https://실제_NPM_도메인/v1/ready`가 정상
   JSON을 반환하며, 토큰 없는 `/v1/events?after=0&waitMs=0`는 401을 반환한다.
4. 앱 양방향 클립보드와 알림을 NPM 경유로 확인하고, NPM 또는 릴레이 중단 시
   직접 연결로 우회하지 않으며 재시작 후 연결이 복구된다.

이 배포 묶음은 실제 서버에 자동 접속하거나 LXC, 방화벽, NPM을 변경하지 않는다.
현재 Mac에서의 네이티브 Node 실행과 실제 LXC/systemd/NPM 검증은 구분해서 기록한다.

## 6. Cloudflare Access로 보호하는 경우

공개 도메인에 Access 애플리케이션을 지정하고 기기마다 별도 Service Token을 발급한다. 정책은 Service Auth로 만들고 허용할 기기 토큰을 명시한다. 두 앱의 `Cloudflare Access 사용`을 켜 Client ID/Secret을 저장하며, 릴레이 역할 토큰도 함께 유지한다. 앱은 Access 로그인 페이지를 따라가지 않으므로 브라우저 이메일 로그인만 허용하는 정책으로는 백그라운드 연결이 되지 않는다.

Access가 앞에 있으면 토큰 없는 공개 `/v1/ready`나 `/v1/events`는 302 또는 403으로 차단될 수 있다. 유효한 Access 자격증명으로 내부 API까지 도달한 뒤 릴레이 Bearer 인증을 따로 검사한다. 실제 연결 시험은 두 앱의 publish/fetch/ACK와 25초 long-poll로 확인한다. Secret은 APK·앱 번들·환경 예제·Git에 넣지 않는다.

개인별 도메인·Access 애플리케이션·서비스 토큰을 새로 준비한다. 다른 설치의 토큰이나 개발용 인증서 지문을 복사하지 않는다.

근거: [Cloudflare 서비스 토큰](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/).
