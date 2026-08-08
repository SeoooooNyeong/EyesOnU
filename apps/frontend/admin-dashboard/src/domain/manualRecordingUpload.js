export const MAX_MANUAL_RECORDING_BYTES = 100 * 1024 * 1024;
export const MAX_MANUAL_RECORDING_DURATION_SECONDS = 60;

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const KST_INPUT_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/;
const ACCEPTED_MP4_MIME_TYPES = new Set(["", "video/mp4", "application/mp4"]);

export class ManualRecordingValidationError extends Error {
  constructor(message, code) {
    super(message);
    this.name = "ManualRecordingValidationError";
    this.code = code;
  }
}

const pad = (value) => String(value).padStart(2, "0");

export function toKstDateTimeInput(value) {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return "";

  const kst = new Date(date.getTime() + KST_OFFSET_MS);
  return `${kst.getUTCFullYear()}-${pad(kst.getUTCMonth() + 1)}-${pad(kst.getUTCDate())}`
    + `T${pad(kst.getUTCHours())}:${pad(kst.getUTCMinutes())}:${pad(kst.getUTCSeconds())}`;
}

export function kstDateTimeInputToIso(value) {
  const match = String(value || "").match(KST_INPUT_PATTERN);
  if (!match) {
    throw new ManualRecordingValidationError(
      "녹화 시작 시각을 초 단위까지 입력해 주세요.",
      "INVALID_RECORDING_START"
    );
  }

  const [, yearValue, monthValue, dayValue, hourValue, minuteValue, secondValue = "0"] = match;
  const parts = [yearValue, monthValue, dayValue, hourValue, minuteValue, secondValue].map(Number);
  const [year, month, day, hour, minute, second] = parts;
  const instant = Date.UTC(year, month - 1, day, hour - 9, minute, second);
  const roundTrip = new Date(instant + KST_OFFSET_MS);
  const valid = roundTrip.getUTCFullYear() === year
    && roundTrip.getUTCMonth() + 1 === month
    && roundTrip.getUTCDate() === day
    && roundTrip.getUTCHours() === hour
    && roundTrip.getUTCMinutes() === minute
    && roundTrip.getUTCSeconds() === second;
  if (!valid) {
    throw new ManualRecordingValidationError(
      "녹화 시작 시각이 올바르지 않습니다.",
      "INVALID_RECORDING_START"
    );
  }
  return new Date(instant).toISOString();
}

export function validateManualRecordingFile(file, maxBytes = MAX_MANUAL_RECORDING_BYTES) {
  if (!(file instanceof Blob) || file.size <= 0) {
    throw new ManualRecordingValidationError(
      "업로드할 MP4 파일을 선택해 주세요.",
      "EMPTY_RECORDING_FILE"
    );
  }
  if (!/\.mp4$/i.test(String(file.name || ""))) {
    throw new ManualRecordingValidationError(
      "파일 이름이 .mp4로 끝나는 MP4 파일만 업로드할 수 있습니다.",
      "UNSUPPORTED_RECORDING_EXTENSION"
    );
  }
  if (!ACCEPTED_MP4_MIME_TYPES.has(String(file.type || "").toLowerCase())) {
    throw new ManualRecordingValidationError(
      "MP4 MIME 형식의 파일만 업로드할 수 있습니다.",
      "UNSUPPORTED_RECORDING_TYPE"
    );
  }
  if (file.size > maxBytes) {
    throw new ManualRecordingValidationError(
      `녹화 파일은 ${formatFileSize(maxBytes)} 이하여야 합니다.`,
      "RECORDING_FILE_TOO_LARGE"
    );
  }
  return file;
}

export function validateManualRecordingDuration(durationSeconds) {
  const duration = Number(durationSeconds);
  if (!Number.isFinite(duration) || duration <= 0) {
    throw new ManualRecordingValidationError(
      "영상 재생 시간을 확인할 수 없습니다.",
      "INVALID_RECORDING_DURATION"
    );
  }
  if (duration > MAX_MANUAL_RECORDING_DURATION_SECONDS) {
    throw new ManualRecordingValidationError(
      "녹화 영상 길이는 60초 이하여야 합니다.",
      "RECORDING_DURATION_TOO_LONG"
    );
  }
  return duration;
}

export function buildManualRecordingPeriod(startKst, durationSeconds, now = new Date()) {
  const startTime = kstDateTimeInputToIso(startKst);
  const duration = validateManualRecordingDuration(durationSeconds);
  const startMillis = new Date(startTime).getTime();
  const endMillis = startMillis + Math.round(duration * 1000);
  const nowMillis = now instanceof Date ? now.getTime() : new Date(now).getTime();

  if (Number.isNaN(nowMillis)) {
    throw new TypeError("현재 시각이 올바르지 않습니다.");
  }
  if (endMillis > nowMillis) {
    throw new ManualRecordingValidationError(
      "녹화 종료 시각은 현재보다 미래일 수 없습니다.",
      "FUTURE_RECORDING_PERIOD"
    );
  }

  const endTime = new Date(endMillis).toISOString();
  return {
    startTime,
    endTime,
    startKst: toKstDateTimeInput(startTime),
    endKst: toKstDateTimeInput(endTime)
  };
}

export async function readManualRecordingDuration(file, options = {}) {
  validateManualRecordingFile(file);
  const createObjectUrl = options.createObjectURL || ((value) => URL.createObjectURL(value));
  const revokeObjectUrl = options.revokeObjectURL || ((value) => URL.revokeObjectURL(value));
  const createVideo = options.createVideo || (() => document.createElement("video"));
  const objectUrl = createObjectUrl(file);
  const video = createVideo();

  try {
    const duration = await new Promise((resolve, reject) => {
      video.preload = "metadata";
      video.onloadedmetadata = () => resolve(video.duration);
      video.onerror = () => reject(new ManualRecordingValidationError(
        "영상 메타데이터를 읽지 못했습니다. 손상되지 않은 MP4 파일을 선택해 주세요.",
        "RECORDING_METADATA_UNAVAILABLE"
      ));
      video.src = objectUrl;
      video.load?.();
    });
    return validateManualRecordingDuration(duration);
  } finally {
    video.removeAttribute?.("src");
    video.load?.();
    revokeObjectUrl(objectUrl);
  }
}

export function formatFileSize(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) return "-";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MiB`;
}

export function formatDuration(durationSeconds) {
  const duration = Number(durationSeconds);
  if (!Number.isFinite(duration) || duration < 0) return "-";
  return `${duration.toFixed(duration % 1 === 0 ? 0 : 3)}초`;
}
