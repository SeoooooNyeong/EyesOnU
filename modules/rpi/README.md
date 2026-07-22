# Raspberry Pi 5 Media Server

스마트폰의 Larix Broadcaster와 Raspberry Pi 4의 USB 웹캠 영상을 Raspberry Pi 5의 MediaMTX로 수집·중계·녹화하는 구성이다.

```text
스마트폰 Larix ── RTSP/TCP 또는 SRT ─┐
                                     ├─> Raspberry Pi 5 / MediaMTX
Pi 4 USB 웹캠 ── RTSP/TCP ──────────┘       ├─ RTSP → 영상 처리 및 VLC/ffplay
                                             ├─ WebRTC/HLS → 브라우저
                                             └─ fMP4 → 카메라별 1분 녹화
```

MediaMTX는 입력 영상을 다시 인코딩하지 않는다. 스마트폰과 Pi 4가 H.264 영상을 발행하고 Pi 5는 동일 스트림을 중계하면서 녹화한다.

## 카메라 경로

카메라는 장비 IP가 아니라 MediaMTX path로 식별한다.

```text
camera-01: 스마트폰 Larix
camera-04: Raspberry Pi 4 USB 웹캠
```

같은 경로에는 송출자 한 대만 연결할 수 있다.

## 1. 네트워크 확인

스마트폰, Pi 4, Pi 5는 서로의 IP와 포트로 통신할 수 있어야 한다. 첫 테스트에서는 세 장비를 같은 공유기나 노트북 모바일 핫스팟에 연결한다.

Pi 5 IP 확인:

```bash
hostname -I
```

Pi 4에서 Pi 5의 RTSP 포트 확인:

```bash
nc -vz PI5_IP 8554
```

## 2. Pi 5 MediaMTX 설치

프로젝트를 Pi 5 홈 디렉터리에 복사한 뒤 실행한다.

```bash
cd ~/raspberry-pi-media-server
chmod +x scripts/*.sh
./scripts/install-pi5.sh PI5_IP
```

설치 스크립트는 MediaMTX ARM64, FFmpeg, 전용 사용자, 녹화 디렉터리와 systemd 서비스를 설치한다.
UFW가 활성화된 환경에서는 RTSP, HLS, WebRTC, SRT에 필요한 포트도 함께 허용한다.

서비스 확인:

```bash
sudo systemctl status mediamtx
sudo journalctl -u mediamtx -f
```

재시작:

```bash
sudo systemctl restart mediamtx
```

## 3. 스마트폰 Larix 송출

Larix Broadcaster에서 새 Connection을 만들고 다음 주소를 입력한다.

```text
rtsp://PI5_IP:8554/camera-01
```

권장 설정:

```text
Codec: H.264/AVC
Resolution: 1280x720
FPS: 10 또는 15
Bitrate: 1000~1500 Kbps
Keyframe interval: 1초
Audio: 사용하지 않으면 비활성화
```

Wi-Fi에서 RTSP 끊김이 반복될 때는 SRT 연결을 사용할 수 있다.

```text
srt://PI5_IP:8890?streamid=publish:camera-01&pkt_size=1316
```

## 4. Pi 4 USB 웹캠 확인

Pi 4에 필요한 패키지를 설치한다.

```bash
sudo apt update
sudo apt install -y ffmpeg v4l-utils netcat-openbsd
```

USB 웹캠과 지원 포맷을 확인한다.

```bash
v4l2-ctl --list-devices
v4l2-ctl -d /dev/video0 --list-formats-ext
```

`/dev/video0`이 실제 Video Capture 장치가 아니면 `/dev/video1` 등 출력에 표시된 장치를 사용한다.

## 5. Pi 4 USB 웹캠 수동 송출

프로젝트를 Pi 4 홈 디렉터리에 복사한 뒤 실행한다.

```bash
cd ~/raspberry-pi-media-server
chmod +x scripts/*.sh
./scripts/publish-rpi4-usb.sh PI5_IP camera-04 auto
```

MJPEG 또는 하드웨어 인코더 호환 문제가 있으면 보수적인 설정으로 실행한다.

```bash
INPUT_FORMAT=yuyv422 \
WIDTH=640 \
HEIGHT=480 \
FPS=10 \
VIDEO_CODEC=libx264 \
./scripts/publish-rpi4-usb.sh PI5_IP camera-04 auto
```

## 6. Pi 4 자동 송출 등록

수동 송출을 먼저 확인한 뒤 systemd 서비스로 설치한다.

```bash
./scripts/install-pi4-usb-camera.sh PI5_IP camera-04 auto
```

수동 테스트에서 YUYV와 libx264 설정으로 성공했다면 같은 설정을 설치 명령에 전달한다. 이 값들은 `/etc/default/usb-camera-publisher`에 저장되어 재부팅 후에도 유지된다.

```bash
./scripts/install-pi4-usb-camera.sh \
  PI5_IP camera-04 auto \
  yuyv422 640 480 10 libx264
```

서비스 확인:

```bash
sudo systemctl status usb-camera-publisher
sudo journalctl -u usb-camera-publisher -f
```

Pi 5나 네트워크가 아직 준비되지 않았거나 USB 웹캠 연결이 늦어져도 서비스는 5초 간격으로 계속 재시도한다.

## 7. 재부팅 및 자동 복구 확인

Pi 5:

```bash
sudo systemctl is-enabled mediamtx
sudo reboot
```

재접속 후:

```bash
sudo systemctl is-active mediamtx
```

Pi 4:

```bash
sudo systemctl is-enabled usb-camera-publisher
sudo reboot
```

재접속 후:

```bash
sudo systemctl is-active usb-camera-publisher
sudo journalctl -u usb-camera-publisher -n 100 --no-pager
```

두 `is-enabled` 결과가 `enabled`, 재부팅 후 두 `is-active` 결과가 `active`여야 한다. Wi-Fi 연결 프로필도 자동 접속 상태여야 한다.

```bash
nmcli -f NAME,AUTOCONNECT connection show
```

필요하면 사용하는 Wi-Fi 프로필의 자동 접속을 활성화한다.

```bash
sudo nmcli connection modify "WIFI_PROFILE_NAME" connection.autoconnect yes connection.autoconnect-retries 0
```

## 8. 스트림 확인

Pi 5 로그에서 다음 메시지를 확인한다.

```text
is publishing to path 'camera-01'
is publishing to path 'camera-04'
```

RTSP 재생:

```bash
ffplay -rtsp_transport tcp -fflags +nobuffer -flags low_delay -framedrop rtsp://PI5_IP:8554/camera-04
```

브라우저 재생:

```text
WebRTC: http://PI5_IP:8889/camera-04
HLS:    http://PI5_IP:8888/camera-04
```

Pi 5 종합 점검:

```bash
./scripts/check-server.sh camera-04
curl http://127.0.0.1:9997/v3/paths/list
```

## 9. 녹화 확인

MediaMTX는 수신 스트림을 카메라별 1분 단위 fMP4로 저장하며 생성 후 24시간이 지난 파일을 삭제하도록 설정되어 있다.

```bash
find /var/lib/mediamtx/recordings -type f -name '*.mp4' -ls
```

저장 경로 예시:

```text
/var/lib/mediamtx/recordings/camera-04/2026/07/21/14-30-00-000000.mp4
```

## 사용 포트

| 포트 | 용도 |
|---|---|
| `8554/tcp` | RTSP 발행 및 구독 |
| `8890/udp` | Larix SRT 발행 |
| `8889/tcp` | WebRTC 페이지 및 시그널링 |
| `8189/udp`, `8189/tcp` | WebRTC 미디어(UDP 우선, TCP 예비) |
| `8888/tcp` | HLS 재생 |

관리 API `9997/tcp`와 metrics `9998/tcp`는 Pi 5의 localhost에서만 접근할 수 있다.
