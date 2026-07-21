#!/usr/bin/env bash
set -euo pipefail

MEDIA_SERVER_IP="${1:?Usage: ./install-pi4-usb-camera.sh PI5_IP [camera-04] [video-device]}"
CAMERA_ID="${2:-camera-04}"
VIDEO_DEVICE="${3:-/dev/video0}"
RUN_USER="$(id -un)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ ! "${MEDIA_SERVER_IP}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: invalid Pi 5 IP: ${MEDIA_SERVER_IP}" >&2
  exit 1
fi

if [[ ! "${CAMERA_ID}" =~ ^camera-[0-9]{2}$ ]]; then
  echo "ERROR: camera ID must look like camera-04" >&2
  exit 1
fi

if [[ ! -e "${VIDEO_DEVICE}" ]]; then
  echo "ERROR: video device was not found: ${VIDEO_DEVICE}" >&2
  exit 1
fi

sudo apt-get update
sudo apt-get install -y ffmpeg v4l-utils
sudo usermod -aG video "${RUN_USER}"

sudo install -m 0755 \
  "${PROJECT_DIR}/scripts/publish-rpi4-usb.sh" \
  /usr/local/bin/publish-rpi4-usb-camera

sed \
  -e "s/__RUN_USER__/${RUN_USER}/g" \
  -e "s/__MEDIA_SERVER_IP__/${MEDIA_SERVER_IP}/g" \
  -e "s/__CAMERA_ID__/${CAMERA_ID}/g" \
  -e "s|__VIDEO_DEVICE__|${VIDEO_DEVICE}|g" \
  "${PROJECT_DIR}/systemd/usb-camera-publisher.service" |
  sudo tee /etc/systemd/system/usb-camera-publisher.service >/dev/null

sudo systemctl daemon-reload
sudo systemctl enable --now usb-camera-publisher

echo
echo "USB camera publisher is installed."
echo "Device: ${VIDEO_DEVICE}"
echo "Target: rtsp://${MEDIA_SERVER_IP}:8554/${CAMERA_ID}"
echo
sudo systemctl --no-pager --full status usb-camera-publisher
