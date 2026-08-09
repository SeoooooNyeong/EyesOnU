import { createApp, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { getCameraStreamUrlMock, hlsState, listCamerasMock } = vi.hoisted(() => ({
  getCameraStreamUrlMock: vi.fn(),
  hlsState: { instances: [] },
  listCamerasMock: vi.fn()
}));
vi.mock("../api/cameraApi", () => ({
  getCameraStreamUrl: getCameraStreamUrlMock,
  listCameras: listCamerasMock
}));
vi.mock("hls.js", () => {
  const Events = { ERROR: "error", MANIFEST_PARSED: "manifest-parsed" };

  class HlsMock {
    static Events = Events;

    static isSupported() {
      return true;
    }

    constructor() {
      this.handlers = {};
      this.destroy = vi.fn();
      this.loadSource = vi.fn();
      this.attachMedia = vi.fn();
      hlsState.instances.push(this);
    }

    on(event, handler) {
      this.handlers[event] = handler;
    }
  }

  return { default: HlsMock };
});

import LiveMonitoringView from "./LiveMonitoringView.vue";

const rawCamera = (id, code, overrides = {}) => ({
  id,
  cameraCode: code,
  cameraName: `Camera ${String(id).padStart(2, "0")}`,
  mediaServer: { id: 3, serverCode: "rpi5-media-01", name: "RPI5 Media" },
  latitude: 0,
  longitude: 0,
  address: "설치 위치 미정",
  status: "ONLINE",
  lastHeartbeat: null,
  createdAt: "2026-07-30T00:00:00Z",
  updatedAt: "2026-07-30T00:00:00Z",
  ...overrides
});

const result = (data) => ({
  data,
  meta: { page: 0, size: 4, totalElements: data.length, totalPages: 1, sort: "cameraCode,asc" }
});

const settle = async () => {
  for (let index = 0; index < 6; index += 1) {
    await Promise.resolve();
    await nextTick();
  }
};

describe("LiveMonitoringView", () => {
  let app;
  let root;

  const mount = () => {
    root = document.createElement("div");
    document.body.appendChild(root);
    app = createApp(LiveMonitoringView);
    app.mount(root);
  };

  beforeEach(() => {
    hlsState.instances.length = 0;
    getCameraStreamUrlMock.mockReset();
    listCamerasMock.mockReset();
    getCameraStreamUrlMock.mockImplementation(async (cameraId) => ({
      streamUrl: `http://webviewer:test-secret@media.example:8888/camera-${String(cameraId).padStart(2, "0")}`
    }));
  });

  afterEach(() => {
    app?.unmount();
    root?.remove();
    vi.useRealTimers();
  });

  it("loads up to four cameras and fills the remaining quad slots", async () => {
    listCamerasMock.mockResolvedValue(result([
      rawCamera(1, "camera-01"),
      rawCamera(2, "camera-02")
    ]));
    mount();
    await settle();

    expect(listCamerasMock).toHaveBeenCalledWith({ page: 0, size: 4, sort: "cameraCode,asc" });
    expect(getCameraStreamUrlMock).toHaveBeenCalledTimes(2);
    expect(root.querySelectorAll(".live-stream-card")).toHaveLength(4);
    expect(root.querySelectorAll(".empty-camera-slot")).toHaveLength(2);
    expect(root.querySelectorAll("video")).toHaveLength(2);
    expect(root.querySelectorAll("iframe")).toHaveLength(0);
  });

  it("isolates a stream URL failure to the affected camera", async () => {
    listCamerasMock.mockResolvedValue(result([
      rawCamera(1, "camera-01"),
      rawCamera(2, "camera-02")
    ]));
    getCameraStreamUrlMock.mockImplementation(async (cameraId) => {
      if (cameraId === 2) throw new Error("스트림 URL 조회 오류");
      return { streamUrl: "http://webviewer:test-secret@media.example:8888/camera-01" };
    });
    mount();
    await settle();

    expect(root.querySelectorAll("video")).toHaveLength(1);
    expect(root.textContent).toContain("스트림 URL 조회 오류");
    expect(root.querySelectorAll(".live-stream-card")).toHaveLength(4);
  });

  it("shows API DB status separately from player state", async () => {
    listCamerasMock.mockResolvedValue(result([
      rawCamera(1, "camera-01", { status: "ERROR" })
    ]));
    mount();
    await settle();

    root.querySelector('button[aria-label="카메라 정보"]').click();
    await settle();

    expect(root.textContent).toContain("DB 상태");
    expect(root.textContent).toContain("ERROR");
    expect(root.textContent).toContain("재생 상태");
  });

  it("does not request or create a player for an offline camera", async () => {
    listCamerasMock.mockResolvedValue(result([
      rawCamera(1, "camera-01", { status: "OFFLINE" })
    ]));
    mount();
    await settle();

    expect(getCameraStreamUrlMock).not.toHaveBeenCalled();
    expect(root.querySelector("video")).toBeNull();
    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("연결 없음");
    expect(root.textContent).toContain("Camera 01");
  });

  it("shows device error independently from actual ERROR camera playback", async () => {
    listCamerasMock.mockResolvedValue(result([
      rawCamera(1, "camera-01", { status: "ERROR" })
    ]));
    mount();
    await settle();

    expect(getCameraStreamUrlMock).toHaveBeenCalledWith(1);
    const deviceError = root.querySelector(".camera-device-error");
    expect(deviceError?.textContent).toContain("장치 오류");
    expect(deviceError?.classList.contains("status-amber-text")).toBe(true);
    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("연결 확인 중");

    root.querySelector("video").dispatchEvent(new Event("playing"));
    await settle();
    expect(root.querySelector(".live-badge")?.textContent).toContain("LIVE");
    expect(root.textContent).toContain("플레이어 로드됨");
    expect(root.textContent).toContain("장치 오류");

    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    hlsState.instances[0].handlers.error(null, { fatal: true, details: "networkError" });
    await settle();
    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("재생 오류");
    expect(root.textContent).toContain("장치 오류");
    consoleError.mockRestore();
  });

  it("shows LIVE for an online camera only after playing and removes it on failure", async () => {
    listCamerasMock.mockResolvedValue(result([rawCamera(1, "camera-01")]));
    mount();
    await settle();

    expect(getCameraStreamUrlMock).toHaveBeenCalledWith(1);
    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("연결 확인 중");

    root.querySelector("video").dispatchEvent(new Event("playing"));
    await settle();
    expect(root.querySelector(".live-badge")?.textContent).toContain("LIVE");

    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    hlsState.instances[0].handlers.error(null, { fatal: true, details: "manifestLoadError" });
    await settle();
    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("재생 오류");
    consoleError.mockRestore();
  });

  it("keeps the quad UI and retries after a camera-list error", async () => {
    listCamerasMock
      .mockRejectedValueOnce(new Error("CCTV API 오류"))
      .mockResolvedValueOnce(result([rawCamera(1, "camera-01")]));
    mount();
    await settle();

    expect(root.textContent).toContain("CCTV API 오류");
    expect(root.querySelectorAll(".live-stream-card")).toHaveLength(4);
    root.querySelector(".live-monitoring-notice button").click();
    await settle();

    expect(listCamerasMock).toHaveBeenCalledTimes(2);
    expect(root.textContent).toContain("Camera 01");
  });

  it("polls camera state and clears the timer on unmount", async () => {
    vi.useFakeTimers();
    listCamerasMock.mockResolvedValue(result([rawCamera(1, "camera-01")]));
    mount();
    await settle();

    expect(listCamerasMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    expect(listCamerasMock).toHaveBeenCalledTimes(2);

    app.unmount();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(listCamerasMock).toHaveBeenCalledTimes(2);
  });

  it("updates an active camera status without replacing its player element", async () => {
    const errorCamera = rawCamera(1, "camera-01", {
      status: "ERROR",
      lastHeartbeat: "2026-07-20T02:00:00Z"
    });
    vi.useFakeTimers();
    listCamerasMock
      .mockResolvedValueOnce(result([rawCamera(1, "camera-01")]))
      .mockResolvedValueOnce(result([errorCamera]));
    mount();
    await settle();

    const video = root.querySelector("video");
    await vi.advanceTimersByTimeAsync(10_000);
    await settle();

    expect(root.querySelector("video")).toBe(video);
    expect(hlsState.instances).toHaveLength(1);
    expect(root.textContent).toContain("장치 오류");
    root.querySelector('button[aria-label="카메라 정보"]').click();
    await settle();
    expect(root.textContent).toContain("ERROR");
    expect(root.textContent).toContain("2026-07-20 11:00");
  });

  it("disposes and restores the player across online-offline-error polling", async () => {
    vi.useFakeTimers();
    listCamerasMock
      .mockResolvedValueOnce(result([rawCamera(1, "camera-01")]))
      .mockResolvedValueOnce(result([
        rawCamera(1, "camera-01", { status: "OFFLINE" })
      ]))
      .mockResolvedValueOnce(result([
        rawCamera(1, "camera-01", { status: "ERROR" })
      ]));
    mount();
    await settle();

    const firstVideo = root.querySelector("video");
    const firstHls = hlsState.instances[0];
    expect(getCameraStreamUrlMock).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    expect(root.querySelector("video")).toBeNull();
    expect(firstHls.destroy).toHaveBeenCalledOnce();
    expect(getCameraStreamUrlMock).toHaveBeenCalledTimes(1);
    expect(root.textContent).toContain("연결 없음");

    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    expect(root.querySelector("video")).not.toBeNull();
    expect(root.querySelector("video")).not.toBe(firstVideo);
    expect(hlsState.instances).toHaveLength(2);
    expect(getCameraStreamUrlMock).toHaveBeenCalledTimes(2);
    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("장치 오류");
  });
});
