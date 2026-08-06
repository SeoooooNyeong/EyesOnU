import { createApp, nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const candidateApi = vi.hoisted(() => ({ fetchAdminCandidates: vi.fn() }));
const cameraApi = vi.hoisted(() => ({ listCameras: vi.fn() }));
const caseApi = vi.hoisted(() => ({ listCases: vi.fn() }));

vi.mock("../api/candidateApi", () => candidateApi);
vi.mock("../api/cameraApi", () => cameraApi);
vi.mock("../api/caseApi", () => caseApi);

import CandidatesView from "./CandidatesView.vue";

const mountedApps = [];
const rows = [
  { id: 1, caseNumber: "CASE-1", sourceType: "RECORDING_ANALYSIS", reviewStatus: "PENDING", bestSimilarity: 0.43, lastDetectedAt: "2026-08-05T08:03:00Z", cameraCode: "CAM-1", trackId: "track-1" },
  { id: 2, caseNumber: "CASE-1", sourceType: "REALTIME", reviewStatus: "CONFIRMED", bestSimilarity: 0.98, lastDetectedAt: "2026-08-05T08:03:00Z", cameraCode: "CAM-1", trackId: "track-2" }
];

async function flushUi() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve();
  await nextTick();
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/admin/candidates", component: CandidatesView },
      { path: "/admin/candidates/:candidateId", component: { template: "<div />" } }
    ]
  });
  await router.push("/admin/candidates");
  await router.isReady();
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(CandidatesView).use(router);
  app.mount(root);
  mountedApps.push({ app, root });
  await flushUi();
  return { root, router };
}

beforeEach(() => {
  vi.clearAllMocks();
  caseApi.listCases.mockResolvedValue({ data: [{ id: 17, caseNumber: "CASE-1", missingName: "홍길동" }] });
  cameraApi.listCameras.mockResolvedValue({ data: [{ id: 11, cameraCode: "CAM-1", cameraName: "정문" }] });
  candidateApi.fetchAdminCandidates.mockResolvedValue({ rows, meta: { totalPages: 1, totalElements: rows.length } });
});

afterEach(() => {
  for (const { app, root } of mountedApps.splice(0)) {
    app.unmount();
    root.remove();
  }
});

describe("CandidatesView", () => {
  it("shows queue controls and collapses confirmed candidates by default", async () => {
    const { root } = await mountView();

    expect(root.textContent).toContain("미판정 우선");
    expect(root.textContent).toContain("다음 미판정 후보");
    expect(root.textContent).toContain("카메라");
    expect(root.textContent).toContain("탐지 시작");
    expect(root.textContent).toContain("확정·제외 후보 1건");
    expect(root.querySelectorAll(".candidate-card")).toHaveLength(1);

    root.querySelector(".resolved-candidates-toggle button").click();
    await flushUi();

    expect(root.querySelectorAll(".candidate-card")).toHaveLength(2);
  });

  it("opens the first pending candidate from the queue action", async () => {
    const { root, router } = await mountView();

    root.querySelector(".candidate-heading-actions .primary-button").click();
    await flushUi();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(router.currentRoute.value.path).toBe("/admin/candidates/1");
  });

  it("uses the selected camera, date range, and sort in the request", async () => {
    const { root } = await mountView();
    const selects = root.querySelectorAll("select");
    const cameraSelect = [...selects].find((select) => select.value === "");
    cameraSelect.value = "11";
    cameraSelect.dispatchEvent(new Event("change", { bubbles: true }));
    const sortSelect = [...selects].find((select) => [...select.options].some((option) => option.value === "bestSimilarity,desc"));
    sortSelect.value = "bestSimilarity,desc";
    sortSelect.dispatchEvent(new Event("change", { bubbles: true }));
    const dates = root.querySelectorAll('input[type="datetime-local"]');
    dates[0].value = "2026-08-05T17:00";
    dates[0].dispatchEvent(new Event("input", { bubbles: true }));
    dates[1].value = "2026-08-05T18:00";
    dates[1].dispatchEvent(new Event("input", { bubbles: true }));
    await flushUi();

    expect(candidateApi.fetchAdminCandidates).toHaveBeenLastCalledWith(expect.objectContaining({
      cameraId: "11",
      sort: "bestSimilarity,desc",
      detectedFrom: new Date("2026-08-05T17:00").toISOString(),
      detectedTo: new Date("2026-08-05T18:00").toISOString()
    }));
  });
});
