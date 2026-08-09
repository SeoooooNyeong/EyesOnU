import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  completeManualRecordingUpload,
  createManualRecordingUploadUrl,
  uploadManualRecordingFile
} from "./recordingApi";
import { apiClient, setUnauthorizedHandler } from "./httpClient";

const originalAdapter = apiClient.defaults.adapter;
const response = (config, data, status = 200) => ({
  config,
  data,
  headers: {},
  request: {},
  status,
  statusText: "OK"
});

describe("recordingApi manual upload", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=recording-api-token; path=/";
    setUnauthorizedHandler(null);
  });

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter;
    document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
    setUnauthorizedHandler(null);
    vi.unstubAllGlobals();
  });

  it("requests an admin upload URL with the camera code and idempotency key", async () => {
    const adapter = vi.fn(async (config) => response(config, {
      data: {
        objectKey: "recordings/camera-04/manual.mp4",
        uploadUrl: "https://storage.example/upload",
        contentType: "video/mp4",
        expiresInSeconds: 900,
        maxFileSizeBytes: 104857600
      }
    }, 201));
    apiClient.defaults.adapter = adapter;
    const payload = {
      startTime: "2026-08-08T06:04:05.000Z",
      endTime: "2026-08-08T06:04:35.000Z"
    };

    await expect(createManualRecordingUploadUrl("camera/04", payload, "550e8400-e29b-41d4-a716-446655440000"))
      .resolves.toMatchObject({ contentType: "video/mp4" });

    const config = adapter.mock.calls[0][0];
    expect(config.method).toBe("post");
    expect(config.url).toBe("/admin/cameras/camera%2F04/recording-upload-urls");
    expect(config.headers.get("Idempotency-Key")).toBe("550e8400-e29b-41d4-a716-446655440000");
    expect(JSON.parse(config.data)).toEqual(payload);
  });

  it("uploads the original MP4 bytes with native fetch and omits credentials", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal("fetch", fetchMock);
    const file = new File([new Uint8Array([0, 0, 0, 8, 102, 116, 121, 112])], "clip.mp4", {
      type: "video/mp4"
    });
    const controller = new AbortController();

    await expect(uploadManualRecordingFile(
      "https://storage.example/upload?signature=secret",
      file,
      "video/mp4",
      { signal: controller.signal }
    )).resolves.toBeUndefined();

    expect(fetchMock).toHaveBeenCalledWith(
      "https://storage.example/upload?signature=secret",
      {
        method: "PUT",
        body: file,
        credentials: "omit",
        headers: { "Content-Type": "video/mp4" },
        signal: controller.signal
      }
    );
  });

  it("turns a non-successful storage PUT into a safe error without exposing the URL", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 403 }));

    await expect(uploadManualRecordingFile(
      "https://storage.example/upload?signature=secret",
      new File(["mp4"], "clip.mp4", { type: "video/mp4" })
    )).rejects.toMatchObject({
      message: "녹화 파일을 저장소에 업로드하지 못했습니다.",
      status: 403
    });
  });

  it("completes registration with the same idempotency key and object metadata", async () => {
    const adapter = vi.fn(async (config) => response(config, {
      data: {
        id: 501,
        cameraId: 4,
        fileSize: 1024,
        duplicate: false,
        createdAt: "2026-08-08T06:05:00Z"
      }
    }, 201));
    apiClient.defaults.adapter = adapter;
    const payload = {
      startTime: "2026-08-08T06:04:05.000Z",
      endTime: "2026-08-08T06:04:35.000Z",
      objectKey: "recordings/camera-04/manual.mp4"
    };

    await expect(completeManualRecordingUpload(
      "camera-04",
      payload,
      "550e8400-e29b-41d4-a716-446655440000"
    )).resolves.toMatchObject({ id: 501 });

    const config = adapter.mock.calls[0][0];
    expect(config.url).toBe("/admin/cameras/camera-04/recordings");
    expect(config.headers.get("Idempotency-Key")).toBe("550e8400-e29b-41d4-a716-446655440000");
    expect(JSON.parse(config.data)).toEqual(payload);
  });
});
