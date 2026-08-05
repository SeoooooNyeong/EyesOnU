#!/usr/bin/env bash
set -euo pipefail

MEDIAMTX_VERSION="1.19.2"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PI_LAN_IP="${1:-}"

if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "ERROR: Raspberry Pi OS 64-bit (aarch64) is required." >&2
  exit 1
fi

if [[ -z "${PI_LAN_IP}" ]]; then
  PI_LAN_IP="$(hostname -I | awk '{print $1}')"
fi

if [[ ! "${PI_LAN_IP}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: invalid Pi LAN IP: ${PI_LAN_IP}" >&2
  echo "Usage: ./scripts/install-pi5.sh 192.168.0.50" >&2
  exit 1
fi

if systemctl is-active --quiet mediamtx-uploader 2>/dev/null; then
  sudo systemctl stop mediamtx-uploader
fi

if systemctl is-active --quiet mediamtx-heartbeat 2>/dev/null; then
  sudo systemctl stop mediamtx-heartbeat
fi

if systemctl is-active --quiet mediamtx 2>/dev/null; then
  sudo systemctl stop mediamtx
elif pgrep -x mediamtx >/dev/null 2>&1; then
  echo "ERROR: a manually started MediaMTX process is already running." >&2
  echo "Stop it with Ctrl+C, then run this installer again." >&2
  exit 1
fi

echo "Installing MediaMTX ${MEDIAMTX_VERSION} for Pi IP ${PI_LAN_IP}"
sudo apt-get update
sudo apt-get install -y curl ca-certificates ffmpeg jq

TASK_TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TASK_TMP_DIR}"' EXIT

ARCHIVE="${TASK_TMP_DIR}/mediamtx.tar.gz"
DOWNLOAD_URL="https://github.com/bluenviron/mediamtx/releases/download/v${MEDIAMTX_VERSION}/mediamtx_v${MEDIAMTX_VERSION}_linux_arm64.tar.gz"

curl --fail --location "${DOWNLOAD_URL}" --output "${ARCHIVE}"
tar -xzf "${ARCHIVE}" -C "${TASK_TMP_DIR}"

if ! id mediamtx >/dev/null 2>&1; then
  sudo useradd --system --home-dir /var/lib/mediamtx --shell /usr/sbin/nologin mediamtx
fi

sudo install -m 0755 "${TASK_TMP_DIR}/mediamtx" /usr/local/bin/mediamtx
sudo install -d -m 0755 /etc/mediamtx
sudo install -d -o mediamtx -g mediamtx -m 0755 /var/lib/mediamtx
sudo install -d -o mediamtx -g mediamtx -m 0755 /var/lib/mediamtx/recordings
sudo install -d -o mediamtx -g mediamtx -m 0755 /var/lib/mediamtx/upload-queue
sudo install -d -m 0755 /usr/local/lib/mediamtx

sudo install -m 0644 "${PROJECT_DIR}/config/mediamtx.yml" /etc/mediamtx/mediamtx.yml
sudo install -m 0755 "${PROJECT_DIR}/scripts/queue-recording-upload.sh" /usr/local/lib/mediamtx/queue-recording-upload.sh
sudo install -m 0755 "${PROJECT_DIR}/scripts/upload-recordings.sh" /usr/local/lib/mediamtx/upload-recordings.sh
sudo install -m 0755 "${PROJECT_DIR}/scripts/report-camera-heartbeats.sh" /usr/local/lib/mediamtx/report-camera-heartbeats.sh

if [[ ! -f /etc/default/mediamtx-uploader ]]; then
  sudo install -o root -g mediamtx -m 0640 \
    "${PROJECT_DIR}/config/mediamtx-uploader.env.example" \
    /etc/default/mediamtx-uploader
fi

sudo install -m 0644 "${PROJECT_DIR}/systemd/mediamtx.service" /etc/systemd/system/mediamtx.service
sudo install -m 0644 "${PROJECT_DIR}/systemd/mediamtx-uploader.service" /etc/systemd/system/mediamtx-uploader.service
sudo install -m 0644 "${PROJECT_DIR}/systemd/mediamtx-heartbeat.service" /etc/systemd/system/mediamtx-heartbeat.service

if command -v ufw >/dev/null 2>&1 && sudo ufw status | grep -q '^Status: active'; then
  sudo ufw allow 8554/tcp comment 'MediaMTX RTSP'
  sudo ufw allow 8888/tcp comment 'MediaMTX HLS'
  sudo ufw allow 8890/udp comment 'MediaMTX SRT'
fi

sudo systemctl daemon-reload
sudo systemctl enable mediamtx mediamtx-uploader mediamtx-heartbeat
sudo systemctl restart mediamtx
sudo systemctl restart mediamtx-uploader
sudo systemctl restart mediamtx-heartbeat

echo
echo "MediaMTX is installed."
echo "RTSP:   rtsp://${PI_LAN_IP}:8554/camera-01"
echo "SRT:    srt://${PI_LAN_IP}:8890?streamid=publish:camera-01"
echo "HLS:    http://${PI_LAN_IP}:8888/camera-01"
echo "Recording uploader config: /etc/default/mediamtx-uploader"
echo "Camera heartbeat config: /etc/default/mediamtx-uploader"
echo
sudo systemctl --no-pager --full status mediamtx
sudo systemctl --no-pager --full status mediamtx-uploader
sudo systemctl --no-pager --full status mediamtx-heartbeat
