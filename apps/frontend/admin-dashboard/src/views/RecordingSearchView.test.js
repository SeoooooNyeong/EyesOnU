import { createApp, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const {
  cancelRecordingAnalysisJobMock,
  createRecordingAnalysisJobMock,
  fetchRecordingAnalysisJobMock,
  listAdminRecordingsMock,
  listCaseCamerasMock,
  listCasesMock,
  listRecordingAnalysisJobsMock,
  listRecordingAnalysisJobsForDashboardMock,
  listSearchConditionsMock,
  retryRecordingAnalysisJobMock,
  routerPushMock
} = vi.hoisted(() => ({
  cancelRecordingAnalysisJobMock: vi.fn(),
  createRecordingAnalysisJobMock: vi.fn(),
  fetchRecordingAnalysisJobMock: vi.fn(),
  listAdminRecordingsMock: vi.fn(),
  listCaseCamerasMock: vi.fn(),
  listCasesMock: vi.fn(),
  listRecordingAnalysisJobsMock: vi.fn(),
  listRecordingAnalysisJobsForDashboardMock: vi.fn(),
  listSearchConditionsMock: vi.fn(),
  retryRecordingAnalysisJobMock: vi.fn(),
  routerPushMock: vi.fn()
}));

vi.mock("../api/caseApi", () => ({
  listCases: listCasesMock,
  listCaseCameras: listCaseCamerasMock,
  listSearchConditions: listSearchConditionsMock
}));

vi.mock("../api/recordingApi", () => ({
  cancelRecordingAnalysisJob: cancelRecordingAnalysisJobMock,
  createRecordingAnalysisJob: createRecordingAnalysisJobMock,
  fetchRecordingAnalysisJob: fetchRecordingAnalysisJobMock,
  listAdminRecordings: listAdminRecordingsMock,
  listRecordingAnalysisJobs: listRecordingAnalysisJobsMock,
  listRecordingAnalysisJobsForDashboard: listRecordingAnalysisJobsForDashboardMock,
  retryRecordingAnalysisJob: retryRecordingAnalysisJobMock
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: routerPushMock })
}));

import RecordingSearchView from "./RecordingSearchView.vue";

const caseItem = {
  id: 1,
  caseNumber: "EFU-001",
  missingName: "홍길동",
  reportedAt: "2026-08-05T01:00:00Z"
};

const condition = {
  id: 10,
  prompt: "흰색 상의와 검은색 바지",
  searchArea: "정문",
  searchStart: "2026-08-04T10:00:00Z",
  searchEnd: "2026-08-04T11:00:00Z"
};

const camera = (overrides = {}) => ({
  id: 30,
  caseId: 1,
  cameraId: 4,
  cameraCode: "camera-04",
  cameraName: "정문 카메라",
  searchEnabled: true,
  removedAt: null,
  ...overrides
});

const recording = (overrides = {}) => ({
  id: 20,
  camera: {
    id: 4,
    cameraCode: "camera-04",
    cameraName: "정문 카메라"
  },
  startTime: "2026-08-04T10:14:32Z",
  endTime: "2026-08-04T10:14:46Z",
  ...overrides
});

const job = (overrides = {}) => ({
  jobId: 501,
  caseId: 1,
  conditionId: 10,
  recordingId: 20,
  status: "QUEUED",
  requestedAt: "2026-08-05T04:00:00Z",
  ...overrides
});

const page = (data, meta = {}) => ({
  data,
  meta: {
    page: 0,
    size: 100,
    totalElements: data.length,
    totalPages: data.length ? 1 : 0,
    sort: "startTime,asc",
    ...meta
  }
});

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};

const settle = async () => {
  for (let index = 0; index < 10; index += 1) {
    await Promise.resolve();
    await nextTick();
  }
};

describe("RecordingSearchView", () => {
  let app;
  let root;

  const mount = () => {
    root = document.createElement("div");
    document.body.appendChild(root);
    app = createApp(RecordingSearchView);
    app.mount(root);
  };

  const selectCase = async () => {
    root.querySelector(".case-picker-trigger").click();
    await nextTick();
    root.querySelectorAll(".case-picker-option")[1].click();
    await settle();
  };

  const selectCamera = async (cameraId = 4) => {
    const select = root.querySelector(".recording-filter-time select");
    select.value = String(cameraId);
    select.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();
  };

  const selectCaseAndCamera = async () => {
    await selectCase();
    await selectCamera();
  };

  beforeEach(() => {
    vi.clearAllMocks();
    listCasesMock.mockResolvedValue(page([caseItem]));
    listRecordingAnalysisJobsForDashboardMock.mockResolvedValue([]);
    listRecordingAnalysisJobsMock.mockResolvedValue([]);
    listSearchConditionsMock.mockResolvedValue([condition]);
    listCaseCamerasMock.mockResolvedValue([camera()]);
    listAdminRecordingsMock.mockResolvedValue(page([recording()]));
    createRecordingAnalysisJobMock.mockImplementation((caseId, payload) => Promise.resolve(job({
      jobId: 500 + payload.recordingId,
      caseId: Number(caseId),
      recordingId: payload.recordingId
    })));
  });

  afterEach(() => {
    app?.unmount();
    root?.remove();
  });

  it("loads the selected camera recordings and registers every target", async () => {
    mount();
    await settle();
    await selectCase();

    expect(listAdminRecordingsMock).not.toHaveBeenCalled();
    expect(root.querySelector(".search-button").disabled).toBe(true);

    await selectCamera();

    expect(listAdminRecordingsMock).toHaveBeenCalledWith({
      cameraId: 4,
      startFrom: condition.searchStart,
      startTo: condition.searchEnd,
      page: 0,
      size: 100,
      sort: "startTime,asc"
    });
    expect(root.textContent).toContain("분석 대상 녹화본 1개");

    root.querySelector(".search-button").click();
    await settle();

    expect(listRecordingAnalysisJobsMock).toHaveBeenCalledWith("1");
    expect(createRecordingAnalysisJobMock).toHaveBeenCalledWith("1", {
      conditionId: 10,
      recordingId: 20
    });
    expect(root.textContent).toContain("생성 1건 · 이미 요청됨 0건 · 실패 0건");
    expect(root.textContent).toContain("JOB-520");
  });

  it("loads every recording page, removes duplicates, and submits in chronological order", async () => {
    const early = recording({ id: 21, startTime: "2026-08-04T10:01:00Z" });
    const middle = recording({ id: 22, startTime: "2026-08-04T10:10:00Z" });
    const late = recording({ id: 23, startTime: "2026-08-04T10:20:00Z" });
    listAdminRecordingsMock.mockImplementation(({ page: requestedPage }) => Promise.resolve(
      requestedPage === 0
        ? page([late, early], { page: 0, totalElements: 4, totalPages: 2 })
        : page([middle, late], { page: 1, totalElements: 4, totalPages: 2 })
    ));

    mount();
    await settle();
    await selectCaseAndCamera();

    expect(listAdminRecordingsMock).toHaveBeenCalledTimes(2);
    expect(listAdminRecordingsMock.mock.calls[1][0].page).toBe(1);
    expect(root.textContent).toContain("분석 대상 녹화본 3개");

    root.querySelector(".search-button").click();
    await settle();

    expect(createRecordingAnalysisJobMock.mock.calls.map((call) => call[1].recordingId)).toEqual([21, 22, 23]);
  });

  it("keeps recording job creation concurrency at one", async () => {
    listAdminRecordingsMock.mockResolvedValue(page([
      recording({ id: 20, startTime: "2026-08-04T10:01:00Z" }),
      recording({ id: 21, startTime: "2026-08-04T10:02:00Z" })
    ]));
    const first = deferred();
    const second = deferred();
    createRecordingAnalysisJobMock
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    mount();
    await settle();
    await selectCaseAndCamera();
    root.querySelector(".search-button").click();
    await settle();

    expect(createRecordingAnalysisJobMock).toHaveBeenCalledTimes(1);
    expect(root.textContent).toContain("0 / 2건 처리했습니다.");

    first.resolve(job({ jobId: 520, recordingId: 20 }));
    await settle();
    expect(createRecordingAnalysisJobMock).toHaveBeenCalledTimes(2);
    expect(root.textContent).toContain("1 / 2건 처리했습니다.");

    second.resolve(job({ jobId: 521, recordingId: 21 }));
    await settle();
    expect(root.textContent).toContain("생성 2건 · 이미 요청됨 0건 · 실패 0건");
  });

  it("skips active jobs and recreates targets whose previous jobs are terminal", async () => {
    listAdminRecordingsMock.mockResolvedValue(page([
      recording({ id: 20 }),
      recording({ id: 21, startTime: "2026-08-04T10:15:00Z" }),
      recording({ id: 22, startTime: "2026-08-04T10:16:00Z" })
    ]));
    listRecordingAnalysisJobsMock.mockResolvedValue([
      job({ jobId: 500, recordingId: 20, status: "RUNNING" }),
      job({ jobId: 501, recordingId: 21, status: "SUCCEEDED" }),
      job({ jobId: 502, recordingId: 22, status: "CANCELLED" })
    ]);

    mount();
    await settle();
    await selectCaseAndCamera();
    root.querySelector(".search-button").click();
    await settle();

    expect(createRecordingAnalysisJobMock.mock.calls.map((call) => call[1].recordingId)).toEqual([21, 22]);
    expect(root.textContent).toContain("생성 2건 · 이미 요청됨 1건 · 실패 0건");
  });

  it("continues after an individual failure and reports failed recording details", async () => {
    listAdminRecordingsMock.mockResolvedValue(page([
      recording({ id: 20 }),
      recording({ id: 21, startTime: "2026-08-04T10:15:00Z" }),
      recording({ id: 22, startTime: "2026-08-04T10:16:00Z" })
    ]));
    createRecordingAnalysisJobMock.mockImplementation((caseId, payload) => (
      payload.recordingId === 21
        ? Promise.reject(new Error("저장소 확인 실패"))
        : Promise.resolve(job({ jobId: 500 + payload.recordingId, recordingId: payload.recordingId }))
    ));

    mount();
    await settle();
    await selectCaseAndCamera();
    root.querySelector(".search-button").click();
    await settle();

    expect(createRecordingAnalysisJobMock.mock.calls.map((call) => call[1].recordingId)).toEqual([20, 21, 22]);
    expect(root.textContent).toContain("생성 2건 · 이미 요청됨 0건 · 실패 1건");
    expect(root.querySelector('[role="alert"]').textContent).toContain("녹화 ID 21: 저장소 확인 실패");
  });

  it("reloads recordings with the newly selected condition time range", async () => {
    const nextCondition = {
      ...condition,
      id: 11,
      searchStart: "2026-08-04T12:00:00Z",
      searchEnd: "2026-08-04T13:00:00Z"
    };
    listSearchConditionsMock.mockResolvedValue([condition, nextCondition]);
    mount();
    await settle();
    await selectCaseAndCamera();
    listAdminRecordingsMock.mockClear();

    const conditionSelect = root.querySelector(".recording-filter-main select");
    conditionSelect.value = "11";
    conditionSelect.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();

    expect(listAdminRecordingsMock).toHaveBeenCalledWith(expect.objectContaining({
      cameraId: 4,
      startFrom: nextCondition.searchStart,
      startTo: nextCondition.searchEnd
    }));
  });

  it("blocks recording analysis when the selected condition has no time range", async () => {
    listSearchConditionsMock.mockResolvedValue([{ ...condition, searchStart: null, searchEnd: null }]);
    mount();
    await settle();
    await selectCase();

    expect(listAdminRecordingsMock).not.toHaveBeenCalled();
    expect(root.textContent).toContain("탐색 시작·종료 시간이 필요한 조건입니다.");
    expect(root.querySelector(".search-button").disabled).toBe(true);
  });

  it("shows an empty state when the selected camera has no overlapping recording", async () => {
    listAdminRecordingsMock.mockResolvedValue(page([]));
    mount();
    await settle();
    await selectCaseAndCamera();

    expect(root.textContent).toContain("선택한 카메라에 탐색 기간과 겹치는 녹화본이 없습니다.");
    expect(root.querySelector(".search-button").disabled).toBe(true);
  });

  it("keeps submission disabled until a camera is explicitly selected", async () => {
    mount();
    await settle();
    await selectCase();

    expect(listAdminRecordingsMock).not.toHaveBeenCalled();
    expect(createRecordingAnalysisJobMock).not.toHaveBeenCalled();
    expect(root.querySelector(".recording-search-form")).not.toBeNull();
    expect(root.querySelector(".search-button").disabled).toBe(true);
  });

  it("shows a recording load error and prevents submission when a later page fails", async () => {
    listAdminRecordingsMock
      .mockResolvedValueOnce(page([recording()], { totalElements: 101, totalPages: 2 }))
      .mockRejectedValueOnce(new Error("녹화 두 번째 페이지 조회 실패"));
    mount();
    await settle();
    await selectCaseAndCamera();

    expect(root.querySelector('[role="alert"]').textContent).toContain("녹화 두 번째 페이지 조회 실패");
    expect(root.querySelector(".search-button").disabled).toBe(true);
  });

  it.each([
    {
      label: "cancellation",
      status: "QUEUED",
      selector: ".job-card-actions .ghost-button",
      actionMock: cancelRecordingAnalysisJobMock,
      message: "작업 취소 실패"
    },
    {
      label: "retry",
      status: "FAILED",
      selector: ".job-card-actions .reset-button",
      actionMock: retryRecordingAnalysisJobMock,
      message: "작업 재시도 실패"
    }
  ])("keeps the form and job visible when $label fails", async ({
    status,
    selector,
    actionMock,
    message
  }) => {
    listRecordingAnalysisJobsForDashboardMock.mockResolvedValue([job({ status })]);
    actionMock.mockRejectedValue(new Error(message));
    mount();
    await settle();

    root.querySelector(selector).click();
    await settle();

    expect(root.querySelector(".recording-search-form")).not.toBeNull();
    expect(root.textContent).toContain("JOB-501");
    expect(root.querySelector('[role="alert"]').textContent).toContain(message);
  });

  it("shows a retry state for initial load failures and reloads the screen", async () => {
    listCasesMock
      .mockRejectedValueOnce(new Error("초기 조회 실패"))
      .mockResolvedValueOnce(page([caseItem]));
    mount();
    await settle();

    expect(root.querySelector(".recording-search-form")).toBeNull();
    expect(root.textContent).toContain("초기 조회 실패");

    root.querySelector(".state-view.error button").click();
    await settle();

    expect(listCasesMock).toHaveBeenCalledTimes(2);
    expect(root.querySelector(".recording-search-form")).not.toBeNull();
  });

  it("rejects recordings returned for a camera other than the selected case camera", async () => {
    listAdminRecordingsMock.mockResolvedValue(page([
      recording({ camera: { ...recording().camera, id: 99 } })
    ]));
    mount();
    await settle();
    await selectCaseAndCamera();

    expect(createRecordingAnalysisJobMock).not.toHaveBeenCalled();
    expect(root.textContent).toContain("선택한 카메라에 탐색 기간과 겹치는 녹화본이 없습니다.");
    expect(root.querySelector(".search-button").disabled).toBe(true);
  });
});
