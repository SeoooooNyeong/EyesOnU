<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import BasePagination from "../components/common/BasePagination.vue";
import StateBlock from "../components/common/StateBlock.vue";
import StatusBadge from "../components/common/StatusBadge.vue";
import { listCameras } from "../api/cameraApi";
import { listCases, listCaseCameras, listSearchConditions } from "../api/caseApi";
import {
  completeManualRecordingUpload,
  createRecordingAnalysisJob,
  createManualRecordingUploadUrl,
  fetchRecordingAnalysisJob,
  listAdminRecordings,
  listRecordingAnalysisJobs,
  listRecordingAnalysisJobsForDashboard,
  cancelRecordingAnalysisJob,
  retryRecordingAnalysisJob,
  uploadManualRecordingFile
} from "../api/recordingApi";
import { mapCamera } from "../domain/cameraMapper";
import {
  buildManualRecordingPeriod,
  formatDuration,
  formatFileSize,
  readManualRecordingDuration,
  toKstDateTimeInput,
  validateManualRecordingFile
} from "../domain/manualRecordingUpload";

const SEARCHING_CASE_REQUIRED_MESSAGE = "탐색 중인 사건만 녹화 분석 작업을 요청하거나 재시도할 수 있습니다.";
const MINUTE_MS = 60 * 1000;

const router = useRouter();
const cases = ref([]);
const conditions = ref([]);
const caseCameras = ref([]);
const recordings = ref([]);
const jobs = ref([]);
const loading = ref(true);
const recordingsLoading = ref(false);
const submitting = ref(false);
const analysisRangeStartOffset = ref(0);
const analysisRangeEndOffset = ref(0);
const analysisRangeDirty = ref(false);
const submissionProgress = ref({ processed: 0, total: 0 });
const batchResult = ref(null);
const loadError = ref("");
const actionError = ref("");
const caseDropdownOpen = ref(false);
const form = ref({
  caseId: "",
  conditionId: "",
  cameraId: ""
});
const manualRecordingOpen = ref(false);
const manualCameraSearchInput = ref("");
const manualCameraQuery = ref("");
const manualCameraRows = ref([]);
const manualCameraPage = ref(1);
const manualCameraTotalPages = ref(1);
const manualCameraTotalCount = ref(0);
const manualCameraLoading = ref(false);
const manualCameraError = ref("");
const manualSelectedCamera = ref(null);
const manualFileInput = ref(null);
const manualForm = reactive({ startKst: "", file: null });
const manualDuration = ref(null);
const manualMetadataLoading = ref(false);
const manualFileError = ref("");
const manualError = ref("");
const manualPhase = ref("idle");
const manualFailureStage = ref("");
const manualUploadSession = ref(null);
const manualUploadResult = ref(null);
const manualOverlapCount = ref(0);
const manualOverlapRows = ref([]);
const manualMaxStartKst = ref("");
let manualCameraRequestId = 0;
let manualMetadataRequestId = 0;
let manualOperationId = 0;
let manualStorageAbortController = null;

const selectedCase = computed(() => cases.value.find((item) => String(item.id) === String(form.value.caseId)));
const selectedCondition = computed(() => conditions.value.find((item) => String(item.id) === String(form.value.conditionId)));
const selectedCamera = computed(() => caseCameras.value.find((item) => String(item.cameraId) === String(form.value.cameraId)));
const isSearchingCase = (caseId) => cases.value.some((item) => (
  String(item.id) === String(caseId) && item.status === "SEARCHING"
));
const selectedCaseIsSearching = computed(() => (
  Boolean(selectedCase.value) && isSearchingCase(selectedCase.value.id)
));
const hasValidSearchTimeRange = computed(() => {
  const start = new Date(selectedCondition.value?.searchStart || "");
  const end = new Date(selectedCondition.value?.searchEnd || "");
  return !Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()) && start.getTime() < end.getTime();
});
const analysisRangeMaxOffset = computed(() => {
  if (!hasValidSearchTimeRange.value) return 0;
  const start = new Date(selectedCondition.value.searchStart).getTime();
  const end = new Date(selectedCondition.value.searchEnd).getTime();
  return Math.ceil((end - start) / MINUTE_MS);
});
const analysisTimeAtOffset = (offset) => {
  if (!hasValidSearchTimeRange.value) return null;
  const start = new Date(selectedCondition.value.searchStart).getTime();
  const end = new Date(selectedCondition.value.searchEnd).getTime();
  if (offset <= 0) return selectedCondition.value.searchStart;
  if (offset >= analysisRangeMaxOffset.value) return selectedCondition.value.searchEnd;
  return new Date(Math.min(start + offset * MINUTE_MS, end)).toISOString();
};
const analysisRangeStart = computed(() => analysisTimeAtOffset(analysisRangeStartOffset.value));
const analysisRangeEnd = computed(() => analysisTimeAtOffset(analysisRangeEndOffset.value));
const analysisRangeFillStyle = computed(() => {
  const max = analysisRangeMaxOffset.value;
  if (max <= 0) return { left: "0%", width: "0%" };
  return {
    left: `${(analysisRangeStartOffset.value / max) * 100}%`,
    width: `${((analysisRangeEndOffset.value - analysisRangeStartOffset.value) / max) * 100}%`
  };
});
const visibleJobs = computed(() => form.value.caseId
  ? jobs.value.filter((job) => String(job.caseId) === String(form.value.caseId))
  : jobs.value);
const manualBusy = computed(() => [
  "checking-overlap",
  "requesting-upload-url",
  "uploading-file",
  "completing-registration"
].includes(manualPhase.value));
const manualPeriodState = computed(() => {
  if (!manualForm.startKst || manualDuration.value == null) return { period: null, error: "" };
  try {
    return { period: buildManualRecordingPeriod(manualForm.startKst, manualDuration.value), error: "" };
  } catch (error) {
    return { period: null, error: error?.message || "녹화 시각을 확인해 주세요." };
  }
});
const manualCanStart = computed(() => Boolean(
  manualSelectedCamera.value
    && manualForm.file
    && manualDuration.value != null
    && manualPeriodState.value.period
    && !manualMetadataLoading.value
    && !manualBusy.value
));
const manualPhaseMessage = computed(() => ({
  "checking-overlap": "같은 카메라의 기존 녹화 구간을 확인하고 있습니다.",
  "requesting-upload-url": "안전한 저장소 업로드 주소를 발급하고 있습니다.",
  "uploading-file": "녹화 파일을 MinIO에 업로드하고 있습니다.",
  "completing-registration": "업로드한 녹화본을 DB에 등록하고 있습니다."
}[manualPhase.value] || ""));
const manualRetryLabel = computed(() => ({
  overlap: "겹침 확인 다시 시도",
  "upload-url": "업로드 주소 다시 요청",
  storage: "파일 업로드 다시 시도",
  completion: "DB 등록 다시 시도"
}[manualFailureStage.value] || "다시 시도"));
const manualOverlapRemainingCount = computed(() => Math.max(
  0,
  manualOverlapCount.value - manualOverlapRows.value.length
));

const formatDate = (value) => value ? new Date(value).toLocaleString("ko-KR") : "-";
const cameraLabel = (camera) => camera.cameraName
  ? `${camera.cameraCode || `카메라 ${camera.cameraId}`} · ${camera.cameraName}`
  : camera.cameraCode || `카메라 ${camera.cameraId}`;
const manualCameraLabel = (camera) => {
  const code = camera?.cameraCode && camera.cameraCode !== "-" ? camera.cameraCode : "";
  const name = camera?.cameraName && camera.cameraName !== "-" ? camera.cameraName : "";
  return [code, name].filter(Boolean).join(" · ") || `카메라 ${camera?.id ?? "-"}`;
};
const formatManualOverlapRange = (recording) => {
  const start = toKstDateTimeInput(recording?.startTime).replace("T", " ");
  const end = toKstDateTimeInput(recording?.endTime).replace("T", " ");
  return start && end ? `${start} ~ ${end} KST` : "촬영 구간 확인 불가";
};
const jobProgress = (status) => ({ QUEUED: 3, RUNNING: 50, SUCCEEDED: 100, FAILED: 100, CANCELLED: 100 }[status] ?? 0);
const jobStatus = (status) => ({ QUEUED: "searching", RUNNING: "searching", SUCCEEDED: "closed", FAILED: "failed", CANCELLED: "cancelled" }[status] || "searching");
const isCurrentRecordingLoad = (requestId, caseId, conditionId, cameraId) => (
  requestId === recordingLoadRequestId
    && caseId === form.value.caseId
    && conditionId === form.value.conditionId
    && cameraId === form.value.cameraId
);

const resetAnalysisRange = () => {
  analysisRangeStartOffset.value = 0;
  analysisRangeEndOffset.value = analysisRangeMaxOffset.value;
  analysisRangeDirty.value = false;
};

const invalidateManualPreparation = () => {
  manualOperationId += 1;
  manualStorageAbortController?.abort();
  manualStorageAbortController = null;
  manualPhase.value = "idle";
  manualFailureStage.value = "";
  manualUploadSession.value = null;
  manualUploadResult.value = null;
  manualOverlapCount.value = 0;
  manualOverlapRows.value = [];
  manualError.value = "";
};

const clearManualFile = () => {
  manualMetadataRequestId += 1;
  manualMetadataLoading.value = false;
  manualForm.file = null;
  manualDuration.value = null;
  manualFileError.value = "";
  if (manualFileInput.value) manualFileInput.value.value = "";
  invalidateManualPreparation();
};

const resetManualDialog = () => {
  manualCameraRequestId += 1;
  manualMetadataRequestId += 1;
  invalidateManualPreparation();
  manualCameraSearchInput.value = "";
  manualCameraQuery.value = "";
  manualCameraRows.value = [];
  manualCameraPage.value = 1;
  manualCameraTotalPages.value = 1;
  manualCameraTotalCount.value = 0;
  manualCameraLoading.value = false;
  manualCameraError.value = "";
  manualSelectedCamera.value = null;
  manualForm.startKst = "";
  manualForm.file = null;
  manualDuration.value = null;
  manualMetadataLoading.value = false;
  manualFileError.value = "";
  manualMaxStartKst.value = toKstDateTimeInput(new Date());
  if (manualFileInput.value) manualFileInput.value.value = "";
};

const loadManualCameras = async () => {
  const requestId = ++manualCameraRequestId;
  manualCameraLoading.value = true;
  manualCameraError.value = "";
  try {
    const result = await listCameras({
      search: manualCameraQuery.value.trim() || undefined,
      page: manualCameraPage.value - 1,
      size: 10,
      sort: "cameraCode,asc"
    });
    if (requestId !== manualCameraRequestId || !manualRecordingOpen.value) return;
    manualCameraRows.value = (result.data || []).map(mapCamera);
    manualCameraTotalPages.value = Math.max(1, Number(result.meta?.totalPages) || 0);
    manualCameraTotalCount.value = Number(result.meta?.totalElements) || 0;
  } catch (error) {
    if (requestId !== manualCameraRequestId || !manualRecordingOpen.value) return;
    manualCameraRows.value = [];
    manualCameraTotalPages.value = 1;
    manualCameraTotalCount.value = 0;
    manualCameraError.value = error?.message || "카메라 목록을 불러오지 못했습니다.";
  } finally {
    if (requestId === manualCameraRequestId) manualCameraLoading.value = false;
  }
};

const openManualRecording = () => {
  resetManualDialog();
  manualRecordingOpen.value = true;
  loadManualCameras();
};

const closeManualRecording = () => {
  if (manualBusy.value) return;
  manualRecordingOpen.value = false;
  resetManualDialog();
};

const searchManualCameras = () => {
  manualCameraQuery.value = manualCameraSearchInput.value;
  manualCameraPage.value = 1;
  loadManualCameras();
};

const changeManualCameraPage = (page) => {
  manualCameraPage.value = page;
  loadManualCameras();
};

const selectManualCamera = (camera) => {
  if (manualBusy.value) return;
  manualSelectedCamera.value = camera;
  invalidateManualPreparation();
};

const inspectManualFile = async (file) => {
  const requestId = ++manualMetadataRequestId;
  invalidateManualPreparation();
  manualForm.file = null;
  manualDuration.value = null;
  manualFileError.value = "";
  if (!file) return;

  manualMetadataLoading.value = true;
  try {
    const duration = await readManualRecordingDuration(file);
    if (requestId !== manualMetadataRequestId) return;
    manualForm.file = file;
    manualDuration.value = duration;
  } catch (error) {
    if (requestId !== manualMetadataRequestId) return;
    manualFileError.value = error?.message || "녹화 파일을 확인하지 못했습니다.";
  } finally {
    if (requestId === manualMetadataRequestId) {
      manualMetadataLoading.value = false;
      if (manualFileInput.value) manualFileInput.value.value = "";
    }
  }
};

const onManualFileChange = (event) => inspectManualFile(event.target.files?.[0]);

const validateManualInputs = () => {
  if (!manualSelectedCamera.value) throw new Error("녹화한 카메라를 선택해 주세요.");
  validateManualRecordingFile(manualForm.file);
  if (manualDuration.value == null) throw new Error("영상 재생 시간을 확인해 주세요.");
  return buildManualRecordingPeriod(manualForm.startKst, manualDuration.value);
};

const failManualOperation = (stage, error, fallback) => {
  manualPhase.value = "error";
  manualFailureStage.value = stage;
  manualError.value = error?.message || fallback;
};

const completeManualUpload = async (session, operationId) => {
  if (operationId !== manualOperationId) return;
  manualPhase.value = "completing-registration";
  manualFailureStage.value = "";
  manualError.value = "";
  try {
    const result = await completeManualRecordingUpload(session.cameraCode, {
      startTime: session.startTime,
      endTime: session.endTime,
      objectKey: session.objectKey
    }, session.idempotencyKey);
    if (operationId !== manualOperationId) return;
    manualUploadResult.value = result;
    manualPhase.value = "success";
    try {
      await loadRecordings();
    } catch {
      // The recording is registered; the existing analysis target list can refresh later.
    }
  } catch (error) {
    if (operationId !== manualOperationId) return;
    failManualOperation("completion", error, "업로드한 녹화본을 DB에 등록하지 못했습니다.");
  }
};

const uploadManualFile = async (session, operationId) => {
  if (operationId !== manualOperationId) return;
  manualPhase.value = "uploading-file";
  manualFailureStage.value = "";
  manualError.value = "";
  manualStorageAbortController = new AbortController();
  try {
    await uploadManualRecordingFile(
      session.uploadUrl,
      session.file,
      session.contentType,
      { signal: manualStorageAbortController.signal }
    );
    if (operationId !== manualOperationId) return;
  } catch (error) {
    if (operationId !== manualOperationId) return;
    failManualOperation("storage", error, "녹화 파일을 MinIO에 업로드하지 못했습니다.");
    return;
  } finally {
    if (operationId === manualOperationId) manualStorageAbortController = null;
  }
  await completeManualUpload(session, operationId);
};

const issueManualUploadUrl = async (session, operationId) => {
  if (operationId !== manualOperationId) return;
  manualPhase.value = "requesting-upload-url";
  manualFailureStage.value = "";
  manualError.value = "";
  let upload;
  try {
    upload = await createManualRecordingUploadUrl(session.cameraCode, {
      startTime: session.startTime,
      endTime: session.endTime
    }, session.idempotencyKey);
    if (operationId !== manualOperationId) return;
  } catch (error) {
    if (operationId !== manualOperationId) return;
    failManualOperation("upload-url", error, "업로드 주소를 발급받지 못했습니다.");
    return;
  }

  try {
    const serverMaxFileSize = Number(upload.maxFileSizeBytes);
    validateManualRecordingFile(
      session.file,
      Number.isFinite(serverMaxFileSize) && serverMaxFileSize > 0 ? serverMaxFileSize : undefined
    );
  } catch (error) {
    manualUploadSession.value = null;
    manualPhase.value = "idle";
    manualFileError.value = error?.message || "녹화 파일 크기를 확인해 주세요.";
    return;
  }
  Object.assign(session, {
    objectKey: upload.objectKey,
    uploadUrl: upload.uploadUrl,
    contentType: upload.contentType || "video/mp4"
  });
  await uploadManualFile(session, operationId);
};

const beginManualUpload = async (period) => {
  const operationId = ++manualOperationId;
  let idempotencyKey;
  try {
    idempotencyKey = globalThis.crypto.randomUUID();
  } catch {
    manualError.value = "이 브라우저에서는 안전한 업로드 식별자를 만들 수 없습니다.";
    return;
  }
  const session = {
    idempotencyKey,
    cameraCode: manualSelectedCamera.value.cameraCode,
    cameraId: manualSelectedCamera.value.id,
    startTime: period.startTime,
    endTime: period.endTime,
    file: manualForm.file
  };
  manualUploadSession.value = session;
  await issueManualUploadUrl(session, operationId);
};

const checkOverlapAndUpload = async () => {
  manualError.value = "";
  manualFailureStage.value = "";
  let period;
  try {
    period = validateManualInputs();
  } catch (error) {
    manualPhase.value = "idle";
    manualError.value = error?.message || "녹화본 추가 정보를 확인해 주세요.";
    return;
  }

  const operationId = ++manualOperationId;
  manualPhase.value = "checking-overlap";
  try {
    const result = await listAdminRecordings({
      cameraId: Number(manualSelectedCamera.value.id),
      startFrom: period.startTime,
      startTo: period.endTime,
      page: 0,
      size: 10,
      sort: "startTime,asc"
    });
    if (operationId !== manualOperationId) return;
    const count = Number(result.meta?.totalElements ?? result.data?.length ?? 0);
    if (count > 0) {
      manualOverlapCount.value = count;
      manualOverlapRows.value = (result.data || []).slice(0, 5);
      manualPhase.value = "awaiting-overlap-confirmation";
      return;
    }
  } catch (error) {
    if (operationId !== manualOperationId) return;
    failManualOperation("overlap", error, "기존 녹화 구간을 확인하지 못했습니다.");
    return;
  }
  await beginManualUpload(period);
};

const confirmManualOverlap = async () => {
  let period;
  try {
    period = validateManualInputs();
  } catch (error) {
    manualPhase.value = "idle";
    manualError.value = error?.message || "녹화본 추가 정보를 확인해 주세요.";
    return;
  }
  await beginManualUpload(period);
};

const retryManualOperation = async () => {
  const session = manualUploadSession.value;
  if (manualFailureStage.value === "overlap") {
    await checkOverlapAndUpload();
    return;
  }
  if (!session) return;
  const operationId = ++manualOperationId;
  if (["upload-url", "storage"].includes(manualFailureStage.value)) {
    await issueManualUploadUrl(session, operationId);
    return;
  }
  if (manualFailureStage.value === "completion") {
    await completeManualUpload(session, operationId);
  }
};

const startAnotherManualRecording = () => {
  resetManualDialog();
  loadManualCameras();
};

const beginAnalysisRangeChange = () => {
  recordingLoadRequestId += 1;
  recordingsLoading.value = false;
  recordings.value = [];
  batchResult.value = null;
  actionError.value = "";
  analysisRangeDirty.value = true;
};

const updateAnalysisRangeStart = (event) => {
  const nextOffset = Math.min(
    Number(event.target.value),
    Math.max(0, analysisRangeEndOffset.value - 1)
  );
  analysisRangeStartOffset.value = nextOffset;
  event.target.value = String(nextOffset);
  beginAnalysisRangeChange();
};

const updateAnalysisRangeEnd = (event) => {
  const nextOffset = Math.max(
    Number(event.target.value),
    Math.min(analysisRangeMaxOffset.value, analysisRangeStartOffset.value + 1)
  );
  analysisRangeEndOffset.value = nextOffset;
  event.target.value = String(nextOffset);
  beginAnalysisRangeChange();
};

let recordingLoadRequestId = 0;
const loadRecordings = async () => {
  const requestId = ++recordingLoadRequestId;
  const caseId = form.value.caseId;
  const conditionId = form.value.conditionId;
  const cameraId = form.value.cameraId;
  const rangeStart = analysisRangeStart.value;
  const rangeEnd = analysisRangeEnd.value;
  recordings.value = [];
  recordingsLoading.value = false;
  batchResult.value = null;

  if (!caseId || !conditionId || !cameraId || !isSearchingCase(caseId)
      || !selectedCamera.value || !rangeStart || !rangeEnd) return;

  recordingsLoading.value = true;
  const loadedRecordings = [];
  try {
    let page = 0;
    let totalPages = 1;
    while (page < totalPages) {
      const result = await listAdminRecordings({
        cameraId: Number(cameraId),
        startFrom: rangeStart,
        startTo: rangeEnd,
        page,
        size: 100,
        sort: "startTime,asc"
      });
      if (!isCurrentRecordingLoad(requestId, caseId, conditionId, cameraId)) return;

      loadedRecordings.push(...(result.data || []));
      const responseTotalPages = Number(result.meta?.totalPages);
      totalPages = Number.isInteger(responseTotalPages) && responseTotalPages >= 0
        ? responseTotalPages
        : 1;
      page += 1;
    }
  } catch (exception) {
    if (!isCurrentRecordingLoad(requestId, caseId, conditionId, cameraId)) return;
    throw exception;
  } finally {
    if (requestId === recordingLoadRequestId) recordingsLoading.value = false;
  }
  if (!isCurrentRecordingLoad(requestId, caseId, conditionId, cameraId)) return;

  const byId = new Map(loadedRecordings
    .filter((recording) => String(recording.camera?.id) === String(cameraId))
    .map((recording) => [recording.id, recording]));
  recordings.value = [...byId.values()].sort((left, right) => (
    new Date(left.startTime).getTime() - new Date(right.startTime).getTime()
  ));
};

const changeRecordingFilter = async () => {
  actionError.value = "";
  try {
    await loadRecordings();
  } catch (exception) {
    actionError.value = exception.message || "녹화 목록을 불러오지 못했습니다.";
  }
};

const changeCondition = async () => {
  resetAnalysisRange();
  await changeRecordingFilter();
};

const commitAnalysisRange = async () => {
  if (!analysisRangeDirty.value) return;
  analysisRangeDirty.value = false;
  await changeRecordingFilter();
};

const loadJobs = async () => {
  if (cases.value.length === 0) {
    jobs.value = [];
    return;
  }
  const result = await listRecordingAnalysisJobsForDashboard(cases.value.map((item) => item.id));
  jobs.value = result.sort((left, right) => (
    new Date(right.requestedAt).getTime() - new Date(left.requestedAt).getTime()
  ));
};

const loadCaseData = async () => {
  if (!form.value.caseId) {
    conditions.value = [];
    caseCameras.value = [];
    form.value.conditionId = "";
    form.value.cameraId = "";
    resetAnalysisRange();
    return;
  }
  if (!selectedCaseIsSearching.value) {
    conditions.value = [];
    caseCameras.value = [];
    recordings.value = [];
    form.value.conditionId = "";
    form.value.cameraId = "";
    resetAnalysisRange();
    return;
  }
  const [nextConditions, nextCameras] = await Promise.all([
    listSearchConditions(form.value.caseId),
    listCaseCameras(form.value.caseId)
  ]);
  conditions.value = nextConditions;
  caseCameras.value = nextCameras.filter((camera) => camera.searchEnabled && !camera.removedAt);
  if (!conditions.value.some((item) => String(item.id) === String(form.value.conditionId))) {
    form.value.conditionId = conditions.value[0]?.id ? String(conditions.value[0].id) : "";
  }
  resetAnalysisRange();
};

const load = async () => {
  loading.value = true;
  loadError.value = "";
  actionError.value = "";
  try {
    const result = await listCases({ page: 0, size: 100, sort: "reportedAt,desc" });
    cases.value = (result.data || []).map((item) => ({
      ...item,
      name: item.missingName || item.name || "이름 미등록"
    }));
    await loadJobs();
    await loadCaseData();
    await loadRecordings();
  } catch (exception) {
    loadError.value = exception.message || "녹화 탐색 정보를 불러오지 못했습니다.";
  } finally {
    loading.value = false;
  }
};

const selectCase = async (caseItem) => {
  recordingLoadRequestId += 1;
  recordingsLoading.value = false;
  form.value.caseId = String(caseItem.id);
  form.value.cameraId = "";
  recordings.value = [];
  resetAnalysisRange();
  batchResult.value = null;
  caseDropdownOpen.value = false;
  await loadCaseData();
};

const selectAllCases = () => {
  recordingLoadRequestId += 1;
  recordingsLoading.value = false;
  form.value.caseId = "";
  form.value.conditionId = "";
  form.value.cameraId = "";
  conditions.value = [];
  caseCameras.value = [];
  recordings.value = [];
  resetAnalysisRange();
  batchResult.value = null;
  caseDropdownOpen.value = false;
};

const requestSearch = async () => {
  if (selectedCase.value && !selectedCaseIsSearching.value) {
    actionError.value = SEARCHING_CASE_REQUIRED_MESSAGE;
    return;
  }
  if (!hasValidSearchTimeRange.value) {
    actionError.value = "탐색 시작·종료 시간이 설정된 조건만 녹화 분석에 사용할 수 있습니다.";
    return;
  }
  if (!form.value.caseId || !form.value.conditionId || !form.value.cameraId
      || !selectedCamera.value || recordings.value.length === 0) {
    actionError.value = "사건, 탐색 조건, 카메라를 선택하고 대상 녹화본을 확인해주세요.";
    return;
  }

  submitting.value = true;
  actionError.value = "";
  batchResult.value = null;
  submissionProgress.value = { processed: 0, total: recordings.value.length };
  try {
    const caseId = form.value.caseId;
    const conditionId = Number(form.value.conditionId);
    const latestCaseJobs = await listRecordingAnalysisJobs(caseId);
    jobs.value = [
      ...jobs.value.filter((item) => String(item.caseId) !== String(caseId)),
      ...latestCaseJobs
    ].sort((left, right) => new Date(right.requestedAt).getTime() - new Date(left.requestedAt).getTime());

    const activeRecordingIds = new Set(latestCaseJobs
      .filter((item) => item.conditionId === conditionId && ["QUEUED", "RUNNING"].includes(item.status))
      .map((item) => String(item.recordingId)));
    const result = { created: 0, skipped: 0, failed: 0, failures: [] };

    for (const recording of recordings.value) {
      if (activeRecordingIds.has(String(recording.id))) {
        result.skipped += 1;
        submissionProgress.value.processed += 1;
        continue;
      }

      try {
        const createdJob = await createRecordingAnalysisJob(caseId, {
          conditionId,
          recordingId: Number(recording.id)
        });
        jobs.value.unshift(createdJob);
        result.created += 1;
      } catch (exception) {
        result.failed += 1;
        result.failures.push({
          recordingId: recording.id,
          message: exception.message || "녹화 분석 작업을 등록하지 못했습니다."
        });
      } finally {
        submissionProgress.value.processed += 1;
      }
    }
    batchResult.value = result;
  } catch (exception) {
    actionError.value = exception.message || "기존 녹화 분석 작업을 확인하지 못했습니다.";
  } finally {
    submitting.value = false;
  }
};

const refreshJobs = async () => {
  const activeJobs = jobs.value.filter((job) => ["QUEUED", "RUNNING"].includes(job.status));
  await Promise.all(activeJobs.map(async (job) => {
    try {
      const updated = await fetchRecordingAnalysisJob(job.caseId, job.jobId);
      Object.assign(job, updated);
    } catch {
      // Keep the last known state while the next refresh retries the job.
    }
  }));
};

const cancelJob = async (job) => {
  actionError.value = "";
  try {
    Object.assign(job, await cancelRecordingAnalysisJob(job.caseId, job.jobId));
  } catch (exception) {
    actionError.value = exception.message || "녹화 분석 작업을 취소하지 못했습니다.";
  }
};

const retryJob = async (job) => {
  actionError.value = "";
  if (!isSearchingCase(job.caseId)) {
    actionError.value = SEARCHING_CASE_REQUIRED_MESSAGE;
    return;
  }
  try {
    Object.assign(job, await retryRecordingAnalysisJob(job.caseId, job.jobId));
  } catch (exception) {
    actionError.value = exception.message || "녹화 분석 작업을 재시도하지 못했습니다.";
  }
};

const openCandidateReview = (job) => {
  router.push({ path: "/admin/candidates", query: { caseId: job.caseId } });
};

watch(
  () => [
    form.value.caseId,
    form.value.conditionId,
    form.value.cameraId
  ],
  () => {
    actionError.value = "";
    batchResult.value = null;
  }
);

let jobRefreshTimer;
onMounted(async () => {
  await load();
  jobRefreshTimer = window.setInterval(refreshJobs, 3000);
});
onUnmounted(() => {
  if (jobRefreshTimer) window.clearInterval(jobRefreshTimer);
  manualCameraRequestId += 1;
  manualMetadataRequestId += 1;
  manualOperationId += 1;
  manualStorageAbortController?.abort();
});
</script>

<template>
  <section class="content-panel">
    <div class="section-heading">
      <div><h2>녹화 영상 탐색</h2><p>사건의 탐색 조건과 카메라를 선택해 기간 내 녹화본의 분석 작업을 등록합니다.</p></div>
      <button class="primary-button manual-recording-open-button" type="button" @click="openManualRecording">
        녹화본 추가
      </button>
    </div>

    <StateBlock :loading="loading" :error="loadError" @retry="load">
      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>

      <div class="recording-search-form">
        <section class="recording-search-filter-panel" aria-labelledby="recording-search-filter-title">
          <div class="recording-search-panel-heading">
            <div>
              <h3 id="recording-search-filter-title">탐색 필터</h3>
              <p>사건·탐색 조건·카메라를 선택해 분석 대상을 좁혀보세요.</p>
            </div>
          </div>
          <div class="recording-search-filter-fields">
            <div class="filter-bar recording-filter-main">
              <label class="case-select-field">사건
                <div class="case-picker">
                  <button type="button" class="case-picker-trigger" :disabled="submitting" @click="caseDropdownOpen = !caseDropdownOpen">{{ selectedCase?.caseNumber || "사건 선택" }}</button>
                  <div v-if="caseDropdownOpen" class="case-picker-menu">
                    <button type="button" class="case-picker-option" @click="selectAllCases">사건 선택</button>
                    <button v-for="item in cases" :key="item.id" type="button" class="case-picker-option" @click="selectCase(item)">{{ item.caseNumber }} · {{ item.name }}</button>
                  </div>
                </div>
              </label>
              <label>탐색 조건
                <select v-model="form.conditionId" :disabled="submitting || !form.caseId || !selectedCaseIsSearching" @change="changeCondition">
                  <option value="">조건 선택</option>
                  <option v-for="condition in conditions" :key="condition.id" :value="String(condition.id)">{{ condition.prompt || `조건 ${condition.id}` }}</option>
                </select>
              </label>
            </div>
            <div class="filter-bar recording-filter-time">
              <label>조건 전체 기간
                <input :value="hasValidSearchTimeRange ? `${formatDate(selectedCondition.searchStart)} ~ ${formatDate(selectedCondition.searchEnd)}` : ''" placeholder="탐색 조건의 시작·종료 시간" readonly />
              </label>
              <label>카메라
                <select v-model="form.cameraId" :disabled="submitting || recordingsLoading || !selectedCaseIsSearching || !hasValidSearchTimeRange || caseCameras.length === 0" @change="changeRecordingFilter">
                  <option value="">카메라 선택</option>
                  <option v-for="camera in caseCameras" :key="camera.cameraId" :value="String(camera.cameraId)">{{ cameraLabel(camera) }}</option>
                </select>
              </label>
              <button class="search-button" :disabled="submitting || recordingsLoading || analysisRangeDirty || !selectedCaseIsSearching || !hasValidSearchTimeRange || !selectedCamera || recordings.length === 0" @click="requestSearch">{{ submitting ? `${submissionProgress.processed}/${submissionProgress.total} 요청 중` : "선택 기간 탐색 요청" }}</button>
            </div>
          </div>
        <section v-if="hasValidSearchTimeRange" class="analysis-range-control" aria-labelledby="analysis-range-title">
          <div class="analysis-range-heading">
            <span id="analysis-range-title">분석 구간 선택</span>
            <div class="analysis-range-selection-group">
              <strong class="analysis-range-selection">{{ formatDate(analysisRangeStart) }} ~ {{ formatDate(analysisRangeEnd) }}</strong>
              <span v-if="selectedCamera && hasValidSearchTimeRange" class="analysis-range-count">분석 대상 녹화본 {{ recordings.length }}개</span>
            </div>
          </div>
          <div class="analysis-range-slider" :class="{ disabled: submitting }">
            <div class="analysis-range-track"><span :style="analysisRangeFillStyle" /></div>
            <input
              class="analysis-range-input analysis-range-start"
              type="range"
              min="0"
              :max="analysisRangeMaxOffset"
              step="1"
              :value="analysisRangeStartOffset"
              :disabled="submitting || analysisRangeMaxOffset < 1"
              aria-label="분석 시작 시각"
              :aria-valuetext="formatDate(analysisRangeStart)"
              @input="updateAnalysisRangeStart"
              @change="commitAnalysisRange"
            />
            <input
              class="analysis-range-input analysis-range-end"
              type="range"
              min="0"
              :max="analysisRangeMaxOffset"
              step="1"
              :value="analysisRangeEndOffset"
              :disabled="submitting || analysisRangeMaxOffset < 1"
              aria-label="분석 종료 시각"
              :aria-valuetext="formatDate(analysisRangeEnd)"
              @input="updateAnalysisRangeEnd"
              @change="commitAnalysisRange"
            />
          </div>
          <div class="analysis-range-bounds">
            <span>{{ formatDate(selectedCondition.searchStart) }}</span>
            <span>{{ formatDate(selectedCondition.searchEnd) }}</span>
          </div>
          <p v-if="analysisRangeDirty" class="analysis-range-note">핸들을 놓으면 선택한 분석 기간의 녹화본을 조회합니다.</p>
        </section>
        </section>
        <div class="recording-search-feedback">
          <p v-if="selectedCase && !selectedCaseIsSearching" class="muted-text">{{ SEARCHING_CASE_REQUIRED_MESSAGE }}</p>
          <p v-else-if="selectedCondition && !hasValidSearchTimeRange" class="muted-text">탐색 시작·종료 시간이 필요한 조건입니다.</p>
          <p v-else-if="recordingsLoading" class="muted-text">탐색 기간과 겹치는 녹화본을 불러오는 중입니다.</p>
          <p v-else-if="selectedCamera && hasValidSearchTimeRange && recordings.length === 0" class="muted-text">선택한 카메라에 분석 기간과 겹치는 녹화본이 없습니다.</p>
          <p v-if="submitting" class="muted-text" role="status" aria-live="polite">{{ submissionProgress.processed }} / {{ submissionProgress.total }}건 처리했습니다.</p>
          <p v-if="batchResult" class="muted-text" role="status">생성 {{ batchResult.created }}건 · 이미 요청됨 {{ batchResult.skipped }}건 · 실패 {{ batchResult.failed }}건</p>
          <div v-if="batchResult?.failures.length" class="form-error" role="alert">
            <strong>실패한 녹화본</strong>
            <ul>
              <li v-for="failure in (batchResult?.failures || [])" :key="failure.recordingId">녹화 ID {{ failure.recordingId }}: {{ failure.message }}</li>
            </ul>
          </div>
        </div>
      </div>

      <section class="recording-job-section" aria-labelledby="recording-job-list-title">
        <div class="job-list-heading">
          <div>
            <h3 id="recording-job-list-title">등록된 분석 작업</h3>
            <p>선택한 녹화본의 분석 진행 상태와 결과를 확인합니다.</p>
          </div>
          <span class="job-list-count">{{ visibleJobs.length }}건</span>
        </div>
        <div class="job-list">
          <p v-if="visibleJobs.length === 0" class="empty-state">등록된 녹화 분석 작업이 없습니다.</p>
          <article v-for="job in visibleJobs" :key="job.jobId" class="job-card">
            <div class="job-card-head"><strong>JOB-{{ job.jobId }}</strong><StatusBadge :status="jobStatus(job.status)" /></div>
            <div class="job-meta-grid">
              <span><small>사건 번호</small><strong>{{ cases.find((item) => String(item.id) === String(job.caseId))?.caseNumber || job.caseId }}</strong></span>
              <span><small>녹화 ID</small><strong>{{ job.recordingId }}</strong></span>
              <span><small>요청 시각</small><strong>{{ formatDate(job.requestedAt) }}</strong></span>
            </div>
            <div class="progress" :class="`progress-${jobStatus(job.status)}`"><span :style="{ width: `${jobProgress(job.status)}%` }" /></div>
            <div class="job-progress-meta"><span><strong>{{ jobProgress(job.status) }}%</strong> 완료</span><span>상태 <strong>{{ job.status }}</strong></span></div>
            <div class="job-card-actions"><button v-if="job.status === 'SUCCEEDED'" class="primary-button" @click="openCandidateReview(job)">후보 검토</button><button v-if="['QUEUED', 'RUNNING'].includes(job.status)" class="ghost-button" @click="cancelJob(job)">취소</button><button v-if="job.status === 'FAILED'" class="reset-button" :disabled="!isSearchingCase(job.caseId)" :title="isSearchingCase(job.caseId) ? undefined : SEARCHING_CASE_REQUIRED_MESSAGE" @click="retryJob(job)">재시도</button></div>
          </article>
        </div>
      </section>
    </StateBlock>

    <div v-if="manualRecordingOpen" class="modal-backdrop" @click.self="closeManualRecording">
      <section class="modal manual-recording-modal" role="dialog" aria-modal="true" aria-labelledby="manual-recording-title">
        <div class="manual-recording-heading">
          <div>
            <h3 id="manual-recording-title">녹화본 추가</h3>
            <p>누락된 MP4 녹화본의 카메라와 과거 촬영 시작 시각을 지정합니다.</p>
          </div>
          <button type="button" class="icon-close-button" aria-label="녹화본 추가 닫기" :disabled="manualBusy" @click="closeManualRecording">×</button>
        </div>

        <div v-if="manualUploadResult" class="manual-recording-success" role="status">
          <strong>녹화본을 저장했습니다.</strong>
          <dl>
            <div><dt>녹화 ID</dt><dd>{{ manualUploadResult.id }}</dd></div>
            <div><dt>카메라</dt><dd>{{ manualCameraLabel(manualSelectedCamera) }}</dd></div>
            <div><dt>촬영 구간</dt><dd>{{ manualPeriodState.period?.startKst }} ~ {{ manualPeriodState.period?.endKst }} KST</dd></div>
            <div><dt>파일 크기</dt><dd>{{ formatFileSize(manualUploadResult.fileSize) }}</dd></div>
            <div><dt>등록 시각</dt><dd>{{ formatDate(manualUploadResult.createdAt) }}</dd></div>
          </dl>
          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeManualRecording">닫기</button>
            <button type="button" class="primary-button" @click="startAnotherManualRecording">다른 녹화본 추가</button>
          </div>
        </div>

        <template v-else>
          <div class="manual-recording-body">
            <section class="manual-recording-section" aria-labelledby="manual-camera-title">
              <div class="manual-recording-section-title">
                <strong id="manual-camera-title">1. 녹화 카메라</strong>
                <span v-if="manualSelectedCamera">선택: {{ manualCameraLabel(manualSelectedCamera) }}</span>
              </div>
              <form class="manual-camera-search" @submit.prevent="searchManualCameras">
                <input v-model="manualCameraSearchInput" aria-label="녹화 카메라 검색" placeholder="카메라 코드 또는 이름" :disabled="manualBusy" />
                <button type="submit" class="search-button" :disabled="manualBusy || manualCameraLoading">검색</button>
              </form>
              <StateBlock :loading="manualCameraLoading" :error="manualCameraError" :empty="manualCameraRows.length === 0" @retry="loadManualCameras">
                <div class="manual-camera-options" role="radiogroup" aria-label="녹화 카메라 선택">
                  <button
                    v-for="camera in manualCameraRows"
                    :key="camera.id"
                    type="button"
                    :class="['manual-camera-option', { selected: manualSelectedCamera?.id === camera.id }]"
                    role="radio"
                    :aria-checked="manualSelectedCamera?.id === camera.id"
                    :disabled="manualBusy"
                    @click="selectManualCamera(camera)"
                  >
                    <span><strong>{{ manualCameraLabel(camera) }}</strong><small>{{ camera.address }}</small></span>
                    <StatusBadge :status="camera.status" />
                  </button>
                </div>
                <BasePagination
                  :page="manualCameraPage"
                  :total-pages="manualCameraTotalPages"
                  :total-count="manualCameraTotalCount"
                  :disabled="manualBusy || manualCameraLoading"
                  @update:page="changeManualCameraPage"
                />
              </StateBlock>
            </section>

            <section class="manual-recording-section" aria-labelledby="manual-file-title">
              <div class="manual-recording-section-title">
                <strong id="manual-file-title">2. MP4 파일</strong>
                <span>최대 100 MiB · 60초 이하</span>
              </div>
              <label class="manual-recording-file-field">
                <input
                  ref="manualFileInput"
                  class="manual-recording-file-input"
                  type="file"
                  accept="video/mp4,.mp4"
                  :disabled="manualBusy || manualMetadataLoading"
                  @change="onManualFileChange"
                />
              </label>
              <div v-if="manualMetadataLoading" class="manual-file-summary" role="status">영상 메타데이터를 확인하고 있습니다.</div>
              <div v-else-if="manualForm.file" class="manual-file-summary">
                <span><strong>{{ manualForm.file.name }}</strong><small>{{ formatFileSize(manualForm.file.size) }}</small></span>
                <span><small>재생 시간</small><strong>{{ formatDuration(manualDuration) }}</strong></span>
                <button type="button" class="ghost-button" :disabled="manualBusy" @click="clearManualFile">선택 취소</button>
              </div>
              <p v-if="manualFileError" class="form-error" role="alert">{{ manualFileError }}</p>
            </section>

            <section class="manual-recording-section" aria-labelledby="manual-time-title">
              <div class="manual-recording-section-title">
                <strong id="manual-time-title">3. 촬영 시각</strong>
                <span>대한민국 표준시(KST)</span>
              </div>
              <div class="manual-recording-time-grid">
                <label>녹화 시작
                  <input
                    v-model="manualForm.startKst"
                    type="datetime-local"
                    step="1"
                    :max="manualMaxStartKst"
                    :disabled="manualBusy"
                    @input="invalidateManualPreparation"
                  />
                </label>
                <label>녹화 종료
                  <input :value="manualPeriodState.period?.endKst || ''" type="datetime-local" step="1" readonly />
                </label>
              </div>
              <p v-if="manualPeriodState.error" class="form-error" role="alert">{{ manualPeriodState.error }}</p>
            </section>

            <p v-if="manualPhaseMessage" class="manual-operation-status" role="status" aria-live="polite">{{ manualPhaseMessage }}</p>
            <div v-if="manualPhase === 'awaiting-overlap-confirmation'" class="manual-overlap-warning" role="alert">
              <strong>같은 시간대의 녹화본 {{ manualOverlapCount }}건이 이미 있습니다.</strong>
              <ul>
                <li v-for="recording in manualOverlapRows" :key="recording.id">
                  {{ formatManualOverlapRange(recording) }}
                </li>
              </ul>
              <p v-if="manualOverlapRemainingCount">외 {{ manualOverlapRemainingCount }}건</p>
              <p>촬영 구간이 겹치는 녹화본을 중복으로 추가할 수 있습니다. 그래도 계속할까요?</p>
            </div>
            <p v-if="manualError" class="form-error manual-operation-error" role="alert">{{ manualError }}</p>
          </div>

          <div class="modal-actions manual-recording-actions">
            <button type="button" class="ghost-button" :disabled="manualBusy" @click="closeManualRecording">취소</button>
            <button
              v-if="manualPhase === 'awaiting-overlap-confirmation'"
              type="button"
              class="primary-button manual-overlap-confirm-button"
              @click="confirmManualOverlap"
            >겹침을 확인하고 추가</button>
            <button
              v-else-if="manualFailureStage"
              type="button"
              class="primary-button manual-retry-button"
              @click="retryManualOperation"
            >{{ manualRetryLabel }}</button>
            <button
              v-else
              type="button"
              class="primary-button manual-recording-submit-button"
              :disabled="!manualCanStart || manualBusy"
              @click="checkOverlapAndUpload"
            >{{ manualBusy ? "처리 중..." : "녹화본 추가" }}</button>
          </div>
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.manual-recording-modal {
  width: min(760px, 100%);
  max-height: calc(100vh - 40px);
  overflow: auto;
}

.manual-recording-heading,
.manual-recording-section-title,
.manual-file-summary,
.manual-camera-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.manual-recording-heading h3,
.manual-recording-heading p,
.manual-overlap-warning p,
.manual-overlap-warning ul {
  margin: 0;
}

.manual-overlap-warning ul {
  display: grid;
  gap: 3px;
  margin-top: 8px;
  padding-left: 20px;
}

.manual-recording-heading p {
  margin-top: 4px;
  color: #64748b;
  font-size: 13px;
}

.manual-recording-body {
  display: grid;
  gap: 14px;
  margin-top: 18px;
}

.manual-recording-section {
  display: grid;
  gap: 10px;
  border: 1px solid #e1e7ed;
  border-radius: 8px;
  padding: 14px;
  background: #fbfcfd;
}

.manual-recording-section-title > span {
  color: #64748b;
  font-size: 12px;
}

.manual-camera-search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.manual-camera-options {
  display: grid;
  gap: 7px;
}

.manual-camera-option {
  width: 100%;
  border: 1px solid #d9e1e8;
  border-radius: 7px;
  padding: 9px 11px;
  background: #fff;
  color: #334155;
  text-align: left;
}

.manual-camera-option.selected {
  border-color: #4c92ad;
  background: #edf7fa;
  box-shadow: 0 0 0 2px rgba(76, 146, 173, .12);
}

.manual-camera-option > span,
.manual-camera-option small,
.manual-file-summary > span {
  display: grid;
  gap: 3px;
}

.manual-camera-option small,
.manual-file-summary small {
  color: #64748b;
  font-size: 12px;
}

.manual-recording-file-input {
  width: 100%;
  padding: 7px;
}

.manual-file-summary {
  border-radius: 7px;
  padding: 10px 12px;
  background: #eef5f8;
  font-size: 13px;
}

.manual-recording-time-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.manual-recording-time-grid label {
  display: grid;
  gap: 6px;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.manual-overlap-warning,
.manual-operation-status,
.manual-recording-success {
  border: 1px solid #ead38b;
  border-radius: 7px;
  padding: 11px 12px;
  background: #fffbeb;
  color: #6f5815;
  font-size: 13px;
}

.manual-operation-status {
  margin: 0;
  border-color: #bdd5ef;
  background: #eef6ff;
  color: #2c5f8a;
}

.manual-operation-error {
  margin: 0;
}

.manual-recording-actions {
  margin-top: 16px;
}

.manual-recording-success {
  margin-top: 18px;
  border-color: #bbdfca;
  background: #eef8f2;
  color: #2f6f54;
}

.manual-recording-success dl {
  display: grid;
  gap: 8px;
  margin: 14px 0 0;
}

.manual-recording-success dl > div {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 10px;
}

.manual-recording-success dt {
  color: #64748b;
}

.manual-recording-success dd {
  margin: 0;
  color: #253342;
  overflow-wrap: anywhere;
}

@media (max-width: 700px) {
  .manual-recording-time-grid {
    grid-template-columns: 1fr;
  }

  .manual-file-summary,
  .manual-recording-section-title {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
