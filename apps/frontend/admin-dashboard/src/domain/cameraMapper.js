import { formatKstDateTime } from "./caseMapper";

export function toUiCameraStatus(status) {
  const normalized = String(status ?? "").trim().toUpperCase();
  if (["ONLINE", "OFFLINE", "ERROR"].includes(normalized)) {
    return normalized.toLowerCase();
  }
  return normalized ? normalized.toLowerCase() : "offline";
}

export function mapCamera(source = {}) {
  return {
    id: source.id,
    cameraCode: source.cameraCode ?? "-",
    cameraName: source.cameraName ?? "-",
    mediaServerId: source.mediaServer?.id ?? null,
    mediaServerCode: source.mediaServer?.serverCode ?? "-",
    mediaServerName: source.mediaServer?.name ?? "-",
    latitude: source.latitude ?? "-",
    longitude: source.longitude ?? "-",
    address: source.address ?? "-",
    status: toUiCameraStatus(source.status),
    statusCode: source.status ?? "OFFLINE",
    lastHeartbeat: formatKstDateTime(source.lastHeartbeat) || "-",
    createdAt: formatKstDateTime(source.createdAt) || "-",
    updatedAt: formatKstDateTime(source.updatedAt) || "-"
  };
}

const decodeCredential = (value) => {
  try {
    return decodeURIComponent(value);
  } catch {
    throw new TypeError("스트림 URL 인증정보의 인코딩이 올바르지 않습니다.");
  }
};

const encodeBasicCredentials = (username, password) => {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return `Basic ${btoa(binary)}`;
};

const appendPath = (baseUrl, path, search) => {
  const normalizedBase = baseUrl.replace(/\/+$/, "");
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${normalizedBase}${normalizedPath}${search}`;
};

export function buildCameraPlaybackSource(streamUrl, baseUrl) {
  const normalizedStreamUrl = String(streamUrl ?? "").trim();
  if (!normalizedStreamUrl) throw new TypeError("스트림 URL이 필요합니다.");

  let parsedUrl;
  try {
    parsedUrl = new URL(normalizedStreamUrl);
  } catch {
    throw new TypeError("스트림 URL 형식이 올바르지 않습니다.");
  }

  if (!["http:", "https:"].includes(parsedUrl.protocol)) {
    throw new TypeError("HLS 스트림 URL은 HTTP 또는 HTTPS 형식이어야 합니다.");
  }

  const hasCredentials = Boolean(parsedUrl.username || parsedUrl.password);
  const authorization = hasCredentials
    ? encodeBasicCredentials(
        decodeCredential(parsedUrl.username),
        decodeCredential(parsedUrl.password)
      )
    : null;

  parsedUrl.username = "";
  parsedUrl.password = "";
  parsedUrl.hash = "";

  const trimmedPath = parsedUrl.pathname.replace(/\/+$/, "");
  const playlistPath = /\.m3u8$/i.test(trimmedPath)
    ? trimmedPath
    : `${trimmedPath}/index.m3u8`;
  const configuredBaseUrl = [baseUrl, import.meta.env.VITE_MEDIA_STREAM_BASE_URL]
    .map((candidate) => String(candidate ?? "").trim())
    .find(Boolean);

  if (configuredBaseUrl) {
    return {
      url: appendPath(configuredBaseUrl, playlistPath, parsedUrl.search),
      authorization
    };
  }

  parsedUrl.pathname = playlistPath;
  return { url: parsedUrl.toString(), authorization };
}
