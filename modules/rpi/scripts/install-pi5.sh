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

if systemctl is-active --quiet mediamtx 2>/dev/null; then
  sudo systemctl stop mediamtx
elif pgrep -x mediamtx >/dev/null 2>&1; then
  echo "ERROR: a manually started MediaMTX process is already running." >&2
  echo "Stop it with Ctrl+C, then run this installer again." >&2
  exit 1
fi

echo "Installing MediaMTX ${MEDIAMTX_VERSION} for Pi IP ${PI_LAN_IP}"
sudo apt-get update
sudo apt-get install -y curl ca-certificates ffmpeg

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

sed "s/__PI_LAN_IP__/${PI_LAN_IP}/g" "${PROJECT_DIR}/config/mediamtx.yml" |
  sudo tee /etc/mediamtx/mediamtx.yml >/dev/null

sudo install -m 0644 "${PROJECT_DIR}/systemd/mediamtx.service" /etc/systemd/system/mediamtx.service
sudo systemctl daemon-reload
sudo systemctl enable --now mediamtx

echo
echo "MediaMTX is installed."
echo "RTSP:   rtsp://${PI_LAN_IP}:8554/camera-01"
echo "SRT:    srt://${PI_LAN_IP}:8890?streamid=publish:camera-01"
echo "WebRTC: http://${PI_LAN_IP}:8889/camera-01"
echo "HLS:    http://${PI_LAN_IP}:8888/camera-01"
echo
sudo systemctl --no-pager --full status mediamtx
