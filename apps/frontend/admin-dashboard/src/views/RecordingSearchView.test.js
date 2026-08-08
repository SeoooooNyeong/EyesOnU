import { createApp, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const {
  cancelRecordingAnalysisJobMock,
  completeManualRecordingUploadMock,
  createManualRecordingUploadUrlMock,
  createRecordingAnalysisJobMock,
  fetchRecordingAnalysisJobMock,
  listAdminRecordingsMock,
  listCamerasMock,
  listCaseCamerasMock,
  listCasesMock,
  listRecordingAnalysisJobsMock,
  listRecordingAnalysisJobsForDashboardMock,
  listSearchConditionsMock,
  readManualRecordingDurationMock,
  retryRecordingAnalysisJobMock,
  routerPushMock,
  uploadManualRecordingFileMock
} = vi.hoisted(() => ({
  cancelRecordingAnalysisJobMock: vi.fn(),
  completeManualRecordingUploadMock: vi.fn(),
  createManualRecordingUploadUrlMock: vi.fn(),
  createRecordingAnalysisJobMock: vi.fn(),
  fetchRecordingAnalysisJobMock: vi.fn(),
  listAdminRecordingsMock: vi.fn(),
  listCamerasMock: vi.fn(),
  listCaseCamerasMock: vi.fn(),
  listCasesMock: vi.fn(),
  listRecordingAnalysisJobsMock: vi.fn(),
  listRecordingAnalysisJobsForDashboardMock: vi.fn(),
  listSearchConditionsMock: vi.fn(),
  readManualRecordingDurationMock: vi.fn(),
  retryRecordingAnalysisJobMock: vi.fn(),
  routerPushMock: vi.fn(),
  uploadManualRecordingFileMock: vi.fn()
}));

vi.mock("../api/caseApi", () => ({
  listCases: listCasesMock,
  listCaseCameras: listCaseCamerasMock,
  listSearchConditions: listSearchConditionsMock
}));

vi.mock("../api/recordingApi", () => ({
  cancelRecordingAnalysisJob: cancelRecordingAnalysisJobMock,
  completeManualRecordingUpload: completeManualRecordingUploadMock,
  createManualRecordingUploadUrl: createManualRecordingUploadUrlMock,
  createRecordingAnalysisJob: createRecordingAnalysisJobMock,
  fetchRecordingAnalysisJob: fetchRecordingAnalysisJobMock,
  listAdminRecordings: listAdminRecordingsMock,
  listRecordingAnalysisJobs: listRecordingAnalysisJobsMock,
  listRecordingAnalysisJobsForDashboard: listRecordingAnalysisJobsForDashboardMock,
  retryRecordingAnalysisJob: retryRecordingAnalysisJobMock,
  uploadManualRecordingFile: uploadManualRecordingFileMock
}));

vi.mock("../api/cameraApi", () => ({ listCameras: listCamerasMock }));

vi.mock("../domain/manualRecordingUpload", async (importOriginal) => ({
  ...(await importOriginal()),
  readManualRecordingDuration: readManualRecordingDurationMock
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: routerPushMock })
}));

import RecordingSearchView from "./RecordingSearchView.vue";

const caseItem = {
  id: 1,
  caseNumber: "EFU-001",
  missingName: "홍길동",
  status: "SEARCHING",
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

const availableCamera = (overrides = {}) => ({
  id: 4,
  cameraCode: "camera-04",
  cameraName: "정문 카메라",
  mediaServer: { id: 3, serverCode: "media-a", name: "Media A" },
  latitude: 37.5,
  longitude: 127,
  address: "서울 정문",
  status: "OFFLINE",
  lastHeartbeat: "2026-08-08T05:00:00Z",
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

  const openManualRecording = async () => {
    root.querySelector(".manual-recording-open-button").click();
    await settle();
  };

  const fillManualRecording = async ({ startKst = "2026-08-04T19:15:00" } = {}) => {
    root.querySelector(".manual-camera-option").click();
    const file = new File([new Uint8Array([0, 0, 0, 8, 102, 116, 121, 112])], "missing.mp4", {
      type: "video/mp4"
    });
    const fileInput = root.querySelector(".manual-recording-file-input");
    Object.defineProperty(fileInput, "files", { configurable: true, value: [file] });
    fileInput.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();

    const startInput = root.querySelector('.manual-recording-time-grid input[type="datetime-local"]');
    startInput.value = startKst;
    startInput.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();
    return file;
  };

  beforeEach(() => {
    vi.clearAllMocks();
    listCasesMock.mockResolvedValue(page([caseItem]));
    listRecordingAnalysisJobsForDashboardMock.mockResolvedValue([]);
    listRecordingAnalysisJobsMock.mockResolvedValue([]);
    listSearchConditionsMock.mockResolvedValue([condition]);
    listCaseCamerasMock.mockResolvedValue([camera()]);
    listAdminRecordingsMock.mockResolvedValue(page([recording()]));
    listCamerasMock.mockResolvedValue(page([availableCamera()], {
      size: 10,
      sort: "cameraCode,asc"
    }));
    readManualRecordingDurationMock.mockResolvedValue(30);
    createManualRecordingUploadUrlMock.mockResolvedValue({
      objectKey: "recordings/camera-04/manual.mp4",
      uploadUrl: "https://storage.example/upload?signature=secret",
      contentType: "video/mp4",
      expiresInSeconds: 900,
      maxFileSizeBytes: 104857600
    });
    uploadManualRecordingFileMock.mockResolvedValue(undefined);
    completeManualRecordingUploadMock.mockResolvedValue({
      id: 701,
      cameraId: 4,
      startTime: "2026-08-04T10:15:00Z",
      endTime: "2026-08-04T10:15:30Z",
      fileSize: 1024,
      duplicate: false,
      createdAt: "2026-08-08T06:05:00Z"
    });
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

    expect(root.querySelector(".recording-search-filter-panel")).not.toBeNull();
    expect(root.querySelector(".analysis-range-control")).not.toBeNull();
    expect(root.querySelector(".recording-job-section")).not.toBeNull();
    expect(root.textContent).not.toContain("탐색 지역");
    expect(listAdminRecordingsMock).not.toHaveBeenCalled();
    expect(root.querySelector(".search-button").disabled).toBe(true);
    expect(root.querySelector(".analysis-range-start").value).toBe("0");
    expect(root.querySelector(".analysis-range-end").value).toBe("60");
    expect(root.querySelector(".analysis-range-end").max).toBe("60");
    expect(root.querySelector(".analysis-range-start").step).toBe("1");

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

  it("previews range movement without loading and reloads once the handle is released", async () => {
    mount();
    await settle();
    await selectCaseAndCamera();
    listAdminRecordingsMock.mockClear();
    listAdminRecordingsMock.mockResolvedValue(page([recording({ id: 30 })]));

    const startHandle = root.querySelector(".analysis-range-start");
    startHandle.value = "10";
    startHandle.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();

    expect(listAdminRecordingsMock).not.toHaveBeenCalled();
    expect(root.textContent).toContain("핸들을 놓으면 선택한 분석 기간의 녹화본을 조회합니다.");
    expect(root.querySelector(".search-button").disabled).toBe(true);

    startHandle.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();

    expect(listAdminRecordingsMock).toHaveBeenCalledTimes(1);
    expect(listAdminRecordingsMock).toHaveBeenCalledWith(expect.objectContaining({
      startFrom: "2026-08-04T10:10:00.000Z",
      startTo: condition.searchEnd
    }));
    expect(root.textContent).toContain("분석 대상 녹화본 1개");

    root.querySelector(".search-button").click();
    await settle();
    expect(createRecordingAnalysisJobMock).toHaveBeenCalledWith("1", {
      conditionId: 10,
      recordingId: 30
    });
  });

  it("maps the final partial minute to the condition's exact end time", async () => {
    const partialMinuteCondition = {
      ...condition,
      searchEnd: "2026-08-04T11:00:30Z"
    };
    listSearchConditionsMock.mockResolvedValue([partialMinuteCondition]);
    mount();
    await settle();
    await selectCaseAndCamera();

    const endHandle = root.querySelector(".analysis-range-end");
    expect(endHandle.max).toBe("61");
    expect(endHandle.value).toBe("61");
    expect(listAdminRecordingsMock).toHaveBeenLastCalledWith(expect.objectContaining({
      startTo: partialMinuteCondition.searchEnd
    }));

    listAdminRecordingsMock.mockClear();
    endHandle.value = "60";
    endHandle.dispatchEvent(new Event("input", { bubbles: true }));
    endHandle.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();

    expect(listAdminRecordingsMock).toHaveBeenCalledWith(expect.objectContaining({
      startTo: "2026-08-04T11:00:00.000Z"
    }));
  });

  it("keeps the range handles at least one minute step apart", async () => {
    mount();
    await settle();
    await selectCase();

    const startHandle = root.querySelector(".analysis-range-start");
    const endHandle = root.querySelector(".analysis-range-end");
    startHandle.value = "60";
    startHandle.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();
    expect(startHandle.value).toBe("59");

    endHandle.value = "0";
    endHandle.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();
    expect(endHandle.value).toBe("60");
  });

  it("preserves the selected range across camera changes and resets it for a new condition", async () => {
    const nextCondition = {
      ...condition,
      id: 11,
      searchStart: "2026-08-04T12:00:00Z",
      searchEnd: "2026-08-04T13:30:00Z"
    };
    listSearchConditionsMock.mockResolvedValue([condition, nextCondition]);
    listCaseCamerasMock.mockResolvedValue([
      camera(),
      camera({ id: 31, cameraId: 5, cameraCode: "camera-05", cameraName: "후문 카메라" })
    ]);
    listAdminRecordingsMock.mockImplementation(({ cameraId }) => Promise.resolve(page([
      recording({ camera: { ...recording().camera, id: cameraId } })
    ])));
    mount();
    await settle();
    await selectCaseAndCamera();

    const startHandle = root.querySelector(".analysis-range-start");
    startHandle.value = "10";
    startHandle.dispatchEvent(new Event("input", { bubbles: true }));
    startHandle.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();

    listAdminRecordingsMock.mockClear();
    await selectCamera(5);
    expect(listAdminRecordingsMock).toHaveBeenLastCalledWith(expect.objectContaining({
      cameraId: 5,
      startFrom: "2026-08-04T10:10:00.000Z"
    }));
    expect(root.querySelector(".analysis-range-start").value).toBe("10");

    const conditionSelect = root.querySelector(".recording-filter-main select");
    conditionSelect.value = "11";
    conditionSelect.dispatchEvent(new Event("change", { bubbles: true }));
    await settle();

    expect(root.querySelector(".analysis-range-start").value).toBe("0");
    expect(root.querySelector(".analysis-range-end").value).toBe("90");
    expect(listAdminRecordingsMock).toHaveBeenLastCalledWith(expect.objectContaining({
      cameraId: 5,
      startFrom: nextCondition.searchStart,
      startTo: nextCondition.searchEnd
    }));
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

  it("keeps a non-searching case visible but locks recording analysis inputs", async () => {
    listCasesMock.mockResolvedValue(page([{ ...caseItem, status: "RECEIVED" }]));
    mount();
    await settle();
    await selectCase();

    expect(root.querySelector(".case-picker-trigger").textContent).toContain(caseItem.caseNumber);
    expect(root.textContent).toContain("탐색 중인 사건만 녹화 분석 작업을 요청하거나 재시도할 수 있습니다.");
    expect(root.querySelector(".recording-filter-main select").disabled).toBe(true);
    expect(root.querySelector(".recording-filter-time select").disabled).toBe(true);
    expect(root.querySelector(".search-button").disabled).toBe(true);
    expect(listSearchConditionsMock).not.toHaveBeenCalled();
    expect(listCaseCamerasMock).not.toHaveBeenCalled();
    expect(listAdminRecordingsMock).not.toHaveBeenCalled();

    root.querySelector(".search-button").click();
    await settle();
    expect(createRecordingAnalysisJobMock).not.toHaveBeenCalled();
  });

  it("shows non-searching case jobs but disables failed job retries", async () => {
    listCasesMock.mockResolvedValue(page([{ ...caseItem, status: "FIELD_SEARCH" }]));
    listRecordingAnalysisJobsForDashboardMock.mockResolvedValue([
      job({ jobId: 501, status: "FAILED" }),
      job({ jobId: 502, status: "QUEUED" })
    ]);
    mount();
    await settle();

    const retryButton = root.querySelector(".reset-button");
    expect(root.textContent).toContain("JOB-501");
    expect(root.textContent).toContain("JOB-502");
    expect(retryButton.disabled).toBe(true);
    expect(retryButton.title).toBe("탐색 중인 사건만 녹화 분석 작업을 요청하거나 재시도할 수 있습니다.");
    expect(root.querySelector(".ghost-button").disabled).toBe(false);

    retryButton.click();
    await settle();
    expect(retryRecordingAnalysisJobMock).not.toHaveBeenCalled();
  });

  it("shows an empty state when the selected camera has no overlapping recording", async () => {
    listAdminRecordingsMock.mockResolvedValue(page([]));
    mount();
    await settle();
    await selectCaseAndCamera();

    expect(root.textContent).toContain("선택한 카메라에 분석 기간과 겹치는 녹화본이 없습니다.");
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
    expect(root.textContent).toContain("선택한 카메라에 분석 기간과 겹치는 녹화본이 없습니다.");
    expect(root.querySelector(".search-button").disabled).toBe(true);
  });

  it("shows the recording add action to every admin and searches all cameras in the modal", async () => {
    mount();
    await settle();

    expect(root.querySelector(".manual-recording-open-button").textContent).toContain("녹화본 추가");
    await openManualRecording();

    expect(listCamerasMock).toHaveBeenCalledWith({
      search: undefined,
      page: 0,
      size: 10,
      sort: "cameraCode,asc"
    });
    expect(root.querySelector(".manual-camera-option").textContent).toContain("camera-04 · 정문 카메라");

    const searchInput = root.querySelector('[aria-label="녹화 카메라 검색"]');
    searchInput.value = "정문";
    searchInput.dispatchEvent(new Event("input", { bubbles: true }));
    root.querySelector(".manual-camera-search").dispatchEvent(new Event("submit", {
      bubbles: true,
      cancelable: true
    }));
    await settle();

    expect(listCamerasMock).toHaveBeenLastCalledWith({
      search: "정문",
      page: 0,
      size: 10,
      sort: "cameraCode,asc"
    });
  });

  it("confirms an overlapping period, uploads with one idempotency key, and refreshes analysis targets", async () => {
    vi.spyOn(globalThis.crypto, "randomUUID").mockReturnValue("550e8400-e29b-41d4-a716-446655440000");
    const overlaps = Array.from({ length: 6 }, (_, index) => recording({
      id: 100 + index,
      startTime: `2026-08-04T10:14:5${index}Z`,
      endTime: `2026-08-04T10:15:1${index}Z`
    }));
    listAdminRecordingsMock.mockImplementation((params) => Promise.resolve(
      params.size === 10
        ? page(overlaps, { size: 10, totalElements: 8 })
        : page([recording()])
    ));
    mount();
    await settle();
    await selectCaseAndCamera();
    await openManualRecording();
    const file = await fillManualRecording();

    expect(root.querySelectorAll('.manual-recording-time-grid input[type="datetime-local"]')[1].value)
      .toBe("2026-08-04T19:15:30.000");
    root.querySelector(".manual-recording-submit-button").click();
    await settle();

    expect(listAdminRecordingsMock).toHaveBeenLastCalledWith({
      cameraId: 4,
      startFrom: "2026-08-04T10:15:00.000Z",
      startTo: "2026-08-04T10:15:30.000Z",
      page: 0,
      size: 10,
      sort: "startTime,asc"
    });
    expect(root.textContent).toContain("같은 시간대의 녹화본 8건");
    expect(root.textContent).toContain("2026-08-04 19:14:50 ~ 2026-08-04 19:15:10 KST");
    expect(root.textContent).toContain("외 3건");
    expect(createManualRecordingUploadUrlMock).not.toHaveBeenCalled();

    root.querySelector(".manual-overlap-confirm-button").click();
    await settle();

    const key = "550e8400-e29b-41d4-a716-446655440000";
    expect(createManualRecordingUploadUrlMock).toHaveBeenCalledWith("camera-04", {
      startTime: "2026-08-04T10:15:00.000Z",
      endTime: "2026-08-04T10:15:30.000Z"
    }, key);
    expect(uploadManualRecordingFileMock).toHaveBeenCalledWith(
      "https://storage.example/upload?signature=secret",
      file,
      "video/mp4",
      { signal: expect.any(AbortSignal) }
    );
    expect(completeManualRecordingUploadMock).toHaveBeenCalledWith("camera-04", {
      startTime: "2026-08-04T10:15:00.000Z",
      endTime: "2026-08-04T10:15:30.000Z",
      objectKey: "recordings/camera-04/manual.mp4"
    }, key);
    expect(root.textContent).toContain("녹화본을 저장했습니다.");
    expect(root.textContent).toContain("녹화 ID");
    expect(listAdminRecordingsMock.mock.calls.filter(([params]) => params.size === 100)).toHaveLength(2);
  });

  it("stops without issuing an upload URL when an overlapping upload is cancelled", async () => {
    mount();
    await settle();
    await openManualRecording();
    await fillManualRecording();

    root.querySelector(".manual-recording-submit-button").click();
    await settle();

    expect(root.textContent).toContain("같은 시간대의 녹화본 1건");
    root.querySelector(".manual-recording-actions .ghost-button").click();
    await settle();

    expect(root.querySelector(".manual-recording-modal")).toBeNull();
    expect(createManualRecordingUploadUrlMock).not.toHaveBeenCalled();
    expect(uploadManualRecordingFileMock).not.toHaveBeenCalled();
    expect(completeManualRecordingUploadMock).not.toHaveBeenCalled();
  });

  it("retries only DB completion after the object upload already succeeded", async () => {
    vi.spyOn(globalThis.crypto, "randomUUID").mockReturnValue("550e8400-e29b-41d4-a716-446655440000");
    listAdminRecordingsMock.mockResolvedValue(page([]));
    completeManualRecordingUploadMock.mockRejectedValueOnce(new Error("DB 등록 실패"));
    mount();
    await settle();
    await openManualRecording();
    await fillManualRecording();

    root.querySelector(".manual-recording-submit-button").click();
    await settle();

    expect(root.querySelector(".manual-retry-button").textContent).toContain("DB 등록 다시 시도");
    expect(createManualRecordingUploadUrlMock).toHaveBeenCalledTimes(1);
    expect(uploadManualRecordingFileMock).toHaveBeenCalledTimes(1);
    expect(completeManualRecordingUploadMock).toHaveBeenCalledTimes(1);

    root.querySelector(".manual-retry-button").click();
    await settle();

    expect(createManualRecordingUploadUrlMock).toHaveBeenCalledTimes(1);
    expect(uploadManualRecordingFileMock).toHaveBeenCalledTimes(1);
    expect(completeManualRecordingUploadMock).toHaveBeenCalledTimes(2);
    expect(root.textContent).toContain("녹화본을 저장했습니다.");
  });

  it("gets a fresh presigned URL before retrying a failed storage PUT", async () => {
    vi.spyOn(globalThis.crypto, "randomUUID").mockReturnValue("550e8400-e29b-41d4-a716-446655440000");
    listAdminRecordingsMock.mockResolvedValue(page([]));
    uploadManualRecordingFileMock.mockRejectedValueOnce(new Error("MinIO PUT 실패"));
    mount();
    await settle();
    await openManualRecording();
    await fillManualRecording();

    root.querySelector(".manual-recording-submit-button").click();
    await settle();

    expect(root.querySelector(".manual-retry-button").textContent).toContain("파일 업로드 다시 시도");
    expect(createManualRecordingUploadUrlMock).toHaveBeenCalledTimes(1);
    expect(uploadManualRecordingFileMock).toHaveBeenCalledTimes(1);
    expect(completeManualRecordingUploadMock).not.toHaveBeenCalled();

    root.querySelector(".manual-retry-button").click();
    await settle();

    expect(createManualRecordingUploadUrlMock).toHaveBeenCalledTimes(2);
    expect(uploadManualRecordingFileMock).toHaveBeenCalledTimes(2);
    expect(completeManualRecordingUploadMock).toHaveBeenCalledTimes(1);
    const keys = createManualRecordingUploadUrlMock.mock.calls.map((call) => call[2]);
    expect(new Set(keys)).toEqual(new Set(["550e8400-e29b-41d4-a716-446655440000"]));
    expect(root.textContent).toContain("녹화본을 저장했습니다.");
  });
});
