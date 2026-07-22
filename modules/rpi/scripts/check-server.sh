#!/usr/bin/env bash
set -euo pipefail

CAMERA_ID="${1:-camera-01}"
PI_LAN_IP="$(hostname -I | awk '{print $1}')"

if [[ ! "${CAMERA_ID}" =~ ^camera-[0-9]{2}$ ]]; then
  echo "ERROR: camera ID must look like camera-01" >&2
  exit 1
fi

echo "SERVICE"
systemctl --no-pager --full status mediamtx || true

echo
echo "PLAYBACK URLS"
echo "RTSP:   rtsp://${PI_LAN_IP}:8554/${CAMERA_ID}"
echo "WebRTC: http://${PI_LAN_IP}:8889/${CAMERA_ID}"
echo "HLS:    http://${PI_LAN_IP}:8888/${CAMERA_ID}"

echo
echo "ACTIVE STREAMS"
curl --fail --silent http://127.0.0.1:9997/v3/paths/list

echo
echo
echo "LISTENING PORTS"
ss -lntup | grep -E ':(8554|8888|8889|8890|8189)\b' || true

echo
echo "STREAM PROBE (${CAMERA_ID})"
if timeout 10 ffprobe \
  -v error \
  -rtsp_transport tcp \
  -select_streams v:0 \
  -show_entries stream=codec_name,width,height,avg_frame_rate \
  -of default=noprint_wrappers=1 \
  "rtsp://127.0.0.1:8554/${CAMERA_ID}"; then
  echo "RESULT: stream is readable"
else
  echo "RESULT: stream is not currently readable"
fi

echo
echo
echo "RECORDING USAGE"
du -sh /var/lib/mediamtx/recordings

echo
echo "TEMPERATURE"
vcgencmd measure_temp || true
