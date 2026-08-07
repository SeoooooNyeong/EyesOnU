import { createApp, nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const candidateApi = vi.hoisted(() => ({
  fetchAdminCandidate: vi.fn(),
  reviewAdminCandidate: vi.fn()
}));

vi.mock("../api/candidateApi", () => candidateApi);
vi.mock("../components/routes/RouteMap.vue", () => ({
  default: { template: "<div class='mock-route-map' />" }
}));

import CandidateDetailView from "./CandidateDetailView.vue";

const mountedApps = [];

const candidate = {
  id: 501,
  caseId: 17,
  caseNumber: "EFU-20260805-0001",
  missingName: "홍길동",
  missingPhotoUrl: "https://storage.example/missing.jpg",
  cameraCode: "CAM-001",
  cameraName: "정문 CCTV",
  cameraLatitude: 37.5,
  cameraLongitude: 127.03,
  cameraAddress: "서울시 강남구 정문 앞",
  sourceType: "RECORDING_ANALYSIS",
  lastDetectedAt: "2026-08-05T03:20:00Z",
  firstDetectedAt: "2026-08-05T03:19:30Z",
  bestSimilarity: 0.913,
  averageSimilarity: 0.88,
  detectionCount: 3,
  trackId: "track-1",
  frameUrl: "https://storage.example/frame.jpg",
  cropUrl: "https://storage.example/crop.jpg",
  clipUrl: "https://storage.example/clip.mp4",
  clipStatus: "READY",
  reviewStatus: "PENDING",
  reviewComment: "",
  version: 1,
  detections: [{ eventId: "event-1", detectedAt: "2026-08-05T03:20:00Z", similarity: 0.913, cropUrl: "https://storage.example/crop-1.jpg" }]
};

async function flushUi() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve();
  await nextTick();
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/admin/candidates/:candidateId", component: CandidateDetailView },
      { path: "/admin/candidates", component: { template: "<div />" } }
    ]
  });
  await router.push("/admin/candidates/501");
  await router.isReady();
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(CandidateDetailView).use(router);
  app.mount(root);
  mountedApps.push({ app, root });
  await flushUi();
  return { root, router };
}

beforeEach(() => {
  vi.clearAllMocks();
  candidateApi.fetchAdminCandidate.mockResolvedValue({ ...candidate });
});

afterEach(() => {
  for (const { app, root } of mountedApps.splice(0)) {
    app.unmount();
    root.remove();
  }
});

describe("CandidateDetailView", () => {
  it("판정에 필요한 사진·클립·출처·지도·주의 안내를 함께 표시한다", async () => {
    const { root } = await mountView();

    expect(root.textContent).toContain("실종자 기준 사진");
    expect(root.textContent).toContain("AI 후보 사진");
    expect(root.textContent).toContain("탐지 전후 영상 클립");
    expect(root.textContent).toContain("녹화");
    expect(root.textContent).not.toContain("출처 · 녹화");
    expect(root.textContent).toContain("AI 유사도는 신원 확정의 근거가 아닙니다");
    expect(root.textContent).toContain("CCTV 위치");
    expect(root.querySelector('img[alt="홍길동 기준 사진"]').getAttribute("src")).toBe(candidate.missingPhotoUrl);
    expect(root.querySelector('img[alt="AI 후보 크롭 이미지"]').getAttribute("src")).toBe(candidate.cropUrl);
    expect(root.querySelector("video").getAttribute("src")).toBe(candidate.clipUrl);
    expect(root.querySelector(".candidate-video-review-row").children).toHaveLength(2);
    expect(root.querySelector(".candidate-video-review-row .candidate-review-form")).not.toBeNull();
    expect(root.querySelector(".candidate-kakao-map .mock-route-map")).not.toBeNull();
    expect(root.querySelector(".candidate-map-history-row").children).toHaveLength(2);
    expect(root.querySelector(".candidate-photo-compare").children).toHaveLength(3);
    expect(root.querySelector(".candidate-photo-compare .candidate-info-panel")).not.toBeNull();
    expect(root.textContent).toContain("원본 프레임 보기");
    expect(root.textContent).not.toContain("원본 프레임 크게 보기");
    expect(root.textContent).not.toContain("다음 미판정 후보");
    expect(root.querySelector('img[alt="탐지 원본 프레임"]')).toBeNull();
    expect(root.textContent).not.toContain("PENDING");
    expect(root.textContent).not.toContain("2026-08-05T03:20:00Z");
  });

  it("blocks a short confirmation evidence comment", async () => {
    const { root } = await mountView();
    const select = root.querySelector("select");
    const textarea = root.querySelector("textarea");

    select.value = "CONFIRMED";
    select.dispatchEvent(new Event("change", { bubbles: true }));
    textarea.value = "ttt";
    textarea.dispatchEvent(new Event("input", { bubbles: true }));
    await flushUi();

    expect(root.textContent).toContain("대상 확인 시 판정 근거를 10자 이상 입력해 주세요.");
    expect(root.querySelector('button[type="submit"]').disabled).toBe(true);
    expect(candidateApi.reviewAdminCandidate).not.toHaveBeenCalled();
  });

  it("renders a candidate whose confirmation evidence is null", async () => {
    candidateApi.fetchAdminCandidate.mockResolvedValue({
      ...candidate,
      reviewStatus: "CONFIRMED",
      reviewComment: null
    });

    const { root } = await mountView();

    expect(root.textContent).toContain("대상 확인 시 판정 근거를 10자 이상 입력해 주세요.");
    expect(root.querySelector('button[type="submit"]').disabled).toBe(true);
  });

  it("클립이 없으면 원본 녹화본을 탐지 전후 구간 재생 대상으로 안내한다", async () => {
    candidateApi.fetchAdminCandidate.mockResolvedValue({
      ...candidate,
      clipUrl: null,
      clipStatus: "PENDING",
      recordingUrl: "https://storage.example/recording.mp4",
      recordingStartTime: "2026-08-05T03:00:00Z",
      recordingEndTime: "2026-08-05T04:00:00Z"
    });
    const { root } = await mountView();

    expect(root.textContent).toContain("원본 녹화본에서 탐지 시각 전후 구간을 재생합니다");
    expect(root.textContent).toContain("탐지 전후 구간 재생");
    expect(root.querySelector("video").getAttribute("src")).toBe("https://storage.example/recording.mp4");
  });

  it("reloads the detail when only the candidate route parameter changes", async () => {
    const nextCandidate = { ...candidate, id: 502, caseNumber: "EFU-20260805-0002" };
    candidateApi.fetchAdminCandidate
      .mockResolvedValueOnce({ ...candidate })
      .mockResolvedValueOnce(nextCandidate);
    const { root, router } = await mountView();

    await router.push("/admin/candidates/502");
    await flushUi();

    expect(candidateApi.fetchAdminCandidate).toHaveBeenLastCalledWith("502");
    expect(root.textContent).toContain(nextCandidate.caseNumber);
    expect(root.textContent).not.toContain(candidate.caseNumber);
  });
});
