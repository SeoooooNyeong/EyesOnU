#!/usr/bin/env bash
set -uo pipefail

INTERVAL_SECONDS="${CAMERA_HEARTBEAT_INTERVAL_SECONDS:-10}"
CAMERA_CODES="${CAMERA_HEARTBEAT_CAMERA_CODES:-camera-01 camera-02 camera-03 camera-04}"
MEDIAMTX_API_URL="${MEDIAMTX_API_URL:-http://127.0.0.1:9997}"
MEDIAMTX_API_USER="${MEDIAMTX_API_USER:-localadmin}"
MEDIAMTX_API_PASSWORD="${MEDIAMTX_API_PASSWORD:-}"

if [[ "${CENTRAL_API_ENABLED:-false}" != "true" ]]; then
  echo "Camera heartbeat reporter is disabled (CENTRAL_API_ENABLED is not true)."
  exit 0
fi

if [[ -z "${CENTRAL_API_BASE_URL:-}" || -z "${CENTRAL_API_DEVICE_KEY:-}" ]]; then
  echo "ERROR: CENTRAL_API_BASE_URL and CENTRAL_API_DEVICE_KEY are required." >&2
  exit 1
fi

if [[ ! "${INTERVAL_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: CAMERA_HEARTBEAT_INTERVAL_SECONDS must be a positive integer." >&2
  exit 1
fi

read -r -a CAMERAS <<< "${CAMERA_CODES}"

send_heartbeat() {
  local camera_code="$1"
  local occurred_at payload endpoint response http_status response_body

  occurred_at="$(date -u +"%Y-%m-%dT%H:%M:%S.000000Z")"
  endpoint="${CENTRAL_API_BASE_URL%/}/api/v1/device/cameras/${camera_code}/heartbeat"

  payload="$(jq -nc \
    --arg occurredAt "${occurred_at}" \
    '{occurredAt: $occurredAt, status: "ONLINE", detail: null}')"

  response="$(curl --noproxy "*" \
    --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time 10 \
    --request POST \
    --header "Accept: application/json" \
    --header "Content-Type: application/json" \
    --header "X-Device-Key: ${CENTRAL_API_DEVICE_KEY}" \
    --data-binary "${payload}" \
    --write-out $'\n%{http_code}' \
    "${endpoint}" 2>&1)"
  http_status="${response##*$'\n'}"
  response_body="${response%$'\n'*}"

  if [[ "${http_status}" =~ ^2[0-9][0-9]$ ]]; then
    echo "heartbeat sent: camera=${camera_code} status=ONLINE http=${http_status}"
  else
    echo "ERROR: heartbeat rejected: camera=${camera_code} status=ONLINE http=${http_status} response=${response_body}" >&2
  fi
}

while true; do
  curl_args=(
    --noproxy "*"
    --silent
    --show-error
    --fail
    --connect-timeout 3
    --max-time 5
  )

  if [[ -n "${MEDIAMTX_API_PASSWORD}" ]]; then
    curl_args+=(--user "${MEDIAMTX_API_USER}:${MEDIAMTX_API_PASSWORD}")
  fi

  if paths_json="$(curl "${curl_args[@]}" "${MEDIAMTX_API_URL%/}/v3/paths/list" 2>&1)"; then
    for camera_code in "${CAMERAS[@]}"; do
      if jq -e --arg camera "${camera_code}" \
        '.items[]? | select(.name == $camera and .ready == true)' \
        >/dev/null <<< "${paths_json}"; then
        send_heartbeat "${camera_code}"
      fi
    done
  else
    echo "ERROR: failed to query MediaMTX paths: ${paths_json}" >&2
  fi

  sleep "${INTERVAL_SECONDS}"
done
