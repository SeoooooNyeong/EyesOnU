#!/usr/bin/env bash
set -euo pipefail

SERVER_IP="${1:?Usage: ./publish-rpi4-usb.sh PI5_IP [camera-04] [video-device]}"
CAMERA_ID="${2:-camera-04}"
VIDEO_DEVICE="${3:-/dev/video0}"
WIDTH="${WIDTH:-1280}"
HEIGHT="${HEIGHT:-720}"
FPS="${FPS:-10}"
BITRATE="${BITRATE:-1500k}"
VIDEO_CODEC="${VIDEO_CODEC:-auto}"

if [[ ! "${CAMERA_ID}" =~ ^camera-[0-9]{2}$ ]]; then
  echo "ERROR: camera ID must look like camera-04" >&2
  exit 1
fi

if [[ ! -e "${VIDEO_DEVICE}" ]]; then
  echo "ERROR: video device was not found: ${VIDEO_DEVICE}" >&2
  echo "Run: v4l2-ctl --list-devices" >&2
  exit 1
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ERROR: ffmpeg was not found. Run: sudo apt install ffmpeg" >&2
  exit 1
fi

TARGET_URL="rtsp://${SERVER_IP}:8554/${CAMERA_ID}"

# Most USB webcams expose MJPEG at 720p. Override INPUT_FORMAT=yuyv422
# when v4l2-ctl reports that MJPEG is unavailable.
INPUT_FORMAT="${INPUT_FORMAT:-mjpeg}"

if [[ "${VIDEO_CODEC}" == "h264_v4l2m2m" ]] ||
  { [[ "${VIDEO_CODEC}" == "auto" ]] && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q 'h264_v4l2m2m'; }; then
  VIDEO_ENCODER=(
    -c:v h264_v4l2m2m
    -b:v "${BITRATE}"
    -g "${FPS}"
    -bf 0
  )
  echo "Using Raspberry Pi H.264 hardware encoder."
else
  VIDEO_ENCODER=(
    -c:v libx264
    -preset ultrafast
    -tune zerolatency
    -profile:v baseline
    -b:v "${BITRATE}"
    -maxrate "${BITRATE}"
    -bufsize 3000k
    -g "${FPS}"
    -keyint_min "${FPS}"
    -bf 0
  )
  echo "Hardware encoder was not found; using libx264."
fi

echo "Publishing ${VIDEO_DEVICE} to ${TARGET_URL}"
echo "Input: ${INPUT_FORMAT}, ${WIDTH}x${HEIGHT}, ${FPS} FPS"

exec ffmpeg \
  -hide_banner \
  -loglevel warning \
  -thread_queue_size 512 \
  -f v4l2 \
  -input_format "${INPUT_FORMAT}" \
  -framerate "${FPS}" \
  -video_size "${WIDTH}x${HEIGHT}" \
  -i "${VIDEO_DEVICE}" \
  -an \
  -pix_fmt yuv420p \
  "${VIDEO_ENCODER[@]}" \
  -f rtsp \
  -rtsp_transport tcp \
  "${TARGET_URL}"
