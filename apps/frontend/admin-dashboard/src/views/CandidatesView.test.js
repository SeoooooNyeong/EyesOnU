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
  it("collapses confirmed candidates by default", async () => {
    const { root } = await mountView();

    expect(root.textContent).toContain("미판정 우선");
    expect(root.textContent).toContain("카메라");
    expect(root.textContent).toContain("확정·제외 후보 1건");
    expect(root.textContent).not.toContain("track");
    expect(root.querySelectorAll(".candidate-card")).toHaveLength(1);

    root.querySelector(".resolved-candidates-toggle").click();
    await flushUi();

    expect(root.querySelectorAll(".candidate-card")).toHaveLength(2);
  });

  it("keeps the resolved-candidate toggle in place when there are no resolved candidates", async () => {
    candidateApi.fetchAdminCandidates.mockResolvedValueOnce({ rows: [rows[0]], meta: { totalPages: 1, totalElements: 1 } });
    const { root } = await mountView();

    const toggle = root.querySelector(".resolved-candidates-toggle");
    expect(toggle).not.toBeNull();
    expect(toggle.disabled).toBe(true);
    expect(root.querySelector(".candidate-list-toolbar")).not.toBeNull();
  });

  it("uses the existing green and blue tones for source filters", async () => {
    const { root } = await mountView();
    const realtimeButton = root.querySelector(".candidate-source-option.green");
    const recordingButton = root.querySelector(".candidate-source-option.blue");

    expect(root.querySelector(".candidate-source-filter-label").textContent).toContain("영상 유형");
    expect(root.querySelector(".candidate-list-actions .candidate-sort-select")).not.toBeNull();
    expect(root.querySelector(".candidate-list-actions .resolved-candidates-toggle")).not.toBeNull();
    expect(realtimeButton).not.toBeNull();
    expect(recordingButton).not.toBeNull();
    realtimeButton.click();
    await flushUi();

    expect(realtimeButton.classList.contains("active")).toBe(true);
    expect(candidateApi.fetchAdminCandidates).toHaveBeenLastCalledWith(expect.objectContaining({ sourceType: "REALTIME" }));
  });

  it("uses the selected camera and sort in the request", async () => {
    const { root } = await mountView();
    const selects = root.querySelectorAll("select");
    const cameraSelect = [...selects].find((select) => select.value === "");
    cameraSelect.value = "11";
    cameraSelect.dispatchEvent(new Event("change", { bubbles: true }));
    const sortSelect = [...selects].find((select) => [...select.options].some((option) => option.value === "bestSimilarity,desc"));
    sortSelect.value = "bestSimilarity,desc";
    sortSelect.dispatchEvent(new Event("change", { bubbles: true }));
    await flushUi();

    expect(candidateApi.fetchAdminCandidates).toHaveBeenLastCalledWith(expect.objectContaining({
      cameraId: "11",
      sort: "bestSimilarity,desc"
    }));
  });

  it("translates server validation errors into Korean guidance", async () => {
    candidateApi.fetchAdminCandidates.mockRejectedValueOnce(new Error(
      "sort must be one of lastDetectedAt, firstDetectedAt, bestSimilarity, createdAt with asc or desc"
    ));
    const { root } = await mountView();

    expect(root.querySelector(".error-message").textContent).toContain("정렬 기준을 불러오지 못했습니다");
    expect(root.textContent).not.toContain("sort must be one of");
  });
});
