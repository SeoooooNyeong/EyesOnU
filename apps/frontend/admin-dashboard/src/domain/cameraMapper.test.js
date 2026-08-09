import { afterEach, describe, expect, it, vi } from "vitest";
import { buildCameraPlaybackSource, mapCamera, toUiCameraStatus } from "./cameraMapper";

describe("cameraMapper", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("maps backend camera metadata without inventing unavailable values", () => {
    expect(mapCamera({
      id: 1,
      cameraCode: "camera-01",
      cameraName: "Camera 01",
      mediaServer: { id: 3, serverCode: "rpi5-media-01", name: "RPI5" },
      latitude: 0,
      longitude: 0,
      address: "설치 위치 미정",
      status: "OFFLINE",
      lastHeartbeat: null,
      createdAt: "2026-07-30T00:00:00Z"
    })).toMatchObject({
      id: 1,
      cameraCode: "camera-01",
      cameraName: "Camera 01",
      mediaServerCode: "rpi5-media-01",
      latitude: 0,
      longitude: 0,
      status: "offline",
      lastHeartbeat: "-",
      createdAt: "2026-07-30 09:00"
    });
  });

  it("normalizes supported DB statuses", () => {
    expect(toUiCameraStatus("ONLINE")).toBe("online");
    expect(toUiCameraStatus("offline")).toBe("offline");
    expect(toUiCameraStatus("ERROR")).toBe("error");
  });

  it("extracts Basic credentials and removes them from a proxied HLS URL", () => {
    const source = buildCameraPlaybackSource(
      "http://webviewer:p%40ss%3Aword@70.12.108.93:8888/camera-01",
      "/media-stream/"
    );

    expect(source).toEqual({
      url: "/media-stream/camera-01/index.m3u8",
      authorization: `Basic ${btoa("webviewer:p@ss:word")}`
    });
    expect(source.url).not.toContain("webviewer");
    expect(source.url).not.toContain("p%40ss");
    expect(source.url).not.toContain("@");
  });

  it("uses the configured same-origin proxy while retaining the DB path and query", () => {
    vi.stubEnv("VITE_MEDIA_STREAM_BASE_URL", "/media-stream");

    const source = buildCameraPlaybackSource(
      "http://viewer:secret@media.example:8888/camera-02?profile=live"
    );

    expect(source.url).toBe("/media-stream/camera-02/index.m3u8?profile=live");
    expect(source.authorization).toBe(`Basic ${btoa("viewer:secret")}`);
  });

  it("keeps an existing playlist path and supports anonymous streams", () => {
    vi.stubEnv("VITE_MEDIA_STREAM_BASE_URL", "   ");

    const source = buildCameraPlaybackSource(
      "https://media.example:8888/camera-03/custom.m3u8?quality=high"
    );
    const url = new URL(source.url);

    expect(url.origin).toBe("https://media.example:8888");
    expect(url.pathname).toBe("/camera-03/custom.m3u8");
    expect(url.search).toBe("?quality=high");
    expect(source.authorization).toBeNull();
  });

  it("rejects missing, malformed, and non-HTTP stream URLs", () => {
    expect(() => buildCameraPlaybackSource("")).toThrow("스트림 URL");
    expect(() => buildCameraPlaybackSource("not-a-url")).toThrow("형식");
    expect(() => buildCameraPlaybackSource("rtsp://viewer:secret@media.example/camera-01"))
      .toThrow("HTTP 또는 HTTPS");
  });
});
