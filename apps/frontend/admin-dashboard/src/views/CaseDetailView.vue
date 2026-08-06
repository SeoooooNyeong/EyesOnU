<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  closeCase,
  getCase,
  listCaseCameras,
  listSearchConditions,
  updateCaseStatus
} from "../api/caseApi";
import { fetchAdminCandidates } from "../api/candidateApi";
import { getCaseRoute } from "../api/routeApi";
import CaseSearchSetupCard from "../components/cases/CaseSearchSetupCard.vue";
import ConfirmModal from "../components/common/ConfirmModal.vue";
import {
  candidateSourceLabel,
  candidateSourceTone,
  formatCandidateDate,
  reviewStatusLabel,
  reviewStatusTone,
  similarityPercent,
  similarityTone
} from "../domain/candidateMapper";
import { mapCaseDetail, toBackendStatus } from "../domain/caseMapper";

const route = useRoute();
const router = useRouter();

const STATUS_STEPS = Object.freeze([
  { value: "received", label: "접수" },
  { value: "searching", label: "탐색 중" },
  { value: "candidate_found", label: "후보 발견" },
  { value: "field_search", label: "현장 탐색" },
  { value: "closed", label: "종료" }
]);

const ALLOWED_TRANSITIONS = Object.freeze({
  received: ["searching"],
  searching: ["candidate_found"],
  candidate_found: ["searching", "field_search"],
  field_search: ["searching"],
  closed: []
});

const rawCase = ref(null);
const item = ref(null);
const loading = ref(true);
const loadError = ref("");
const notFound = ref(false);
const actionMessage = ref("");
const searchSetupRef = ref(null);
const initialSearchConditions = ref([]);
const initialCaseCameras = ref([]);
const searchSetupLoading = ref(true);
const searchSetupError = ref("");
const searchReadiness = ref({
  usableConditionCount: 0,
  activeCameraCount: 0,
  ready: false,
  loading: true,
  error: ""
});
const searchingBlockMessage = ref("");

const candidates = ref([]);
const candidateLoading = ref(true);
const candidateError = ref("");
const candidateRefreshError = ref("");
const candidateTotalCount = ref(0);
const routePoints = ref([]);
const routeLoading = ref(true);
const routeError = ref("");

const nextStatus = ref("");
const statusModalOpen = ref(false);
const statusReason = ref("");
const statusReasonError = ref("");
const statusLoading = ref(false);

const closeModalOpen = ref(false);
const closeReason = ref("");
const closeReasonError = ref("");
const closeLoading = ref(false);
const forceCloseRequired = ref(false);
let loadRequestId = 0;
let searchSetupRequestId = 0;
let actionRequestId = 0;
let candidateRequestId = 0;
let routeRequestId = 0;
let candidateRefreshTimer;
let candidateRefreshInFlight = false;

const statusLabels = Object.freeze(Object.fromEntries(STATUS_STEPS.map((step) => [step.value, step.label])));
const statusOptions = computed(() => (
  ALLOWED_TRANSITIONS[item.value?.status] ?? []
).map((value) => ({ value, label: statusLabels[value] ?? value })));
const isClosed = computed(() => item.value?.status === "closed");
const isSearchingTarget = computed(() => nextStatus.value === "searching");
const statusConfirmDisabled = computed(() => (
  !statusReason.value.trim()
    || !nextStatus.value
    || (isSearchingTarget.value && !searchReadiness.value.ready)
));
const closeConfirmDisabled = computed(() => !closeReason.value.trim());
const closeModalTitle = computed(() => forceCloseRequired.value
  ? "사건을 강제로 종료할까요?"
  : "사건을 종료할까요?");
const closeModalMessage = computed(() => forceCloseRequired.value
  ? "미처리 후보 또는 실행 중인 작업이 있습니다. 강제 종료하면 실행 중인 작업이 취소됩니다."
  : "먼저 일반 종료를 시도합니다. 처리 중인 작업이 있으면 강제 종료 여부를 다시 확인합니다.");
const closeConfirmText = computed(() => forceCloseRequired.value ? "강제 종료" : "사건 종료");
const hasMoreCandidates = computed(() => candidateTotalCount.value > 20);
const personSummary = computed(() => {
  if (!item.value) return "—";
  const parts = [item.value.gender || null, item.value.age ? `${item.value.age}세` : null].filter(Boolean);
  return parts.length ? parts.join(" · ") : "—";
});

function syncNextStatus() {
  nextStatus.value = ALLOWED_TRANSITIONS[item.value?.status]?.[0] ?? "";
}

function applyRawCase(source) {
  rawCase.value = source;
  item.value = mapCaseDetail(source);
  syncNextStatus();
}

function applyStateResponse(state) {
  applyRawCase({ ...rawCase.value, ...state });
}

function readableError(error, fallback) {
  return error?.message || fallback;
}

function resetCandidateState() {
  candidateRequestId += 1;
  candidates.value = [];
  candidateLoading.value = true;
  candidateError.value = "";
  candidateRefreshError.value = "";
  candidateTotalCount.value = 0;
}

async function loadCandidates({ showLoading = true, preserveOnError = false } = {}) {
  const requestId = ++candidateRequestId;
  const caseId = String(route.params.caseId);
  if (showLoading) candidateLoading.value = true;
  if (!preserveOnError) candidateError.value = "";
  candidateRefreshError.value = "";

  try {
    const result = await fetchAdminCandidates({
      caseId,
      page: 0,
      size: 20,
      sort: "lastDetectedAt,desc"
    });
    if (requestId !== candidateRequestId || String(route.params.caseId) !== caseId) return false;
    candidates.value = result.rows || [];
    candidateTotalCount.value = Number(result.meta?.totalElements ?? candidates.value.length);
    candidateError.value = "";
    return true;
  } catch (error) {
    if (requestId !== candidateRequestId || String(route.params.caseId) !== caseId) return false;
    const message = readableError(error, "후보 목록을 불러오지 못했습니다.");
    if (preserveOnError) {
      candidateRefreshError.value = message;
    } else {
      candidates.value = [];
      candidateTotalCount.value = 0;
      candidateError.value = message;
    }
    return false;
  } finally {
    if (showLoading && requestId === candidateRequestId && String(route.params.caseId) === caseId) {
      candidateLoading.value = false;
    }
  }
}

async function loadRoutePoints({ showLoading = true, preserveOnError = false } = {}) {
  const requestId = ++routeRequestId;
  const caseId = String(route.params.caseId);
  if (showLoading) routeLoading.value = true;
  if (!preserveOnError) routeError.value = "";

  try {
    const result = await getCaseRoute(caseId);
    if (requestId !== routeRequestId || String(route.params.caseId) !== caseId) return false;
    routePoints.value = result?.points || [];
    routeError.value = "";
    return true;
  } catch (error) {
    if (requestId !== routeRequestId || String(route.params.caseId) !== caseId) return false;
    if (!preserveOnError) {
      routePoints.value = [];
      routeError.value = readableError(error, "확정 동선을 불러오지 못했습니다.");
    }
    return false;
  } finally {
    if (showLoading && requestId === routeRequestId && String(route.params.caseId) === caseId) {
      routeLoading.value = false;
    }
  }
}

function detectedTime(point) {
  const first = formatCandidateDate(point.firstDetectedAt);
  const last = formatCandidateDate(point.lastDetectedAt);
  return first === last ? first : `${first} ~ ${last}`;
}

async function refreshCandidates() {
  if (document.visibilityState === "hidden" || candidateLoading.value || candidateRefreshInFlight) return;
  candidateRefreshInFlight = true;
  try {
    await loadCandidates({ showLoading: false, preserveOnError: true });
    await loadRoutePoints({ showLoading: false, preserveOnError: true });
  } finally {
    candidateRefreshInFlight = false;
  }
}

async function loadSearchSetup(caseId) {
  const requestId = ++searchSetupRequestId;
  searchSetupLoading.value = true;
  searchSetupError.value = "";
  initialSearchConditions.value = [];
  initialCaseCameras.value = [];
  searchReadiness.value = {
    usableConditionCount: 0,
    activeCameraCount: 0,
    ready: false,
    loading: true,
    error: ""
  };

  const [conditionsResult, camerasResult] = await Promise.allSettled([
    listSearchConditions(caseId),
    listCaseCameras(caseId)
  ]);
  if (requestId !== searchSetupRequestId || String(route.params.caseId) !== caseId) return false;

  const setupErrors = [];
  if (conditionsResult.status === "fulfilled") {
    initialSearchConditions.value = conditionsResult.value || [];
  } else {
    setupErrors.push(readableError(conditionsResult.reason, "탐색 조건을 불러오지 못했습니다."));
  }
  if (camerasResult.status === "fulfilled") {
    initialCaseCameras.value = camerasResult.value || [];
  } else {
    setupErrors.push(readableError(camerasResult.reason, "배정 카메라를 불러오지 못했습니다."));
  }
  searchSetupError.value = [...new Set(setupErrors)].join(" ");
  searchSetupLoading.value = false;
  return setupErrors.length === 0;
}

async function loadCase({ showLoading = true, loadSetup = true } = {}) {
  const requestId = ++loadRequestId;
  const caseId = String(route.params.caseId);
  if (showLoading) loading.value = true;
  loadError.value = "";
  notFound.value = false;
  if (loadSetup) void loadSearchSetup(caseId);

  try {
    const source = await getCase(caseId);
    if (requestId !== loadRequestId || String(route.params.caseId) !== caseId) return false;
    applyRawCase(source);
    return true;
  } catch (error) {
    if (requestId !== loadRequestId || String(route.params.caseId) !== caseId) return false;
    item.value = null;
    rawCase.value = null;
    if (error?.status === 404) {
      notFound.value = true;
    } else {
      loadError.value = readableError(error, "사건 정보를 불러오지 못했습니다.");
    }
    return false;
  } finally {
    if (showLoading && requestId === loadRequestId && String(route.params.caseId) === caseId) {
      loading.value = false;
    }
  }
}

function refreshCaseFromSetup() {
  return loadCase({ showLoading: false, loadSetup: false });
}

function openStatusModal() {
  if (!nextStatus.value || isClosed.value) return;
  if (nextStatus.value === "searching" && !searchReadiness.value.ready) {
    searchingBlockMessage.value = searchReadiness.value.loading
      ? "실시간 탐색 설정을 확인하는 중입니다. 잠시 후 다시 시도해 주세요."
      : "탐색을 시작하려면 실시간 사용 가능 조건과 활성 배정 카메라가 각각 하나 이상 필요합니다.";
    searchSetupRef.value?.focusMissing?.();
    return;
  }
  statusReason.value = "";
  statusReasonError.value = "";
  actionMessage.value = "";
  statusModalOpen.value = true;
}

function closeStatusModal() {
  if (statusLoading.value) return;
  statusModalOpen.value = false;
  statusReasonError.value = "";
}

async function refetchAfterConflict(message, actionId, caseId) {
  if (
    await loadCase({ showLoading: false })
      && actionId === actionRequestId
      && String(item.value?.id) === caseId
  ) {
    actionMessage.value = message;
  }
}

async function changeStatus() {
  const reason = statusReason.value.trim();
  if (!reason) {
    statusReasonError.value = "상태 변경 사유를 입력해 주세요.";
    return;
  }
  if (nextStatus.value === "searching" && !searchReadiness.value.ready) {
    statusReasonError.value = "탐색 조건과 활성 배정 카메라를 먼저 등록해 주세요.";
    return;
  }

  const actionId = ++actionRequestId;
  const caseId = String(item.value.id);
  const previousStatus = item.value.status;
  const targetStatus = nextStatus.value;
  statusLoading.value = true;
  statusReasonError.value = "";
  try {
    const state = await updateCaseStatus(caseId, {
      status: toBackendStatus(targetStatus),
      reason
    });
    if (actionId !== actionRequestId || String(item.value?.id) !== caseId) return;
    applyStateResponse(state);
    statusModalOpen.value = false;
    actionMessage.value = `${statusLabels[previousStatus]}에서 ${statusLabels[targetStatus]} 상태로 변경했습니다.`;
  } catch (error) {
    if (actionId !== actionRequestId || String(item.value?.id) !== caseId) return;
    if (error?.status === 409) {
      statusModalOpen.value = false;
      await refetchAfterConflict(
        "다른 요청으로 사건 상태가 변경되어 최신 정보를 다시 불러왔습니다.",
        actionId,
        caseId
      );
    } else if (error?.status === 422) {
      const message = readableError(error, "현재 사건 상태에서는 요청한 상태로 변경할 수 없습니다.");
      const refreshed = await loadCase({ showLoading: false, loadSetup: false });
      if (actionId !== actionRequestId || String(item.value?.id) !== caseId) return;
      if (!refreshed) {
        statusModalOpen.value = false;
      } else if (item.value.status !== previousStatus) {
        statusModalOpen.value = false;
        actionMessage.value = `사건 상태가 '${statusLabels[item.value.status]}' 상태로 변경되어 최신 정보를 불러왔습니다.`;
      } else {
        if (targetStatus === "searching") await searchSetupRef.value?.reload?.();
        if (actionId !== actionRequestId || String(item.value?.id) !== caseId) return;
        statusReasonError.value = message;
      }
    } else {
      statusReasonError.value = readableError(error, "사건 상태를 변경하지 못했습니다.");
    }
  } finally {
    if (actionId === actionRequestId) statusLoading.value = false;
  }
}

function openCloseModal() {
  if (isClosed.value) return;
  closeReason.value = "";
  closeReasonError.value = "";
  forceCloseRequired.value = false;
  actionMessage.value = "";
  closeModalOpen.value = true;
}

function dismissCloseModal() {
  if (closeLoading.value) return;
  closeModalOpen.value = false;
  closeReasonError.value = "";
  forceCloseRequired.value = false;
}

async function submitClose() {
  const reason = closeReason.value.trim();
  if (!reason) {
    closeReasonError.value = "종료 사유를 입력해 주세요.";
    return;
  }

  const actionId = ++actionRequestId;
  const caseId = String(item.value.id);
  closeLoading.value = true;
  closeReasonError.value = "";
  try {
    const state = await closeCase(caseId, {
      reason,
      force: forceCloseRequired.value
    });
    if (actionId !== actionRequestId || String(item.value?.id) !== caseId) return;
    applyStateResponse(state);
    closeModalOpen.value = false;
    forceCloseRequired.value = false;
    actionMessage.value = "사건을 종료했습니다.";
  } catch (error) {
    if (actionId !== actionRequestId || String(item.value?.id) !== caseId) return;
    if (error?.status === 409 && error?.code === "CASE_CLOSE_CONFLICT") {
      const refreshed = await loadCase({ showLoading: false });
      if (actionId !== actionRequestId || String(item.value?.id) !== caseId) return;
      if (!refreshed) {
        closeModalOpen.value = false;
        return;
      }
      if (item.value?.status === "closed") {
        closeModalOpen.value = false;
        actionMessage.value = "사건이 이미 종료되어 최신 정보를 불러왔습니다.";
      } else {
        forceCloseRequired.value = true;
        closeReasonError.value = "미처리 후보 또는 실행 중인 작업이 있습니다. 계속하려면 강제 종료를 다시 확인해 주세요.";
      }
    } else if (error?.status === 409) {
      closeModalOpen.value = false;
      await refetchAfterConflict(
        "다른 요청으로 사건 상태가 변경되어 최신 정보를 다시 불러왔습니다.",
        actionId,
        caseId
      );
    } else {
      closeReasonError.value = readableError(error, "사건을 종료하지 못했습니다.");
    }
  } finally {
    if (actionId === actionRequestId) closeLoading.value = false;
  }
}

watch(statusReason, () => {
  if (statusReasonError.value === "상태 변경 사유를 입력해 주세요.") statusReasonError.value = "";
});
watch(nextStatus, () => {
  searchingBlockMessage.value = "";
});
watch(closeReason, () => {
  if (closeReasonError.value === "종료 사유를 입력해 주세요.") closeReasonError.value = "";
});
watch(() => route.params.caseId, () => {
  actionRequestId += 1;
  statusModalOpen.value = false;
  closeModalOpen.value = false;
  statusLoading.value = false;
  closeLoading.value = false;
  forceCloseRequired.value = false;
  actionMessage.value = "";
  searchingBlockMessage.value = "";
  resetCandidateState();
  routeRequestId += 1;
  routePoints.value = [];
  routeLoading.value = true;
  routeError.value = "";
  void loadCase();
  void loadCandidates();
  void loadRoutePoints();
});

onMounted(() => {
  void loadCase();
  void loadCandidates();
  void loadRoutePoints();
  candidateRefreshTimer = window.setInterval(refreshCandidates, 5000);
});
onBeforeUnmount(() => {
  loadRequestId += 1;
  searchSetupRequestId += 1;
  actionRequestId += 1;
  candidateRequestId += 1;
  routeRequestId += 1;
  if (candidateRefreshTimer) window.clearInterval(candidateRefreshTimer);
});
</script>

<template>
  <section v-if="loading" class="content-panel">
    <div class="state-view" role="status"><strong>사건 정보를 불러오는 중입니다.</strong></div>
  </section>

  <section v-else-if="notFound" class="content-panel">
    <div class="state-view error">
      <strong>사건을 찾을 수 없습니다.</strong>
      <p>삭제되었거나 존재하지 않는 사건 번호입니다.</p>
      <button type="button" @click="router.push('/admin/cases')">사건 목록으로</button>
    </div>
  </section>

  <section v-else-if="loadError" class="content-panel">
    <div class="state-view error">
      <strong>{{ loadError }}</strong>
      <button type="button" @click="loadCase()">다시 시도</button>
    </div>
  </section>

  <section v-else-if="item" class="detail-layout">
    <div class="case-detail-main">
      <article class="content-panel">
        <div class="section-heading case-detail-heading">
          <div>
            <p class="mono">{{ item.caseNumber }}</p>
            <h2>{{ item.name || "이름 미상" }}</h2>
          </div>
          <div class="status-flow case-status-steps" aria-label="현재 사건 상태">
            <span
              v-for="step in STATUS_STEPS"
              :key="step.value"
              :class="{ active: item.status === step.value }"
            >{{ step.label }}</span>
          </div>
        </div>

        <p v-if="actionMessage" class="case-action-message" role="status">{{ actionMessage }}</p>

        <div class="profile-block">
          <div class="portrait">
            <img v-if="item.photoUrl" :src="item.photoUrl" :alt="`${item.name || '실종자'} 기준 사진`" />
            <span v-else>등록된 사진이 없습니다.</span>
          </div>
          <div class="info-grid">
            <span>성별/나이<strong>{{ personSummary }}</strong></span>
            <span>신고자<strong>{{ item.reporter || "—" }}</strong></span>
            <span>신고 시간<strong>{{ item.reportedAt || "—" }}</strong></span>
            <span>마지막 목격<strong>{{ item.lastSeenAt || "—" }}</strong></span>
            <span>목격 위치<strong>{{ item.lastSeenLocation || "—" }}</strong></span>
            <span>담당자<strong>{{ item.assignee && item.assignee !== "-" ? item.assignee : "미배정" }}</strong></span>
            <span class="info-wide">인상착의<strong>{{ item.appearance || "—" }}</strong></span>
            <span class="info-wide">실종 경위<strong>{{ item.reportContent || "—" }}</strong></span>
            <span v-if="item.closedAt" class="info-wide">종료 일시<strong>{{ item.closedAt }}</strong></span>
            <span class="profile-action">
              <button
                type="button"
                class="ghost-button"
                @click="router.push(`/admin/cases/${item.id}/edit`)"
              >{{ isClosed ? "사진 관리" : "사건 정보 수정" }}</button>
            </span>
          </div>
        </div>

        <CaseSearchSetupCard
          ref="searchSetupRef"
          :case-id="item.id"
          :closed="isClosed"
          :conditions="initialSearchConditions"
          :cameras="initialCaseCameras"
          :loading="searchSetupLoading"
          :error="searchSetupError"
          @readiness-change="searchReadiness = $event"
          @case-refresh-requested="refreshCaseFromSetup"
        />

        <div class="section-heading status-change-heading">
          <div>
            <h2>상태 관리</h2>
            <p v-if="isClosed">종료된 사건은 더 이상 상태를 변경할 수 없습니다.</p>
            <p v-else-if="statusOptions.length">백엔드에서 허용하는 다음 상태만 선택할 수 있습니다.</p>
            <p v-else>현재 상태에서 가능한 일반 상태 전이가 없습니다.</p>
          </div>
          <div v-if="!isClosed" class="status-actions">
            <select v-if="statusOptions.length" v-model="nextStatus" aria-label="변경할 사건 상태">
              <option v-for="option in statusOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
            <button
              v-if="statusOptions.length"
              type="button"
              class="primary-button"
              @click="openStatusModal"
            >상태 변경</button>
            <button type="button" class="danger-button" @click="openCloseModal">사건 종료</button>
          </div>
        </div>
        <div v-if="!isClosed && isSearchingTarget && !searchReadiness.ready" class="searching-prerequisite" role="note">
          <div>
            <strong>{{ searchingBlockMessage || "실시간 탐색 설정이 아직 준비되지 않았습니다." }}</strong>
            <span>사용 가능 조건 {{ searchReadiness.usableConditionCount }}개 · 활성 카메라 {{ searchReadiness.activeCameraCount }}대</span>
          </div>
          <div>
            <button
              v-if="searchReadiness.usableConditionCount === 0"
              type="button"
              @click="searchSetupRef?.focusConditions?.()"
            >조건 설정으로 이동</button>
            <button
              v-if="searchReadiness.activeCameraCount === 0"
              type="button"
              @click="searchSetupRef?.focusCameras?.()"
            >카메라 설정으로 이동</button>
          </div>
        </div>
      </article>

      <article class="content-panel">
        <h2>추정 동선</h2>
        <div v-if="routeLoading" class="state-view" role="status">
          <strong>확정 동선을 불러오는 중입니다.</strong>
        </div>
        <div v-else-if="routeError" class="state-view error">
          <strong>{{ routeError }}</strong>
          <button type="button" @click="loadRoutePoints()">다시 시도</button>
        </div>
        <div v-else-if="routePoints.length === 0" class="state-view">
          <strong>확인된 동선이 없습니다.</strong>
          <p>후보를 확정하면 해당 카메라 위치가 여기에 표시됩니다.</p>
        </div>
        <div v-if="routePoints.length > 0" class="case-route-content">
          <div class="case-route-summary">
            <div>
              <strong>확정 후보 {{ routePoints.length }}건</strong>
              <p>확정된 후보를 탐지 시각 순으로 표시합니다.</p>
            </div>
            <button
              type="button"
              class="ghost-button"
              @click="router.push({ path: '/admin/routes', query: { caseId: item.id } })"
            >지도에서 보기</button>
          </div>
          <div
            v-for="(point, index) in routePoints"
            :key="point.candidateId"
            class="timeline-item"
          >
            <div class="timeline-meta">
              <strong>{{ index + 1 }}</strong>
              <time>{{ detectedTime(point) }}</time>
            </div>
            <h3>{{ point.cameraName || point.cameraCode }}</h3>
            <p>{{ point.address || "카메라 주소 미등록" }}</p>
            <p class="route-point-meta">
              {{ candidateSourceLabel(point.sourceType) }} · 유사도 {{ similarityPercent(point.bestSimilarity) }}%
              · {{ point.latitude }}, {{ point.longitude }}
            </p>
            <button
              type="button"
              class="ghost-button"
              @click="router.push(`/admin/candidates/${point.candidateId}`)"
            >후보 근거 보기</button>
          </div>
        </div>
      </article>
    </div>

    <article class="content-panel">
      <div class="section-heading">
        <div>
          <h2>후보 탐지 목록</h2>
          <p v-if="candidateTotalCount">총 {{ candidateTotalCount }}건</p>
        </div>
        <button
          v-if="hasMoreCandidates"
          type="button"
          class="ghost-button"
          @click="router.push({ path: '/admin/candidates', query: { caseId: item.id } })"
        >전체 후보 보기</button>
      </div>
      <div v-if="candidateLoading" class="state-view" role="status">
        <strong>후보 목록을 불러오는 중입니다.</strong>
      </div>
      <div v-else-if="candidateError" class="state-view error">
        <strong>{{ candidateError }}</strong>
        <button type="button" @click="loadCandidates()">다시 시도</button>
      </div>
      <template v-else>
        <p v-if="candidateRefreshError" class="error-message" role="status">
          최신 후보 목록을 갱신하지 못했습니다. {{ candidateRefreshError }}
        </p>
        <div v-if="candidates.length === 0" class="state-view">
          <strong>탐지된 후보가 없습니다.</strong>
          <p>탐색 중 발견된 후보가 여기에 표시됩니다.</p>
        </div>
        <template v-else>
          <button
            v-for="candidate in candidates"
            :key="candidate.id"
            type="button"
            class="candidate-row detail-candidate"
            @click="router.push(`/admin/candidates/${candidate.id}`)"
          >
            <span class="image-placeholder">
              <img
                v-if="candidate.cropUrl"
                :src="candidate.cropUrl"
                :alt="`${candidate.cameraCode || 'CCTV'} 후보 캡처`"
              />
              <span v-else>이미지 없음</span>
            </span>
            <span class="candidate-meta">
              <strong>{{ candidate.cameraCode || "CCTV 미등록" }}</strong>
              <small>{{ candidate.cameraName || "카메라 위치 미등록" }}</small>
              <small>{{ formatCandidateDate(candidate.lastDetectedAt) }}</small>
              <small>
                최고 유사도
                <b :class="['similarity-score', similarityTone(candidate.bestSimilarity)]">
                  {{ similarityPercent(candidate.bestSimilarity) }}%
                </b>
              </small>
              <span :class="['status-badge', candidateSourceTone(candidate.sourceType)]">
                {{ candidateSourceLabel(candidate.sourceType) }}
              </span>
              <span :class="['status-badge', reviewStatusTone(candidate.reviewStatus)]">
                {{ reviewStatusLabel(candidate.reviewStatus) }}
              </span>
            </span>
          </button>
        </template>
      </template>
    </article>

    <ConfirmModal
      v-model:reason="statusReason"
      :open="statusModalOpen"
      title="사건 상태를 변경할까요?"
      :message="`${statusLabels[item.status]}에서 ${statusLabels[nextStatus]} 상태로 변경합니다.`"
      confirm-text="상태 변경"
      show-reason
      :reason-error="statusReasonError"
      :loading="statusLoading"
      :confirm-disabled="statusConfirmDisabled"
      @close="closeStatusModal"
      @confirm="changeStatus"
    />

    <ConfirmModal
      v-model:reason="closeReason"
      :open="closeModalOpen"
      :title="closeModalTitle"
      :message="closeModalMessage"
      :confirm-text="closeConfirmText"
      show-reason
      :reason-error="closeReasonError"
      :loading="closeLoading"
      :confirm-disabled="closeConfirmDisabled"
      @close="dismissCloseModal"
      @confirm="submitClose"
    />
  </section>
</template>

<style scoped>
.case-action-message {
  margin: 0 0 18px;
  border: 1px solid #a9cfdd;
  border-radius: 7px;
  padding: 10px 12px;
  background: #eef8fb;
  color: #235f7c;
  font-size: 13px;
  font-weight: 700;
}

.portrait span {
  padding: 18px;
  color: #64748b;
}

.status-change-heading {
  margin-top: 24px;
  border-top: 1px solid #e4e8ef;
  padding-top: 20px;
}

.status-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.searching-prerequisite {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: -4px;
  border: 1px solid #ead38b;
  border-radius: 7px;
  padding: 10px 12px;
  background: #fffbeb;
}

.searching-prerequisite > div:first-child { display: grid; gap: 3px; }
.searching-prerequisite strong { color: #7a5a18; font-size: 12px; }
.searching-prerequisite span { color: #8b6b2c; font-size: 11px; }
.searching-prerequisite > div:last-child { display: inline-flex; gap: 7px; flex-wrap: wrap; }
.searching-prerequisite button {
  min-height: 32px;
  border: 1px solid #d6bd78;
  border-radius: 6px;
  padding: 5px 9px;
  background: #fff;
  color: #745516;
  font-size: 11px;
  font-weight: 800;
}

.danger-button {
  min-height: 38px;
  border: 1px solid #d88982;
  border-radius: 7px;
  padding: 0 14px;
  background: #fff0ee;
  color: #9b382f;
  font-weight: 700;
}

.case-route-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
}

.case-route-summary > div { display: grid; gap: 4px; }
.case-route-summary p { margin: 0; color: #64748b; font-size: 12px; }
.case-route-summary .ghost-button { flex: 0 0 auto; }
</style>
