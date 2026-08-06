<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { LayoutGrid, Table2 } from "lucide-vue-next";
import { useRoute, useRouter } from "vue-router";
import { fetchAdminCandidates } from "../api/candidateApi";
import { listCameras } from "../api/cameraApi";
import { listCases } from "../api/caseApi";
import BasePagination from "../components/common/BasePagination.vue";
import {
  candidateSourceLabel,
  candidateSourceTone,
  formatCandidateDate,
  reviewStatusLabel,
  reviewStatusTone,
  similarityPercent,
  similarityTone
} from "../domain/candidateMapper";

const router = useRouter();
const route = useRoute();
const filters = reactive({
  caseId: "",
  source: "all",
  review: "all",
  cameraId: "",
  sort: "pendingFirst",
  view: "card"
});
const rows = ref([]);
const cases = ref([]);
const cameras = ref([]);
const caseDropdownOpen = ref(false);
const loading = ref(true);
const error = ref("");
const page = ref(1);
const PAGE_SIZE = 20;
const totalPages = ref(1);
const totalCount = ref(0);
const resolvedCollapsed = ref(true);
const selectedCase = computed(() => cases.value.find((item) => String(item.id) === String(filters.caseId)));
const resolvedStatuses = new Set(["CONFIRMED", "REJECTED"]);
const resolvedRows = computed(() => rows.value.filter((item) => resolvedStatuses.has(String(item.reviewStatus || "").toUpperCase())));
const shouldCollapseResolved = computed(() => resolvedCollapsed.value && filters.review === "all");
const displayRows = computed(() => shouldCollapseResolved.value
  ? rows.value.filter((item) => !resolvedStatuses.has(String(item.reviewStatus || "").toUpperCase()))
  : rows.value);

const listParams = ({ pageNumber = page.value - 1, size = PAGE_SIZE, reviewStatus } = {}) => ({
  caseId: filters.caseId || undefined,
  sourceType: filters.source === "all" ? undefined : filters.source,
  reviewStatus: reviewStatus || (filters.review === "all" ? undefined : filters.review.toUpperCase()),
  cameraId: filters.cameraId || undefined,
  page: pageNumber,
  size,
  sort: filters.sort
});

const openCandidate = (candidate) => {
  const query = Object.fromEntries(Object.entries(listParams({ pageNumber: undefined }))
    .filter(([key, value]) => !["page", "size"].includes(key) && value !== undefined && value !== ""));
  router.push({ path: `/admin/candidates/${candidate.id}`, query });
};

const loadCases = async () => {
  const result = await listCases({ page: 0, size: 100, sort: "reportedAt,desc" });
  cases.value = (result.data || []).map((item) => ({
    id: item.id,
    caseNumber: item.caseNumber,
    name: item.missingName || item.name
  }));

  const initialCaseNumber = String(route.query.caseNumber || "");
  const initialCaseId = String(route.query.caseId || "");
  const initialCase = cases.value.find((item) => String(item.id) === initialCaseId)
    || cases.value.find((item) => item.caseNumber === initialCaseNumber);
  if (initialCase) filters.caseId = String(initialCase.id);
};

const loadCameras = async () => {
  const result = await listCameras({ page: 0, size: 100, sort: "cameraCode,asc" });
  cameras.value = result.data || [];
};

const candidateErrorMessage = (exception) => {
  const message = String(exception?.message || "");
  if (message.includes("sort must be one of")) {
    return "정렬 기준을 불러오지 못했습니다. 서버를 최신 버전으로 업데이트한 뒤 다시 시도해 주세요.";
  }
  if (message.includes("reviewStatus must be")) {
    return "판정 상태 필터가 올바르지 않습니다.";
  }
  return message || "후보 목록을 불러오지 못했습니다.";
};

const load = async ({ showLoading = true } = {}) => {
  if (showLoading) loading.value = true;
  error.value = "";
  try {
    const result = await fetchAdminCandidates(listParams());
    rows.value = result.rows || [];
    totalPages.value = Math.max(1, result.meta?.totalPages || 1);
    totalCount.value = result.meta?.totalElements || 0;
  } catch (exception) {
    rows.value = [];
    totalPages.value = 1;
    totalCount.value = 0;
    error.value = candidateErrorMessage(exception);
  } finally {
    loading.value = false;
  }
};

let refreshTimer;
const refresh = () => {
  if (document.visibilityState === "hidden") return;
  return load({ showLoading: false });
};

const selectCase = (caseItem) => {
  filters.caseId = caseItem?.id ? String(caseItem.id) : "";
  caseDropdownOpen.value = false;
};

watch(() => [filters.caseId, filters.source, filters.review, filters.cameraId, filters.sort], () => {
  if (page.value !== 1) {
    page.value = 1;
    return;
  }
  load();
});
watch(page, load);

onMounted(async () => {
  try {
    await Promise.all([loadCases(), loadCameras()]);
  } catch {
    cases.value = [];
    cameras.value = [];
  }
  await load();
  refreshTimer = window.setInterval(refresh, 5000);
});

onUnmounted(() => {
  if (refreshTimer) window.clearInterval(refreshTimer);
});
</script>

<template>
  <section class="content-panel">
    <div class="section-heading">
      <div>
        <h2>후보 검토 목록</h2>
        <p>미판정 후보를 우선 확인하고, 유사도와 탐지 시각 기준으로 빠르게 검토합니다.</p>
      </div>
      <div class="candidate-heading-actions">
        <div class="segmented view-toggle">
          <button :class="{ active: filters.view === 'card' }" title="카드 보기" aria-label="카드 보기" @click="filters.view = 'card'"><LayoutGrid :size="17" /></button>
          <button :class="{ active: filters.view === 'table' }" title="테이블 보기" aria-label="테이블 보기" @click="filters.view = 'table'"><Table2 :size="17" /></button>
        </div>
      </div>
    </div>

    <div class="filter-bar candidate-filter-bar">
      <label class="case-select-field">사건
        <div class="case-picker">
          <button type="button" class="case-picker-trigger" @click="caseDropdownOpen = !caseDropdownOpen">{{ selectedCase?.caseNumber || "전체 사건" }}</button>
          <div v-if="caseDropdownOpen" class="case-picker-menu">
            <button type="button" class="case-picker-option" @click="selectCase()">전체 사건</button>
            <button v-for="caseItem in cases" :key="caseItem.id" type="button" class="case-picker-option" @click="selectCase(caseItem)">{{ caseItem.caseNumber }} · {{ caseItem.name || "이름 미등록" }}</button>
          </div>
        </div>
      </label>
      <label>카메라
        <select v-model="filters.cameraId">
          <option value="">전체 카메라</option>
          <option v-for="camera in cameras" :key="camera.id" :value="String(camera.id)">{{ camera.cameraCode }}{{ camera.cameraName ? ` · ${camera.cameraName}` : "" }}</option>
        </select>
      </label>
      <label>판정 상태
        <select v-model="filters.review">
          <option value="all">전체</option>
          <option value="pending">미판정</option>
          <option value="kept">후보 유지</option>
          <option value="confirmed">대상 확인</option>
          <option value="rejected">대상 아님</option>
        </select>
      </label>
      <div class="candidate-source-filter">
        <strong class="candidate-source-filter-label">영상 유형</strong>
        <div class="candidate-source-options" role="group" aria-label="영상 유형">
          <button type="button" :class="['candidate-source-option', { active: filters.source === 'all' }]" :aria-pressed="filters.source === 'all'" @click="filters.source = 'all'">전체</button>
          <button type="button" :class="['candidate-source-option', 'green', { active: filters.source === 'REALTIME' }]" :aria-pressed="filters.source === 'REALTIME'" @click="filters.source = 'REALTIME'">실시간</button>
          <button type="button" :class="['candidate-source-option', 'blue', { active: filters.source === 'RECORDING_ANALYSIS' }]" :aria-pressed="filters.source === 'RECORDING_ANALYSIS'" @click="filters.source = 'RECORDING_ANALYSIS'">녹화영상</button>
        </div>
      </div>
    </div>

    <div class="candidate-list-toolbar">
      <div class="candidate-list-actions">
        <div class="resolved-candidates-toggle-row">
          <span class="resolved-candidates-toggle-label">확정·제외 후보 <strong>{{ resolvedRows.length }}건</strong></span>
          <button class="resolved-candidates-toggle" type="button" role="switch" :disabled="resolvedRows.length === 0" :aria-checked="resolvedRows.length > 0 && !shouldCollapseResolved" :aria-label="resolvedRows.length === 0 ? '확정·제외 후보 없음' : (shouldCollapseResolved ? '확정·제외 후보 펼치기' : '확정·제외 후보 접기')" @click="resolvedCollapsed = !resolvedCollapsed">
            <span class="resolved-candidates-toggle-track" aria-hidden="true">
              <span class="resolved-candidates-toggle-thumb"></span>
            </span>
          </button>
        </div>

        <div class="candidate-sort-control">
          <label for="candidate-sort">정렬
            <select id="candidate-sort" class="candidate-sort-select" v-model="filters.sort">
              <option value="pendingFirst">미판정 우선</option>
              <option value="realtimeFirst">실시간 우선</option>
              <option value="bestSimilarity,desc">유사도 높은 순</option>
              <option value="lastDetectedAt,desc">탐지 최신순</option>
            </select>
          </label>
        </div>
      </div>
    </div>

    <p v-if="loading" class="empty-state">후보 목록을 불러오는 중입니다.</p>
    <p v-else-if="error" class="error-message">{{ error }}</p>
    <p v-else-if="rows.length === 0" class="empty-state">조회된 후보가 없습니다.</p>
    <p v-else-if="displayRows.length === 0" class="empty-state">접힌 확정·제외 후보 외에 표시할 후보가 없습니다.</p>
    <template v-else>
      <div v-if="filters.view === 'card'" class="candidate-grid">
        <button v-for="item in displayRows" :key="item.id" :class="['candidate-card', `review-${reviewStatusTone(item.reviewStatus)}`]" @click="openCandidate(item)">
          <div class="candidate-card-top-row">
            <span :class="['status-badge', 'candidate-source-badge', candidateSourceTone(item.sourceType)]">{{ candidateSourceLabel(item.sourceType) }}</span>
            <span :class="['status-badge', 'candidate-review-badge', reviewStatusTone(item.reviewStatus)]">{{ reviewStatusLabel(item.reviewStatus) }}</span>
          </div>
          <img v-if="item.cropUrl" :src="item.cropUrl" alt="후보 캡처" />
          <span v-else class="image-placeholder large">이미지 없음</span>
          <div class="candidate-card-content">
            <strong>{{ item.caseNumber }}</strong>
            <p class="candidate-detection-meta">{{ item.cameraCode }} · {{ formatCandidateDate(item.lastDetectedAt) }}</p>
            <b :class="['similarity-score', similarityTone(item.bestSimilarity)]">{{ similarityPercent(item.bestSimilarity) }}%</b>
            <p class="candidate-location">{{ item.cameraName || "카메라 위치 미등록" }}</p>
          </div>
        </button>
      </div>
      <div v-else class="table-scroll">
        <table class="case-table">
          <thead><tr><th>사건</th><th>출처</th><th>CCTV</th><th>카메라</th><th>최근 탐지</th><th>유사도</th><th>상태</th><th></th></tr></thead>
          <tbody>
            <tr v-for="item in displayRows" :key="item.id">
              <td>{{ item.caseNumber }}</td>
              <td><span :class="['status-badge', 'candidate-source-badge', candidateSourceTone(item.sourceType)]">{{ candidateSourceLabel(item.sourceType) }}</span></td>
              <td>{{ item.cameraCode }}</td>
              <td>{{ item.cameraName || "-" }}</td>
              <td>{{ formatCandidateDate(item.lastDetectedAt) }}</td>
              <td :class="['similarity-score', similarityTone(item.bestSimilarity)]">{{ similarityPercent(item.bestSimilarity) }}%</td>
              <td><span :class="['status-badge', 'candidate-review-badge', reviewStatusTone(item.reviewStatus)]">{{ reviewStatusLabel(item.reviewStatus) }}</span></td>
              <td><button class="ghost-button" @click="openCandidate(item)">상세 보기</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <BasePagination v-model:page="page" :total-pages="totalPages" :total-count="totalCount" />
    </template>
  </section>
</template>
