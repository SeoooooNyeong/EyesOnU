import { createApp, h, nextTick, ref } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const hlsState = vi.hoisted(() => ({
  instances: [],
  supported: true
}));

vi.mock("hls.js", () => {
  const Events = {
    ERROR: "error",
    MANIFEST_PARSED: "manifest-parsed"
  };

  class HlsMock {
    static Events = Events;

    static isSupported() {
      return hlsState.supported;
    }

    constructor(config = {}) {
      this.config = config;
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

import LiveStreamPlayer from "./LiveStreamPlayer.vue";

const settle = async () => {
  for (let index = 0; index < 4; index += 1) {
    await Promise.resolve();
    await nextTick();
  }
};

describe("LiveStreamPlayer", () => {
  let app;
  let root;
  let consoleErrorSpy;

  beforeEach(() => {
    hlsState.instances.length = 0;
    hlsState.supported = true;
    root = document.createElement("div");
    document.body.appendChild(root);
    vi.spyOn(window.HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
    consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    app?.unmount();
    root?.remove();
    consoleErrorSpy?.mockRestore();
  });

  it("adds the supplied Basic authorization header to HLS.js requests", async () => {
    const authorization = `Basic ${btoa("webviewer:test-secret")}`;
    app = createApp(LiveStreamPlayer, {
      protocol: "HLS",
      url: "/media-stream/camera-01/index.m3u8",
      authorization
    });
    app.mount(root);
    await settle();

    const instance = hlsState.instances[0];
    const xhr = { setRequestHeader: vi.fn() };
    instance.config.xhrSetup(xhr);

    expect(xhr.setRequestHeader).toHaveBeenCalledWith("Authorization", authorization);
    expect(instance.loadSource).toHaveBeenCalledWith("/media-stream/camera-01/index.m3u8");
    expect(instance.attachMedia).toHaveBeenCalledWith(root.querySelector("video"));
  });

  it("restarts HLS.js when authorization changes without replacing the video element", async () => {
    const authorization = ref(`Basic ${btoa("webviewer:old-secret")}`);
    const Wrapper = {
      setup: () => () => h(LiveStreamPlayer, {
        protocol: "HLS",
        url: "/media-stream/camera-01/index.m3u8",
        authorization: authorization.value
      })
    };
    app = createApp(Wrapper);
    app.mount(root);
    await settle();
    const video = root.querySelector("video");
    const firstInstance = hlsState.instances[0];

    authorization.value = `Basic ${btoa("webviewer:new-secret")}`;
    await settle();

    expect(firstInstance.destroy).toHaveBeenCalledOnce();
    expect(hlsState.instances).toHaveLength(2);
    expect(root.querySelector("video")).toBe(video);
  });

  it("does not place credentials in the native video URL when HLS.js is unavailable", async () => {
    hlsState.supported = false;
    app = createApp(LiveStreamPlayer, {
      protocol: "HLS",
      url: "/media-stream/camera-01/index.m3u8",
      authorization: `Basic ${btoa("webviewer:test-secret")}`
    });
    app.mount(root);
    await settle();

    expect(root.querySelector("video").getAttribute("src")).toBeNull();
    expect(root.textContent).toContain("스트림 연결에 실패했습니다.");
  });

  it("renders LIVE only after playing and removes it after a fatal HLS error", async () => {
    app = createApp(LiveStreamPlayer, {
      protocol: "HLS",
      url: "/media-stream/camera-01/index.m3u8"
    });
    app.mount(root);
    await settle();

    expect(root.querySelector(".live-badge")).toBeNull();
    root.querySelector("video").dispatchEvent(new Event("playing"));
    await settle();
    expect(root.querySelector(".live-badge")?.textContent).toContain("LIVE");

    hlsState.instances[0].handlers.error(null, { fatal: true, details: "networkError" });
    await settle();
    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("스트림 연결에 실패했습니다.");
  });

  it("keeps LIVE hidden when video.play rejects after the manifest loads", async () => {
    window.HTMLMediaElement.prototype.play.mockRejectedValueOnce(new Error("autoplay blocked"));
    app = createApp(LiveStreamPlayer, {
      protocol: "HLS",
      url: "/media-stream/camera-01/index.m3u8"
    });
    app.mount(root);
    await settle();

    hlsState.instances[0].handlers["manifest-parsed"]();
    await settle();

    expect(root.querySelector(".live-badge")).toBeNull();
    expect(root.textContent).toContain("스트림 연결에 실패했습니다.");
  });

  it("destroys HLS resources when the player is unmounted", async () => {
    app = createApp(LiveStreamPlayer, {
      protocol: "HLS",
      url: "/media-stream/camera-01/index.m3u8"
    });
    app.mount(root);
    await settle();
    const instance = hlsState.instances[0];

    app.unmount();
    app = null;

    expect(instance.destroy).toHaveBeenCalledOnce();
  });
});
