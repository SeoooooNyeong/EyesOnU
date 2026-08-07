import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { loadKakaoMapsSdkMock, mapCalls } = vi.hoisted(() => ({
  loadKakaoMapsSdkMock: vi.fn(),
  mapCalls: {
    maps: [],
    polylines: [],
    overlays: [],
    bounds: []
  }
}));

vi.mock("../../lib/kakaoMaps", () => ({ loadKakaoMapsSdk: loadKakaoMapsSdkMock }));

import RouteMap from "./RouteMap.vue";

const points = [
  { candidateId: 1, latitude: 37.5, longitude: 127.0 },
  { candidateId: 2, latitude: 37.5, longitude: 127.0 },
  { candidateId: 3, latitude: 37.51, longitude: 127.01 }
];

function fakeKakao() {
  class LatLng {
    constructor(latitude, longitude) {
      this.latitude = latitude;
      this.longitude = longitude;
    }
  }
  class LatLngBounds {
    constructor() {
      this.positions = [];
      mapCalls.bounds.push(this);
    }
    extend(position) { this.positions.push(position); }
  }
  class Map {
    constructor(element, options) {
      this.element = element;
      this.options = options;
      this.setBounds = vi.fn();
      this.setCenter = vi.fn();
      this.setLevel = vi.fn();
      this.panTo = vi.fn();
      mapCalls.maps.push(this);
    }
  }
  class Polyline {
    constructor(options) {
      this.options = options;
      this.setMap = vi.fn();
      mapCalls.polylines.push(this);
    }
  }
  class CustomOverlay {
    constructor(options) {
      this.options = options;
      this.setMap = vi.fn();
      mapCalls.overlays.push(this);
    }
  }
  return { maps: { LatLng, LatLngBounds, Map, Polyline, CustomOverlay } };
}

async function settle() {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
    await nextTick();
  }
}

function trackButtonListeners() {
  const createElement = document.createElement.bind(document);
  const buttons = [];
  vi.spyOn(document, "createElement").mockImplementation((tagName, options) => {
    const element = createElement(tagName, options);
    if (String(tagName).toLowerCase() === "button") {
      vi.spyOn(element, "addEventListener");
      vi.spyOn(element, "removeEventListener");
      buttons.push(element);
    }
    return element;
  });
  return buttons;
}

function registeredClickHandler(element) {
  return element.addEventListener.mock.calls.find(([type]) => type === "click")?.[1];
}

describe("RouteMap", () => {
  let app;
  let root;

  function mount(props = {}) {
    const state = reactive({ points, activeIndex: -1, ...props });
    root = document.createElement("div");
    document.body.appendChild(root);
    app = createApp({
      render: () => h(RouteMap, {
        points: state.points,
        activeIndex: state.activeIndex
      })
    });
    app.mount(root);
    return state;
  }

  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_MAP_APP_KEY", "test-javascript-key");
    Object.values(mapCalls).forEach((items) => { items.length = 0; });
    loadKakaoMapsSdkMock.mockResolvedValue(fakeKakao());
  });

  afterEach(() => {
    app?.unmount();
    root?.remove();
    vi.unstubAllEnvs();
  });

  it("좌표를 시간순 직선으로 그리고 동일 좌표 마커를 하나로 묶는다", async () => {
    mount();
    await settle();

    expect(loadKakaoMapsSdkMock).toHaveBeenCalledWith("test-javascript-key");
    expect(mapCalls.polylines).toHaveLength(1);
    expect(mapCalls.polylines[0].options.path).toHaveLength(3);
    expect(mapCalls.overlays).toHaveLength(2);
    expect(mapCalls.overlays[0].options.content.querySelector("img").getAttribute("src")).toBe("/cctv-marker.webp");
    expect(mapCalls.overlays[0].options.content.querySelector(".route-coordinate-marker-label").textContent).toBe("1·2");
    expect(mapCalls.bounds[0].positions).toHaveLength(3);
    expect(mapCalls.maps[0].setBounds).toHaveBeenCalled();
  });

  it("활성 관측점 마커를 강조하고 해당 좌표로 이동한다", async () => {
    const state = mount();
    await settle();

    state.activeIndex = 2;
    await settle();

    expect(mapCalls.overlays[1].options.content.classList.contains("active")).toBe(true);
    expect(mapCalls.maps[0].panTo).toHaveBeenCalledWith(expect.objectContaining({
      latitude: 37.51,
      longitude: 127.01
    }));
  });

  it("지도 키가 없으면 SDK를 호출하지 않고 목록 사용 안내를 표시한다", async () => {
    vi.stubEnv("VITE_KAKAO_MAP_APP_KEY", "");
    mount();
    await settle();

    expect(loadKakaoMapsSdkMock).not.toHaveBeenCalled();
    expect(root.textContent).toContain("지도 대신 동선 목록만 표시합니다");
  });

  it("SDK 로딩 실패 시 지도 오류 안내를 표시한다", async () => {
    loadKakaoMapsSdkMock.mockRejectedValue(new Error("sdk failed"));
    mount();
    await settle();

    expect(root.textContent).toContain("카카오맵을 불러오지 못했습니다");
  });

  it("포인트를 다시 그릴 때 기존 마커의 클릭 리스너를 해제한다", async () => {
    const buttons = trackButtonListeners();
    const state = mount();
    await settle();
    const previousButtons = [...buttons];
    const previousHandlers = previousButtons.map(registeredClickHandler);

    state.points = [{ candidateId: 4, latitude: 37.52, longitude: 127.02 }];
    await settle();

    previousButtons.forEach((element, index) => {
      expect(element.removeEventListener).toHaveBeenCalledWith("click", previousHandlers[index]);
    });
  });

  it("언마운트할 때 현재 마커의 클릭 리스너를 해제한다", async () => {
    const buttons = trackButtonListeners();
    mount();
    await settle();
    const handlers = buttons.map(registeredClickHandler);

    app.unmount();
    app = undefined;

    buttons.forEach((element, index) => {
      expect(element.removeEventListener).toHaveBeenCalledWith("click", handlers[index]);
    });
  });
});
