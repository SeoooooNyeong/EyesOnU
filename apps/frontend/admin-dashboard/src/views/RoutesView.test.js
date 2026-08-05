import { createApp, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getCaseRoute: vi.fn(),
  listCases: vi.fn(),
  routerPush: vi.fn(),
  routerReplace: vi.fn(),
  route: { query: {} }
}));

vi.mock("../api/caseApi", () => ({ listCases: mocks.listCases }));
vi.mock("../api/routeApi", () => ({ getCaseRoute: mocks.getCaseRoute }));
vi.mock("vue-router", () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.routerPush, replace: mocks.routerReplace })
}));
vi.mock("../components/routes/RouteMap.vue", () => ({
  default: {
    props: ["points", "activeIndex"],
    emits: ["select"],
    template: '<div class="route-map-stub">지도 {{ points.length }}개 / 활성 {{ activeIndex }}</div>'
  }
}));

import RoutesView from "./RoutesView.vue";

const cases = [
  { id: 17, caseNumber: "EFU-17", missingName: "김민수", lastSeenAddress: "서울역" },
  { id: 18, caseNumber: "EFU-18", missingName: "이영희", lastSeenAddress: "강남역" }
];
const point = (overrides = {}) => ({
  candidateId: 501,
  sourceType: "REALTIME",
  cameraId: 1,
  cameraCode: "camera-01",
  cameraName: "정문 카메라",
  address: "서울시 강남구",
  latitude: 37.5,
  longitude: 127.0,
  firstDetectedAt: "2026-08-05T01:00:00Z",
  lastDetectedAt: "2026-08-05T01:00:30Z",
  bestSimilarity: 0.91,
  ...overrides
});

async function settle() {
  for (let index = 0; index < 6; index += 1) {
    await Promise.resolve();
    await nextTick();
  }
}

describe("RoutesView", () => {
  let app;
  let root;

  async function mount() {
    root = document.createElement("div");
    document.body.appendChild(root);
    app = createApp(RoutesView);
    app.mount(root);
    await settle();
  }

  beforeEach(() => {
    mocks.route.query = {};
    mocks.listCases.mockResolvedValue({ data: cases, meta: {} });
    mocks.getCaseRoute.mockResolvedValue({
      caseId: 17,
      caseNumber: "EFU-17",
      missingName: "김민수",
      points: [point(), point({ candidateId: 502, cameraCode: "camera-02", cameraName: "후문 카메라" })]
    });
  });

  afterEach(() => {
    app?.unmount();
    root?.remove();
    vi.useRealTimers();
  });

  it("URL 사건을 선택해 실제 동선과 후보 근거 링크를 표시한다", async () => {
    mocks.route.query = { caseId: "17" };
    await mount();

    expect(mocks.listCases).toHaveBeenCalledWith({ page: 0, size: 100, sort: "reportedAt,desc" });
    expect(mocks.getCaseRoute).toHaveBeenCalledWith("17");
    expect(root.textContent).toContain("정문 카메라");
    expect(root.textContent).toContain("후문 카메라");
    expect(root.textContent).toContain("유사도 91%");
    expect(root.textContent).toContain("실제 도로 이동 경로가 아닙니다");
    expect(root.textContent).not.toContain("동선 제외");
    expect(root.textContent).not.toContain("동선 정보 공유");

    [...root.querySelectorAll("button")].find((button) => button.textContent.includes("후보 근거 보기")).click();
    expect(mocks.routerPush).toHaveBeenCalledWith("/admin/candidates/501");
  });

  it("사건을 변경하면 해당 사건의 동선을 다시 조회한다", async () => {
    await mount();
    root.querySelector(".case-picker-trigger").click();
    await nextTick();
    [...root.querySelectorAll(".case-picker-option")].find((button) => button.textContent.includes("EFU-18")).click();
    await settle();

    expect(mocks.getCaseRoute).toHaveBeenLastCalledWith("18");
    expect(mocks.routerReplace).toHaveBeenLastCalledWith({ query: { caseId: "18" } });
  });

  it("확정 후보가 없으면 조회 전용 빈 상태를 표시한다", async () => {
    mocks.getCaseRoute.mockResolvedValue({ caseId: 17, points: [] });
    await mount();

    expect(root.textContent).toContain("확정된 동선이 없습니다");
    expect(root.querySelector(".route-follow-link").disabled).toBe(true);
  });

  it("동선 조회 실패를 표시하고 다시 시도한다", async () => {
    mocks.getCaseRoute
      .mockRejectedValueOnce(new Error("동선 조회 오류"))
      .mockResolvedValueOnce({ caseId: 17, points: [point()] });
    await mount();

    expect(root.textContent).toContain("동선 조회 오류");
    [...root.querySelectorAll("button")].find((button) => button.textContent.includes("다시 시도")).click();
    await settle();

    expect(mocks.getCaseRoute).toHaveBeenCalledTimes(2);
    expect(root.textContent).toContain("정문 카메라");
  });

  it("동선 따라보기를 시간순으로 진행하고 해제한다", async () => {
    vi.useFakeTimers();
    await mount();
    const button = root.querySelector(".route-follow-link");

    button.click();
    await nextTick();
    expect(root.textContent).toContain("활성 0");
    vi.advanceTimersByTime(2200);
    await nextTick();
    expect(root.textContent).toContain("활성 1");

    button.click();
    await nextTick();
    expect(root.textContent).toContain("활성 -1");
  });
});
