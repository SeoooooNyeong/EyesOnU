<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import StateBlock from "../components/common/StateBlock.vue";
import StatusBadge from "../components/common/StatusBadge.vue";
import { listCases, listCaseCameras, listSearchConditions } from "../api/caseApi";
import {
  createRecordingAnalysisJob,
  fetchRecordingAnalysisJob,
  listAdminRecordings,
  listRecordingAnalysisJobs,
  listRecordingAnalysisJobsForDashboard,
  cancelRecordingAnalysisJob,
  retryRecordingAnalysisJob
} from "../api/recordingApi";

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

const formatDate = (value) => value ? new Date(value).toLocaleString("ko-KR") : "-";
const cameraLabel = (camera) => camera.cameraName
  ? `${camera.cameraCode || `카메라 ${camera.cameraId}`} · ${camera.cameraName}`
  : camera.cameraCode || `카메라 ${camera.cameraId}`;
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
});
</script>

<template>
  <section class="content-panel">
    <div class="section-heading">
      <div><h2>녹화 영상 탐색</h2><p>사건의 탐색 조건과 카메라를 선택해 기간 내 녹화본의 분석 작업을 등록합니다.</p></div>
    </div>

    <StateBlock :loading="loading" :error="loadError" @retry="load">
      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>

      <div class="recording-search-form">
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
          <label>탐색 지역
            <input :value="selectedCondition?.searchArea || ''" placeholder="탐색 조건에 저장된 지역" readonly />
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
        <section v-if="hasValidSearchTimeRange" class="analysis-range-control" aria-labelledby="analysis-range-title">
          <div class="analysis-range-heading">
            <span id="analysis-range-title">분석 기간</span>
            <strong class="analysis-range-selection">{{ formatDate(analysisRangeStart) }} ~ {{ formatDate(analysisRangeEnd) }}</strong>
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
          <p class="analysis-range-note">선택한 기간과 겹치는 녹화본 전체를 분석합니다.</p>
        </section>
        <p v-if="selectedCase && !selectedCaseIsSearching" class="muted-text">{{ SEARCHING_CASE_REQUIRED_MESSAGE }}</p>
        <p v-else-if="selectedCondition && !hasValidSearchTimeRange" class="muted-text">탐색 시작·종료 시간이 필요한 조건입니다.</p>
        <p v-else-if="analysisRangeDirty" class="muted-text">핸들을 놓으면 선택한 분석 기간의 녹화본을 조회합니다.</p>
        <p v-else-if="recordingsLoading" class="muted-text">탐색 기간과 겹치는 녹화본을 불러오는 중입니다.</p>
        <p v-else-if="selectedCamera && hasValidSearchTimeRange && recordings.length === 0" class="muted-text">선택한 카메라에 분석 기간과 겹치는 녹화본이 없습니다.</p>
        <p v-else-if="selectedCamera && hasValidSearchTimeRange" class="muted-text">분석 대상 녹화본 {{ recordings.length }}개 · {{ cameraLabel(selectedCamera) }}</p>
        <p v-if="submitting" class="muted-text" role="status" aria-live="polite">{{ submissionProgress.processed }} / {{ submissionProgress.total }}건 처리했습니다.</p>
        <p v-if="batchResult" class="muted-text" role="status">생성 {{ batchResult.created }}건 · 이미 요청됨 {{ batchResult.skipped }}건 · 실패 {{ batchResult.failed }}건</p>
        <div v-if="batchResult?.failures.length" class="form-error" role="alert">
          <strong>실패한 녹화본</strong>
          <ul>
            <li v-for="failure in batchResult.failures" :key="failure.recordingId">녹화 ID {{ failure.recordingId }}: {{ failure.message }}</li>
          </ul>
        </div>
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
    </StateBlock>
  </section>
</template>
