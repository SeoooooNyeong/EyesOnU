import { createApp, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const {
  cancelRecordingAnalysisJobMock,
  createRecordingAnalysisJobMock,
  fetchRecordingAnalysisJobMock,
  listAdminRecordingsMock,
  listCaseCamerasMock,
  listCasesMock,
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
  searchArea: "정문"
};

const recording = (cameraId = 4) => ({
  id: 20,
  camera: {
    id: cameraId,
    cameraCode: "camera-04",
    cameraName: "정문 카메라"
  },
  startTime: "2026-08-04T10:14:32Z",
  endTime: "2026-08-04T10:14:46Z"
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

const settle = async () => {
  for (let index = 0; index < 8; index += 1) {
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

  beforeEach(() => {
    listCasesMock.mockResolvedValue({ data: [caseItem] });
    listRecordingAnalysisJobsForDashboardMock.mockResolvedValue([]);
    listSearchConditionsMock.mockResolvedValue([condition]);
    listCaseCamerasMock.mockResolvedValue([{
      id: 30,
      caseId: 1,
      cameraId: 4,
      cameraCode: "camera-04",
      searchEnabled: true,
      removedAt: null
    }]);
    listAdminRecordingsMock.mockResolvedValue({ data: [recording()] });
    createRecordingAnalysisJobMock.mockResolvedValue(job());
  });

  afterEach(() => {
    app?.unmount();
    root?.remove();
  });

  it("registers a job using the recording camera id contract and renders it immediately", async () => {
    mount();
    await settle();
    await selectCase();

    expect(listAdminRecordingsMock).toHaveBeenCalledWith(expect.objectContaining({
      cameraId: 4,
      page: 0,
      size: 100
    }));

    root.querySelector(".search-button").click();
    await settle();

    expect(createRecordingAnalysisJobMock).toHaveBeenCalledWith("1", {
      conditionId: 10,
      recordingId: 20
    });
    expect(root.textContent).toContain("JOB-501");
    expect(root.querySelector('[role="alert"]')).toBeNull();
  });

  it("keeps the form visible when required selections are missing", async () => {
    mount();
    await settle();

    root.querySelector(".search-button").click();
    await settle();

    expect(createRecordingAnalysisJobMock).not.toHaveBeenCalled();
    expect(root.querySelector(".recording-search-form")).not.toBeNull();
    expect(root.querySelector('[role="alert"]').textContent).toContain(
      "사건, 탐색 조건, 녹화를 모두 선택해주세요."
    );
  });

  it("keeps the form and existing jobs visible when job registration fails", async () => {
    listRecordingAnalysisJobsForDashboardMock.mockResolvedValue([
      job({ jobId: 400, status: "SUCCEEDED" })
    ]);
    createRecordingAnalysisJobMock.mockRejectedValue(new Error("작업 등록 실패"));
    mount();
    await settle();
    await selectCase();

    root.querySelector(".search-button").click();
    await settle();

    expect(root.querySelector(".recording-search-form")).not.toBeNull();
    expect(root.textContent).toContain("JOB-400");
    expect(root.querySelector('[role="alert"]').textContent).toContain("작업 등록 실패");
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
      .mockResolvedValueOnce({ data: [caseItem] });
    mount();
    await settle();

    expect(root.querySelector(".recording-search-form")).toBeNull();
    expect(root.textContent).toContain("초기 조회 실패");

    root.querySelector(".state-view.error button").click();
    await settle();

    expect(listCasesMock).toHaveBeenCalledTimes(2);
    expect(root.querySelector(".recording-search-form")).not.toBeNull();
  });

  it("rejects a recording returned for a camera that is not linked to the case", async () => {
    listAdminRecordingsMock.mockResolvedValue({ data: [recording(99)] });
    mount();
    await settle();
    await selectCase();

    root.querySelector(".search-button").click();
    await settle();

    expect(createRecordingAnalysisJobMock).not.toHaveBeenCalled();
    expect(root.querySelector(".recording-search-form")).not.toBeNull();
    expect(root.querySelector('[role="alert"]').textContent).toContain(
      "사건, 탐색 조건, 녹화를 모두 선택해주세요."
    );
  });
});
