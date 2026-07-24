#!/usr/bin/env bash
set -uo pipefail

CONFIG_FILE="/etc/default/mediamtx-uploader"
QUEUE_DIR="/var/lib/mediamtx/upload-queue"
MINIO_ALIAS="edge-minio"
MC_CONFIG_DIR="/var/lib/mediamtx/.mc"

if [[ ! -f "${CONFIG_FILE}" ]]; then
  echo "ERROR: missing ${CONFIG_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${CONFIG_FILE}"

: "${UPLOAD_ENABLED:=false}"
: "${MINIO_ENDPOINT:=}"
: "${MINIO_BUCKET:=}"
: "${MINIO_ACCESS_KEY:=}"
: "${MINIO_SECRET_KEY:=}"
: "${MINIO_OBJECT_PREFIX:=recordings}"
: "${MEDIA_SERVER_ID:=rpi5-media-01}"
: "${MINIO_INSECURE:=false}"
: "${RECORDING_ROOT:=/var/lib/mediamtx/recordings}"
: "${RETRY_DELAY_SECONDS:=15}"
: "${DELETE_LOCAL_AFTER_UPLOAD:=true}"

mkdir -p "${QUEUE_DIR}" "${MC_CONFIG_DIR}"
chmod 0700 "${MC_CONFIG_DIR}"

mc_global_args=(--config-dir "${MC_CONFIG_DIR}")
if [[ "${MINIO_INSECURE}" == "true" ]]; then
  mc_global_args+=(--insecure)
fi

validate_config() {
  local missing=0
  local variable_name

  for variable_name in MINIO_ENDPOINT MINIO_BUCKET MINIO_ACCESS_KEY MINIO_SECRET_KEY MEDIA_SERVER_ID; do
    if [[ -z "${!variable_name:-}" ]]; then
      echo "ERROR: ${variable_name} is required in ${CONFIG_FILE}" >&2
      missing=1
    fi
  done

  if [[ "${MINIO_BUCKET}" == */* ]]; then
    echo "ERROR: MINIO_BUCKET must contain only the bucket name" >&2
    missing=1
  fi

  return "${missing}"
}

configure_minio() {
  if ! mc "${mc_global_args[@]}" alias set \
    "${MINIO_ALIAS}" \
    "${MINIO_ENDPOINT}" \
    "${MINIO_ACCESS_KEY}" \
    "${MINIO_SECRET_KEY}" \
    --api S3v4 >/dev/null; then
    echo "ERROR: failed to configure MinIO alias for ${MINIO_ENDPOINT}" >&2
    return 1
  fi

  if ! mc "${mc_global_args[@]}" ls \
    "${MINIO_ALIAS}/${MINIO_BUCKET}" >/dev/null; then
    echo "ERROR: MinIO bucket is unavailable: ${MINIO_BUCKET}" >&2
    return 1
  fi
}

build_object_key() {
  local segment_path="$1"
  local relative_path prefix

  prefix="${RECORDING_ROOT%/}/"
  if [[ "${segment_path}" != "${prefix}"* ]]; then
    echo "ERROR: segment is outside RECORDING_ROOT: ${segment_path}" >&2
    return 1
  fi

  relative_path="${segment_path#${prefix}}"
  printf '%s/%s/%s' \
    "${MINIO_OBJECT_PREFIX#/}" \
    "${MEDIA_SERVER_ID}" \
    "${relative_path}"
}

process_job() {
  local job_path="$1"
  local job_id camera_path segment_path object_key destination
  local local_size remote_json remote_size

  if ! job_id="$(jq -er '.jobId' "${job_path}")" ||
     ! camera_path="$(jq -er '.cameraPath' "${job_path}")" ||
     ! segment_path="$(jq -er '.segmentPath' "${job_path}")"; then
    echo "ERROR: invalid upload job: ${job_path}" >&2
    return 1
  fi

  if [[ ! -f "${segment_path}" ]]; then
    echo "WARN: local segment no longer exists; removing stale job: ${segment_path}" >&2
    rm -f -- "${job_path}"
    return 0
  fi

  if ! object_key="$(build_object_key "${segment_path}")"; then
    return 1
  fi

  destination="${MINIO_ALIAS}/${MINIO_BUCKET}/${object_key}"
  local_size="$(stat -c '%s' "${segment_path}")"

  # A service crash can occur after upload but before local cleanup. If an object
  # with the same key and size already exists, treat the retry as successful.
  if remote_json="$(mc "${mc_global_args[@]}" stat --json "${destination}" 2>/dev/null)"; then
    remote_size="$(jq -er '.size' <<<"${remote_json}" 2>/dev/null || true)"
    if [[ "${remote_size}" == "${local_size}" ]]; then
      echo "MinIO object already exists with matching size: ${object_key}"
    else
      echo "ERROR: Object Key collision with a different size: ${object_key}" >&2
      return 1
    fi
  else
    echo "uploading to MinIO: camera=${camera_path} object=${object_key}"
    if ! mc "${mc_global_args[@]}" cp --json \
      "${segment_path}" \
      "${destination}" >/dev/null; then
      echo "ERROR: MinIO upload failed: job=${job_id} object=${object_key}" >&2
      return 1
    fi
  fi

  rm -f -- "${job_path}"
  if [[ "${DELETE_LOCAL_AFTER_UPLOAD}" == "true" ]]; then
    rm -f -- "${segment_path}"
  fi

  echo "MinIO upload completed: camera=${camera_path} object=${object_key} size=${local_size}"
}

if [[ "${UPLOAD_ENABLED}" != "true" ]]; then
  echo "MinIO uploader is disabled; set UPLOAD_ENABLED=true in ${CONFIG_FILE}"
fi

while true; do
  if [[ "${UPLOAD_ENABLED}" == "true" ]]; then
    if validate_config && configure_minio; then
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
