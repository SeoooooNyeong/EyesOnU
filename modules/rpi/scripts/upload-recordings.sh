#!/usr/bin/env bash
set -uo pipefail

CONFIG_FILE="${CONFIG_FILE:-/etc/default/mediamtx-uploader}"
QUEUE_DIR="${QUEUE_DIR:-/var/lib/mediamtx/upload-queue}"

if [[ ! -f "${CONFIG_FILE}" ]]; then
  echo "ERROR: missing ${CONFIG_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${CONFIG_FILE}"

: "${UPLOAD_ENABLED:=false}"
: "${RECORDING_ROOT:=/var/lib/mediamtx/recordings}"
: "${RETRY_DELAY_SECONDS:=15}"
: "${DELETE_LOCAL_AFTER_UPLOAD:=true}"
: "${CENTRAL_API_ENABLED:=false}"
: "${CENTRAL_API_BASE_URL:=}"
: "${CENTRAL_API_DEVICE_KEY:=}"

mkdir -p "${QUEUE_DIR}"

validate_config() {
  local missing=0
  local variable_name

  for variable_name in CENTRAL_API_BASE_URL CENTRAL_API_DEVICE_KEY; do
    if [[ -z "${!variable_name:-}" ]]; then
      echo "ERROR: ${variable_name} is required in ${CONFIG_FILE}" >&2
      missing=1
    fi
  done

  if [[ "${CENTRAL_API_ENABLED}" != "true" ]]; then
    echo "ERROR: CENTRAL_API_ENABLED must be true for presigned uploads" >&2
    missing=1
  fi

  return "${missing}"
}

request_presigned_upload() {
  local job_id="$1"
  local camera_code="$2"
  local start_time="$3"
  local end_time="$4"
  local response_file http_status payload
  local object_key upload_url content_type expires_in_seconds max_file_size_bytes

  response_file="$(mktemp)"
  payload="$(jq -n \
    --arg startTime "${start_time}" \
    --arg endTime "${end_time}" \
    '{startTime: $startTime, endTime: $endTime}')"

  http_status="$(
    curl \
      --noproxy "*" \
      --silent \
      --show-error \
      --output "${response_file}" \
      --write-out '%{http_code}' \
      --connect-timeout 5 \
      --max-time 15 \
      --request POST \
      --header "Accept: application/json" \
      --header "Content-Type: application/json" \
      --header "X-Device-Key: ${CENTRAL_API_DEVICE_KEY}" \
      --header "Idempotency-Key: ${job_id}" \
      --data-binary "${payload}" \
      "${CENTRAL_API_BASE_URL%/}/api/v1/device/cameras/${camera_code}/recording-upload-urls"
  )" || {
    echo "ERROR: failed to request recording upload URL: camera=${camera_code}" >&2
    rm -f -- "${response_file}"
    return 1
  }

  if [[ ! "${http_status}" =~ ^20[01]$ ]]; then
    echo "ERROR: recording upload URL request rejected: camera=${camera_code} HTTP=${http_status}" >&2
    sed -n '1,20p' "${response_file}" >&2
    rm -f -- "${response_file}"
    return 1
  fi

  if ! IFS=$'\t' read -r \
    object_key upload_url content_type expires_in_seconds max_file_size_bytes < <(
      jq -er '
        [
          (.data.objectKey | select(type == "string" and length > 0)),
          (.data.uploadUrl | select(type == "string" and length > 0)),
          (.data.contentType | select(type == "string" and length > 0)),
          ((.data.expiresInSeconds // 0) | select(type == "number") | tostring),
          ((.data.maxFileSizeBytes // 0) | select(type == "number") | tostring)
        ] | @tsv
      ' "${response_file}"
    ); then
    echo "ERROR: upload URL response is missing required data fields" >&2
    rm -f -- "${response_file}"
    return 1
  fi

  rm -f -- "${response_file}"
  echo "recording upload URL issued: camera=${camera_code} object=${object_key} expires=${expires_in_seconds}s" >&2
  printf '%s\t%s\t%s\t%s\n' \
    "${object_key}" "${upload_url}" "${content_type}" "${max_file_size_bytes}"
}

build_recording_times() {
  local segment_path="$1"
  local duration_seconds start_epoch end_epoch
  local year month day hour minute second

  if ! duration_seconds="$(
    ffprobe \
      -v error \
      -show_entries format=duration \
      -of default=noprint_wrappers=1:nokey=1 \
      "${segment_path}"
  )" ||
    [[ ! "${duration_seconds}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    echo "ERROR: failed to read recording duration: ${segment_path}" >&2
    return 1
  fi

  if [[ "${segment_path}" =~ /([0-9]{4})/([0-9]{2})/([0-9]{2})/([0-9]{2})-([0-9]{2})-([0-9]{2})-[0-9]+[.]mp4$ ]]; then
    year="${BASH_REMATCH[1]}"
    month="${BASH_REMATCH[2]}"
    day="${BASH_REMATCH[3]}"
    hour="${BASH_REMATCH[4]}"
    minute="${BASH_REMATCH[5]}"
    second="${BASH_REMATCH[6]}"

    if ! start_epoch="$(
      date -d "${year}-${month}-${day} ${hour}:${minute}:${second}" +'%s'
    )"; then
      echo "ERROR: failed to parse recording start time: ${segment_path}" >&2
      return 1
    fi
  else
    end_epoch="$(stat -c '%Y' "${segment_path}")"
    start_epoch="$(
      awk \
        -v end="${end_epoch}" \
        -v duration="${duration_seconds}" \
        'BEGIN { printf "%.0f", end - duration }'
    )"
  fi

  end_epoch="$(
    awk \
      -v start="${start_epoch}" \
      -v duration="${duration_seconds}" \
      'BEGIN { printf "%.0f", start + duration }'
  )"

  printf '%s\t%s\n' \
    "$(date -u -d "@${start_epoch}" +'%Y-%m-%dT%H:%M:%SZ')" \
    "$(date -u -d "@${end_epoch}" +'%Y-%m-%dT%H:%M:%SZ')"
}

save_recording_id() {
  local job_path="$1"
  local recording_id="$2"
  local object_key="$3"
  local temporary_path

  temporary_path="$(mktemp "${QUEUE_DIR}/.recording-job.XXXXXX")"
  if ! jq \
    --arg recordingId "${recording_id}" \
    --arg objectKey "${object_key}" \
    '.recordingId = $recordingId | .objectKey = $objectKey' \
    "${job_path}" >"${temporary_path}"; then
    rm -f -- "${temporary_path}"
    return 1
  fi

  chmod 0640 "${temporary_path}"
  mv "${temporary_path}" "${job_path}"
}

ensure_job_uuid() {
  local job_path="$1"
  local job_id="$2"
  local replacement_id temporary_path

  if [[ "${job_id}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]]; then
    printf '%s' "${job_id}"
    return 0
  fi

  replacement_id="$(cat /proc/sys/kernel/random/uuid)"
  temporary_path="$(mktemp "${QUEUE_DIR}/.recording-job.XXXXXX")"
  if ! jq \
    --arg jobId "${replacement_id}" \
    '.jobId = $jobId' \
    "${job_path}" >"${temporary_path}"; then
    rm -f -- "${temporary_path}"
    return 1
  fi

  chmod 0640 "${temporary_path}"
  mv "${temporary_path}" "${job_path}"
  echo "migrated legacy upload job to UUID idempotency key" >&2
  printf '%s' "${replacement_id}"
}

register_recording() {
  local job_path="$1"
  local job_id="$2"
  local camera_code="$3"
  local start_time="$4"
  local end_time="$5"
  local object_key="$6"
  local file_size="$7"
  local response_file http_status recording_id payload

  response_file="$(mktemp)"

  payload="$(
    jq -n \
      --arg startTime "${start_time}" \
      --arg endTime "${end_time}" \
      --arg objectKey "${object_key}" \
      --argjson fileSize "${file_size}" \
      --arg uploadStatus "COMPLETED" \
      '{
        startTime: $startTime,
        endTime: $endTime,
        objectKey: $objectKey,
        fileSize: $fileSize,
        uploadStatus: $uploadStatus
      }'
  )"

  http_status="$(
    curl \
      --noproxy "*" \
      --silent \
      --show-error \
      --output "${response_file}" \
      --write-out '%{http_code}' \
      --connect-timeout 5 \
      --max-time 15 \
      -X POST \
      -H "Accept: application/json" \
      -H "Content-Type: application/json" \
      -H "X-Device-Key: ${CENTRAL_API_DEVICE_KEY}" \
      -H "Idempotency-Key: ${job_id}" \
      --data-binary "${payload}" \
      "${CENTRAL_API_BASE_URL%/}/api/v1/device/cameras/${camera_code}/recordings"
  )" || {
    echo "ERROR: failed to register recording metadata" >&2
    rm -f -- "${response_file}"
    return 1
  }

  if [[ ! "${http_status}" =~ ^20[01]$ ]]; then
    echo "ERROR: recording metadata registration rejected: HTTP ${http_status}" >&2
    sed -n '1,20p' "${response_file}" >&2
    rm -f -- "${response_file}"
    return 1
  fi

  if ! recording_id="$(
    jq -er \
      '(.data.recordingId // .recordingId // .data.id // .id) | tostring' \
      "${response_file}"
  )"; then
    echo "ERROR: recording metadata response does not contain recordingId" >&2
    rm -f -- "${response_file}"
    return 1
  fi

  if ! save_recording_id "${job_path}" "${recording_id}" "${object_key}"; then
    echo "ERROR: failed to preserve recordingId in upload job" >&2
    rm -f -- "${response_file}"
    return 1
  fi

  echo "recording metadata registered: camera=${camera_code} recordingId=${recording_id} status=COMPLETED http=${http_status}" >&2
  rm -f -- "${response_file}"
  printf '%s' "${recording_id}"
}

save_upload_completed() {
  local job_path="$1"
  local object_key="$2"
  local temporary_path

  temporary_path="$(mktemp "${QUEUE_DIR}/.recording-job.XXXXXX")"
  if ! jq \
    --arg objectKey "${object_key}" \
    '.objectKey = $objectKey | .uploadCompleted = true' \
    "${job_path}" >"${temporary_path}"; then
    rm -f -- "${temporary_path}"
    return 1
  fi

  chmod 0640 "${temporary_path}"
  mv "${temporary_path}" "${job_path}"
}

upload_with_presigned_url() {
  local segment_path="$1"
  local upload_url="$2"
  local content_type="$3"
  local response_file http_status

  response_file="$(mktemp)"
  http_status="$(
    curl \
      --noproxy "*" \
      --silent \
      --show-error \
      --output "${response_file}" \
      --write-out '%{http_code}' \
      --connect-timeout 10 \
      --max-time 300 \
      --request PUT \
      --header "Content-Type: ${content_type}" \
      --upload-file "${segment_path}" \
      "${upload_url}"
  )" || {
    echo "ERROR: presigned recording upload request failed" >&2
    rm -f -- "${response_file}"
    return 1
  }

  if [[ ! "${http_status}" =~ ^2[0-9]{2}$ ]]; then
    echo "ERROR: presigned recording upload rejected: HTTP=${http_status}" >&2
    sed -n '1,20p' "${response_file}" >&2
    rm -f -- "${response_file}"
    return 1
  fi

  rm -f -- "${response_file}"
}

process_job() {
  local job_path="$1"
  local job_id camera_path segment_path object_key upload_url content_type
  local recording_id saved_object_key upload_completed max_file_size_bytes
  local start_time end_time
  local local_size

  if ! job_id="$(jq -er '.jobId' "${job_path}")" ||
     ! camera_path="$(jq -er '.cameraPath' "${job_path}")" ||
     ! segment_path="$(jq -er '.segmentPath' "${job_path}")"; then
    echo "ERROR: invalid upload job: ${job_path}" >&2
    return 1
  fi

  if ! job_id="$(ensure_job_uuid "${job_path}" "${job_id}")"; then
    echo "ERROR: failed to ensure UUID Idempotency-Key: ${job_path}" >&2
    return 1
  fi

  if [[ ! "${camera_path}" =~ ^camera-[0-9]{2}$ ]]; then
    echo "ERROR: invalid camera ID in upload job: ${camera_path}" >&2
    return 1
  fi

  if [[ ! -f "${segment_path}" ]]; then
    echo "WARN: local segment no longer exists; removing stale job: ${segment_path}" >&2
    rm -f -- "${job_path}"
    return 0
  fi

  local_size="$(stat -c '%s' "${segment_path}")"

  if ! IFS=$'\t' read -r start_time end_time < <(
    build_recording_times "${segment_path}"
  ); then
    return 1
  fi

  recording_id="$(jq -r '.recordingId // empty' "${job_path}")"
  saved_object_key="$(jq -r '.objectKey // empty' "${job_path}")"
  upload_completed="$(jq -r '.uploadCompleted // false' "${job_path}")"

  # If the file upload succeeded before a service crash, retry the idempotent
  # COMPLETED metadata registration without uploading the object again.
  # Presigned URLs are intentionally never persisted.
  if [[ "${upload_completed}" == "true" ]]; then
    if [[ -z "${saved_object_key}" ]]; then
      echo "ERROR: completed upload job is missing objectKey: ${job_path}" >&2
      return 1
    fi

    echo "registering uploaded recording metadata: camera=${camera_path} status=COMPLETED"
    if ! recording_id="$(
      register_recording \
        "${job_path}" \
        "${job_id}" \
        "${camera_path}" \
        "${start_time}" \
        "${end_time}" \
        "${saved_object_key}" \
        "${local_size}"
    )"; then
      return 1
    fi

    rm -f -- "${job_path}"
    if [[ "${DELETE_LOCAL_AFTER_UPLOAD}" == "true" ]]; then
      rm -f -- "${segment_path}"
    fi
    echo "recording upload completed: camera=${camera_path} object=${saved_object_key} size=${local_size}"
    return 0
  fi

  if ! IFS=$'\t' read -r \
    object_key upload_url content_type max_file_size_bytes < <(
      request_presigned_upload \
        "${job_id}" \
        "${camera_path}" \
        "${start_time}" \
        "${end_time}"
    ); then
    return 1
  fi

  if [[ ! "${max_file_size_bytes}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: invalid maxFileSizeBytes in upload URL response" >&2
    return 1
  fi

  if (( max_file_size_bytes > 0 && local_size > max_file_size_bytes )); then
    echo "ERROR: recording exceeds presigned upload size limit: size=${local_size} max=${max_file_size_bytes}" >&2
    return 1
  fi

  if [[ -n "${saved_object_key}" && "${saved_object_key}" != "${object_key}" ]]; then
    echo "ERROR: idempotent upload URL returned a different Object Key" >&2
    return 1
  fi

  echo "uploading recording with presigned URL: camera=${camera_path} object=${object_key}"
  if ! upload_with_presigned_url "${segment_path}" "${upload_url}" "${content_type}"; then
    return 1
  fi

  if ! save_upload_completed "${job_path}" "${object_key}"; then
    echo "ERROR: failed to persist successful upload state" >&2
    return 1
  fi

  echo "registering uploaded recording metadata: camera=${camera_path} status=COMPLETED"
  if ! recording_id="$(
    register_recording \
      "${job_path}" \
      "${job_id}" \
      "${camera_path}" \
      "${start_time}" \
      "${end_time}" \
      "${object_key}" \
      "${local_size}"
  )"; then
    return 1
  fi

  rm -f -- "${job_path}"
  if [[ "${DELETE_LOCAL_AFTER_UPLOAD}" == "true" ]]; then
    rm -f -- "${segment_path}"
  fi

  echo "recording upload completed: camera=${camera_path} object=${object_key} size=${local_size}"
}

if [[ "${UPLOAD_ENABLED}" != "true" ]]; then
  echo "Recording uploader is disabled; set UPLOAD_ENABLED=true in ${CONFIG_FILE}"
fi

while true; do
  if [[ "${UPLOAD_ENABLED}" == "true" ]]; then
    if validate_config; then
      shopt -s nullglob
      jobs=("${QUEUE_DIR}"/*.json)
      shopt -u nullglob

      for job_path in "${jobs[@]}"; do
        process_job "${job_path}" || true
      done
    fi
  fi

  sleep "${RETRY_DELAY_SECONDS}"
done
