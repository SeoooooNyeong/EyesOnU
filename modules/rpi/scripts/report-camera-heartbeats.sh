#!/usr/bin/env bash
set -uo pipefail

INTERVAL_SECONDS="${CAMERA_HEARTBEAT_INTERVAL_SECONDS:-10}"
FAILURE_THRESHOLD="${CAMERA_HEARTBEAT_FAILURE_THRESHOLD:-2}"
RECOVERY_THRESHOLD="${CAMERA_HEARTBEAT_RECOVERY_THRESHOLD:-1}"
CAMERA_CODES="${CAMERA_HEARTBEAT_CAMERA_CODES:-camera-01 camera-02 camera-03 camera-04}"

MEDIAMTX_API_URL="${MEDIAMTX_API_URL:-http://127.0.0.1:9997}"
MEDIAMTX_API_USER="${MEDIAMTX_API_USER:-localadmin}"
MEDIAMTX_API_PASSWORD="${MEDIAMTX_API_PASSWORD:-}"

HLS_HEALTH_BASE_URL="${HLS_HEALTH_BASE_URL:-http://127.0.0.1:8888}"
HLS_HEALTH_USER="${HLS_HEALTH_USER:-webviewer}"
HLS_HEALTH_PASSWORD="${HLS_HEALTH_PASSWORD:-}"
HLS_HEALTH_TIMEOUT_SECONDS="${HLS_HEALTH_TIMEOUT_SECONDS:-5}"

if [[ "${CENTRAL_API_ENABLED:-false}" != "true" ]]; then
  echo "Camera heartbeat reporter is disabled (CENTRAL_API_ENABLED is not true)."
  exit 0
fi

for required_name in CENTRAL_API_BASE_URL CENTRAL_API_DEVICE_KEY; do
  if [[ -z "${!required_name:-}" ]]; then
    echo "ERROR: ${required_name} is required." >&2
    exit 1
  fi
done

for integer_name in \
  INTERVAL_SECONDS \
  FAILURE_THRESHOLD \
  RECOVERY_THRESHOLD \
  HLS_HEALTH_TIMEOUT_SECONDS; do
  if [[ ! "${!integer_name}" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: ${integer_name} must be a positive integer." >&2
    exit 1
  fi
done

read -r -a CAMERAS <<< "${CAMERA_CODES}"
if (( ${#CAMERAS[@]} == 0 )); then
  echo "ERROR: CAMERA_HEARTBEAT_CAMERA_CODES must not be empty." >&2
  exit 1
fi

for camera_code in "${CAMERAS[@]}"; do
  if [[ ! "${camera_code}" =~ ^camera-[0-9]{2}$ ]]; then
    echo "ERROR: invalid camera code: ${camera_code}" >&2
    exit 1
  fi
done

declare -A CURRENT_STATE
declare -A INCIDENT_DETAIL
declare -A PENDING_DETAIL
declare -A FAILURE_COUNT
declare -A RECOVERY_COUNT
declare -A LAST_HLS_MARKER

media_api_curl_args=(
  --noproxy "*"
  --silent
  --show-error
  --fail
  --connect-timeout 3
  --max-time 5
)

if [[ -n "${MEDIAMTX_API_PASSWORD}" ]]; then
  media_api_curl_args+=(--user "${MEDIAMTX_API_USER}:${MEDIAMTX_API_PASSWORD}")
fi

hls_curl_args=(
  --noproxy "*"
  --location
  --silent
  --show-error
  --fail
  --connect-timeout "${HLS_HEALTH_TIMEOUT_SECONDS}"
  --max-time "${HLS_HEALTH_TIMEOUT_SECONDS}"
)

if [[ -n "${HLS_HEALTH_PASSWORD}" ]]; then
  hls_curl_args+=(--user "${HLS_HEALTH_USER}:${HLS_HEALTH_PASSWORD}")
fi

send_heartbeat() {
  local camera_code="$1"
  local status="$2"
  local detail="$3"
  local occurred_at endpoint payload response http_status response_body

  occurred_at="$(date -u +"%Y-%m-%dT%H:%M:%S.000000Z")"
  endpoint="${CENTRAL_API_BASE_URL%/}/api/v1/device/cameras/${camera_code}/heartbeat"

  if [[ "${status}" == "ONLINE" ]]; then
    payload="$(jq -nc \
      --arg occurredAt "${occurred_at}" \
      '{occurredAt: $occurredAt, status: "ONLINE", detail: null}')"
  else
    payload="$(jq -nc \
      --arg occurredAt "${occurred_at}" \
      --arg detail "${detail}" \
      '{occurredAt: $occurredAt, status: "ERROR", detail: $detail}')"
  fi

  response="$(curl \
    --noproxy "*" \
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
    echo "heartbeat sent: camera=${camera_code} status=${status} detail=${detail:-null} http=${http_status}"
    return 0
  fi

  echo "ERROR: heartbeat rejected: camera=${camera_code} status=${status} http=${http_status} response=${response_body}" >&2
  return 1
}

extract_latest_hls_uri() {
  awk '
    /^#EXT-X-PART:/ {
      if (match($0, /URI="[^"]+"/)) {
        part = substr($0, RSTART + 5, RLENGTH - 6)
      }
    }
    /^[^#]/ {
      segment = $0
      sub(/\r$/, "", segment)
    }
    END {
      if (part != "") {
        print part
      } else if (segment != "") {
        print segment
      }
    }
  '
}

extract_hls_update_marker() {
  awk '
    /^#EXT-X-MEDIA-SEQUENCE:/ {
      media_sequence = $0
      sub(/\r$/, "", media_sequence)
    }
    /^#EXT-X-PART:/ {
      part = $0
      sub(/\r$/, "", part)
    }
    /^[^#]/ {
      segment = $0
      sub(/\r$/, "", segment)
    }
    END {
      print media_sequence "|" part "|" segment
    }
  '
}

extract_variant_hls_uri() {
  awk '
    /^#EXT-X-STREAM-INF:/ {
      expect_uri = 1
      next
    }
    expect_uri && /^[^#]/ {
      sub(/\r$/, "", $0)
      print
      exit
    }
  '
}

resolve_hls_uri() {
  local camera_code="$1"
  local uri="$2"

  if [[ "${uri}" =~ ^https?:// ]]; then
    printf '%s' "${uri}"
  elif [[ "${uri}" == /* ]]; then
    printf '%s%s' "${HLS_HEALTH_BASE_URL%/}" "${uri}"
  else
    printf '%s/%s/%s' "${HLS_HEALTH_BASE_URL%/}" "${camera_code}" "${uri#./}"
  fi
}

CHECK_DETAIL=""

check_camera_health() {
  local camera_code="$1"
  local paths_available="$2"
  local paths_json="$3"
  local playlist_url playlist variant_uri variant_url
  local latest_uri latest_url latest_marker previous_marker

  CHECK_DETAIL=""

  # Error priority 1: MediaMTX management API cannot be queried.
  if [[ "${paths_available}" != "true" ]]; then
    CHECK_DETAIL="MEDIAMTX_API_UNAVAILABLE"
    return 1
  fi

  # Error priority 2: the publisher path does not exist or is not ready.
  if ! jq -e --arg camera "${camera_code}" \
    '.items[]? | select(.name == $camera and .ready == true)' \
    >/dev/null <<< "${paths_json}"; then
    CHECK_DETAIL="CAMERA_STREAM_UNAVAILABLE"
    return 1
  fi

  playlist_url="${HLS_HEALTH_BASE_URL%/}/${camera_code}/index.m3u8"

  # Error priority 3: the HLS media playlist itself is unavailable.
  if ! playlist="$(curl "${hls_curl_args[@]}" "${playlist_url}" 2>&1)"; then
    CHECK_DETAIL="HLS_PLAYLIST_UNAVAILABLE"
    return 1
  fi

  # MediaMTX can return a multivariant index first. Follow its media playlist
  # before looking for LL-HLS parts or media segments.
  variant_uri="$(extract_variant_hls_uri <<< "${playlist}")"
  if [[ -n "${variant_uri}" ]]; then
    variant_url="$(resolve_hls_uri "${camera_code}" "${variant_uri}")"
    if ! playlist="$(curl "${hls_curl_args[@]}" "${variant_url}" 2>&1)"; then
      CHECK_DETAIL="HLS_PLAYLIST_UNAVAILABLE"
      return 1
    fi
  fi

  latest_uri="$(extract_latest_hls_uri <<< "${playlist}")"
  if [[ -z "${latest_uri}" ]]; then
    CHECK_DETAIL="HLS_SEGMENT_UNAVAILABLE"
    return 1
  fi

  latest_url="$(resolve_hls_uri "${camera_code}" "${latest_uri}")"

  # Error priority 4: the newest segment or LL-HLS part cannot be downloaded.
  if ! curl "${hls_curl_args[@]}" --output /dev/null "${latest_url}"; then
    CHECK_DETAIL="HLS_SEGMENT_UNAVAILABLE"
    return 1
  fi

  # Error priority 5: the referenced segment/part remains unchanged.
  latest_marker="$(extract_hls_update_marker <<< "${playlist}")"
  previous_marker="${LAST_HLS_MARKER[${camera_code}]:-}"
  LAST_HLS_MARKER["${camera_code}"]="${latest_marker}"
  if [[ -n "${previous_marker}" && "${previous_marker}" == "${latest_marker}" ]]; then
    CHECK_DETAIL="HLS_SEGMENT_STALLED"
    return 1
  fi

  return 0
}

handle_healthy_result() {
  local camera_code="$1"
  local state="${CURRENT_STATE[${camera_code}]:-UNKNOWN}"
  local recovery_count

  FAILURE_COUNT["${camera_code}"]=0
  PENDING_DETAIL["${camera_code}"]=""

  if [[ "${state}" == "ERROR" ]]; then
    recovery_count=$(( ${RECOVERY_COUNT[${camera_code}]:-0} + 1 ))
    RECOVERY_COUNT["${camera_code}"]="${recovery_count}"
    if (( recovery_count < RECOVERY_THRESHOLD )); then
      return 0
    fi

    echo "camera recovered: camera=${camera_code} previousDetail=${INCIDENT_DETAIL[${camera_code}]:-unknown}"
    INCIDENT_DETAIL["${camera_code}"]=""
  fi

  RECOVERY_COUNT["${camera_code}"]=0
  CURRENT_STATE["${camera_code}"]="ONLINE"
  send_heartbeat "${camera_code}" "ONLINE" "" || true
}

handle_failed_result() {
  local camera_code="$1"
  local detected_detail="$2"
  local state="${CURRENT_STATE[${camera_code}]:-UNKNOWN}"
  local pending_detail="${PENDING_DETAIL[${camera_code}]:-}"
  local failure_count

  RECOVERY_COUNT["${camera_code}"]=0

  # Keep the first confirmed cause fixed until full recovery. This matches the
  # backend incident log, which records only the first ERROR transition.
  if [[ "${state}" == "ERROR" ]]; then
    send_heartbeat \
      "${camera_code}" \
      "ERROR" \
      "${INCIDENT_DETAIL[${camera_code}]}" || true
    return 0
  fi

  if [[ "${pending_detail}" == "${detected_detail}" ]]; then
    failure_count=$(( ${FAILURE_COUNT[${camera_code}]:-0} + 1 ))
  else
    PENDING_DETAIL["${camera_code}"]="${detected_detail}"
    failure_count=1
  fi
  FAILURE_COUNT["${camera_code}"]="${failure_count}"

  if (( failure_count < FAILURE_THRESHOLD )); then
    echo "health check failure pending: camera=${camera_code} detail=${detected_detail} count=${failure_count}/${FAILURE_THRESHOLD}"
    return 0
  fi

  CURRENT_STATE["${camera_code}"]="ERROR"
  INCIDENT_DETAIL["${camera_code}"]="${detected_detail}"
  echo "camera error confirmed: camera=${camera_code} detail=${detected_detail}"
  send_heartbeat "${camera_code}" "ERROR" "${detected_detail}" || true
}

while true; do
  paths_available="true"
  if ! paths_json="$(curl \
    "${media_api_curl_args[@]}" \
    "${MEDIAMTX_API_URL%/}/v3/paths/list" 2>&1)"; then
    paths_available="false"
    echo "ERROR: failed to query MediaMTX paths: ${paths_json}" >&2
  fi

  for camera_code in "${CAMERAS[@]}"; do
    if check_camera_health "${camera_code}" "${paths_available}" "${paths_json}"; then
      handle_healthy_result "${camera_code}"
    else
      handle_failed_result "${camera_code}" "${CHECK_DETAIL}"
    fi
  done

  sleep "${INTERVAL_SECONDS}"
done
