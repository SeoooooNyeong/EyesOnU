#!/usr/bin/env bash
set -euo pipefail

MEDIA_SERVER_IP="${1:?Usage: ./install-pi4-usb-camera.sh PI5_HOST [camera-04] [video-device] [input-format] [width] [height] [fps] [video-codec]}"
CAMERA_ID="${2:-camera-04}"
VIDEO_DEVICE="${3:-auto}"
INPUT_FORMAT="${4:-mjpeg}"
WIDTH="${5:-1280}"
HEIGHT="${6:-720}"
FPS="${7:-10}"
VIDEO_CODEC="${8:-libx264}"
BITRATE="${BITRATE:-1500k}"
RUN_USER="$(id -un)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ ! "${MEDIA_SERVER_IP}" =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "ERROR: invalid Pi 5 IP or hostname: ${MEDIA_SERVER_IP}" >&2
  exit 1
fi

if [[ ! "${INPUT_FORMAT}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "ERROR: invalid input format: ${INPUT_FORMAT}" >&2
  exit 1
fi

if [[ ! "${WIDTH}" =~ ^[0-9]+$ || ! "${HEIGHT}" =~ ^[0-9]+$ || ! "${FPS}" =~ ^[0-9]+$ ]]; then
  echo "ERROR: width, height and FPS must be integers." >&2
  exit 1
fi

if [[ ! "${VIDEO_CODEC}" =~ ^(auto|libx264|h264_v4l2m2m)$ ]]; then
  echo "ERROR: video codec must be auto, libx264 or h264_v4l2m2m." >&2
  exit 1
fi

if [[ ! "${CAMERA_ID}" =~ ^camera-[0-9]{2}$ ]]; then
  echo "ERROR: camera ID must look like camera-04" >&2
  exit 1
fi

if [[ "${VIDEO_DEVICE}" != "auto" && ! -e "${VIDEO_DEVICE}" ]]; then
  echo "ERROR: video device was not found: ${VIDEO_DEVICE}" >&2
  exit 1
fi

sudo apt-get update
sudo apt-get install -y ffmpeg v4l-utils
sudo usermod -aG video "${RUN_USER}"

sudo install -m 0755 \
  "${PROJECT_DIR}/scripts/publish-rpi4-usb.sh" \
  /usr/local/bin/publish-rpi4-usb-camera

sudo install -d -m 0755 /etc/default
{
  echo "MEDIA_SERVER_HOST=${MEDIA_SERVER_IP}"
  echo "CAMERA_ID=${CAMERA_ID}"
  echo "VIDEO_DEVICE=${VIDEO_DEVICE}"
  echo "INPUT_FORMAT=${INPUT_FORMAT}"
  echo "WIDTH=${WIDTH}"
  echo "HEIGHT=${HEIGHT}"
  echo "FPS=${FPS}"
  echo "BITRATE=${BITRATE}"
  echo "VIDEO_CODEC=${VIDEO_CODEC}"
} | sudo tee /etc/default/usb-camera-publisher >/dev/null

sed "s/__RUN_USER__/${RUN_USER}/g" \
  "${PROJECT_DIR}/systemd/usb-camera-publisher.service" |
  sudo tee /etc/systemd/system/usb-camera-publisher.service >/dev/null

sudo systemctl daemon-reload
sudo systemctl reset-failed usb-camera-publisher 2>/dev/null || true
sudo systemctl enable usb-camera-publisher
sudo systemctl restart usb-camera-publisher

echo
echo "USB camera publisher is installed."
echo "Device: ${VIDEO_DEVICE}"
echo "Target: rtsp://${MEDIA_SERVER_IP}:8554/${CAMERA_ID}"
echo "Video: ${INPUT_FORMAT}, ${WIDTH}x${HEIGHT}, ${FPS} FPS, ${VIDEO_CODEC}"
echo
sudo systemctl --no-pager --full status usb-camera-publisher
