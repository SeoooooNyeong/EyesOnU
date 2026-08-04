#!/usr/bin/env bash
set -euo pipefail

: "${MTX_PATH:?MTX_PATH was not provided by MediaMTX}"
: "${MTX_SEGMENT_PATH:?MTX_SEGMENT_PATH was not provided by MediaMTX}"
: "${MTX_SEGMENT_DURATION:?MTX_SEGMENT_DURATION was not provided by MediaMTX}"

QUEUE_DIR="/var/lib/mediamtx/upload-queue"
mkdir -p "${QUEUE_DIR}"

# The path hash provides a deterministic queue filename, while the UUID stored
# in the job is reused as the backend Idempotency-Key for every retry.
queue_key="$(printf '%s\n%s' "${MTX_PATH}" "${MTX_SEGMENT_PATH}" | sha256sum | awk '{print $1}')"
job_path="${QUEUE_DIR}/${queue_key}.json"

if [[ -f "${job_path}" ]]; then
  exit 0
fi

job_id="$(cat /proc/sys/kernel/random/uuid)"
temporary_path="$(mktemp "${QUEUE_DIR}/.${queue_key}.XXXXXX")"
trap 'rm -f -- "${temporary_path}"' EXIT

jq -n \
  --arg schemaVersion "1.0" \
  --arg jobId "${job_id}" \
  --arg cameraPath "${MTX_PATH}" \
  --arg segmentPath "${MTX_SEGMENT_PATH}" \
  --arg duration "${MTX_SEGMENT_DURATION}" \
  --arg completedAt "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
  --arg uploadStatus "PENDING" \
  '{
    schemaVersion: $schemaVersion,
    jobId: $jobId,
    cameraPath: $cameraPath,
    segmentPath: $segmentPath,
    duration: $duration,
    completedAt: $completedAt,
    uploadStatus: $uploadStatus
  }' >"${temporary_path}"

chmod 0640 "${temporary_path}"
mv "${temporary_path}" "${job_path}"
trap - EXIT

echo "queued recording upload: camera=${MTX_PATH} file=${MTX_SEGMENT_PATH}"
