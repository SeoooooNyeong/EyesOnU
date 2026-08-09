<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { listCases } from "../api/caseApi";
import { getCaseRoute } from "../api/routeApi";
import RouteMap from "../components/routes/RouteMap.vue";
import { candidateSourceLabel, formatCandidateDate, similarityPercent } from "../domain/candidateMapper";

const route = useRoute();
const router = useRouter();
const cases = ref([]);
const selectedCaseId = ref("");
const caseDropdownOpen = ref(false);
const points = ref([]);
const loading = ref(true);
const error = ref("");
const followMode = ref(false);
const followIndex = ref(0);
const selectedPointIndex = ref(-1);
let followTimer;
let routeRequestId = 0;

const selectedCase = computed(() => cases.value.find(
  (item) => String(item.id) === String(selectedCaseId.value)
));
const activePointIndex = computed(() => followMode.value ? followIndex.value : selectedPointIndex.value);

function stopFollowing() {
  followMode.value = false;
  window.clearInterval(followTimer);
  followTimer = undefined;
}

async function loadRoute(caseId) {
  const requestId = ++routeRequestId;
  stopFollowing();
  selectedPointIndex.value = -1;
  points.value = [];
  error.value = "";
  if (!caseId) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await getCaseRoute(caseId);
    if (requestId !== routeRequestId || String(selectedCaseId.value) !== String(caseId)) return;
    points.value = result?.points || [];
  } catch (exception) {
    if (requestId !== routeRequestId || String(selectedCaseId.value) !== String(caseId)) return;
    error.value = exception.message || "추정 동선을 불러오지 못했습니다.";
  } finally {
    if (requestId === routeRequestId && String(selectedCaseId.value) === String(caseId)) {
      loading.value = false;
    }
  }
}

async function loadCases() {
  loading.value = true;
  error.value = "";
  try {
    const result = await listCases({ page: 0, size: 100, sort: "reportedAt,desc" });
    cases.value = result.data || [];
    const requestedId = String(route.query.caseId || "");
    const initial = cases.value.find((item) => String(item.id) === requestedId) || cases.value[0];
    selectedCaseId.value = initial ? String(initial.id) : "";
    if (!initial) loading.value = false;
  } catch (exception) {
    cases.value = [];
    loading.value = false;
    error.value = exception.message || "사건 목록을 불러오지 못했습니다.";
  }
}

function selectCase(caseItem) {
  selectedCaseId.value = String(caseItem.id);
  caseDropdownOpen.value = false;
}

function toggleRouteFollow() {
  if (!points.value.length) return;
  if (followMode.value) {
    stopFollowing();
    return;
  }
  followMode.value = true;
  selectedPointIndex.value = -1;
  followIndex.value = 0;
  followTimer = window.setInterval(() => {
    followIndex.value = (followIndex.value + 1) % points.value.length;
  }, 2200);
}

function selectPoint(index) {
  selectedPointIndex.value = index;
}

function detectedTime(point) {
  const first = formatCandidateDate(point.firstDetectedAt);
  const last = formatCandidateDate(point.lastDetectedAt);
  return first === last ? first : `${first} ~ ${last}`;
}

watch(selectedCaseId, (caseId) => {
  const query = { ...route.query };
  if (caseId) query.caseId = caseId;
  else delete query.caseId;
  router.replace({ query });
  loadRoute(caseId);
});

onMounted(loadCases);
onUnmounted(() => {
  routeRequestId += 1;
  stopFollowing();
});
</script>

<template>
  <section class="route-layout">
    <article class="content-panel route-main-panel">
      <div class="section-heading">
        <label class="case-select-field route-case-selector">
          <span class="route-case-label">사건 선택</span>
          <div class="case-picker">
            <button type="button" class="case-picker-trigger" @click="caseDropdownOpen = !caseDropdownOpen">
              {{ selectedCase?.caseNumber || "사건 선택" }}
            </button>
            <div v-if="caseDropdownOpen" class="case-picker-menu">
              <button
                v-for="caseItem in cases"
                :key="caseItem.id"
                type="button"
                class="case-picker-option"
                @click="selectCase(caseItem)"
              >
                <span>{{ caseItem.caseNumber }}</span>
                <div class="case-hover-card">
                  <strong>{{ caseItem.missingName || "이름 미등록" }}</strong>
                  <span>{{ caseItem.gender || "성별 미등록" }}</span>
                  <span>{{ caseItem.lastSeenAddress || "마지막 목격지 미등록" }}</span>
                  <span>{{ formatCandidateDate(caseItem.reportedAt) }} 접수</span>
                </div>
              </button>
            </div>
          </div>
        </label>
        <div class="route-follow-actions">
          <button
            type="button"
            class="route-follow-link"
            :class="{ active: followMode }"
            :disabled="!points.length || loading"
            @click="toggleRouteFollow"
          >{{ followMode ? "동선 따라가기 중지" : "동선 따라보기" }}</button>
          <span>확정된 관측 지점을 시간순으로 확인합니다.</span>
        </div>
      </div>

      <div v-if="loading" class="state-view" role="status"><strong>추정 동선을 불러오는 중입니다.</strong></div>
      <div v-else-if="error" class="state-view error">
        <strong>{{ error }}</strong>
        <button type="button" @click="selectedCaseId ? loadRoute(selectedCaseId) : loadCases()">다시 시도</button>
      </div>
      <div v-else-if="!selectedCase" class="state-view"><strong>조회할 사건이 없습니다.</strong></div>
      <div v-else-if="points.length === 0" class="state-view">
        <strong>확정된 동선이 없습니다.</strong>
        <p>후보를 확정하면 해당 카메라 위치가 시간순으로 표시됩니다.</p>
      </div>
      <RouteMap
        v-else
        :points="points"
        :active-index="activePointIndex"
        @select="selectPoint"
      />
      <p v-if="points.length" class="route-map-caption">
        선은 확정 후보가 관측된 카메라를 시간순으로 연결한 추정선이며 실제 도로 이동 경로가 아닙니다.
      </p>
    </article>

    <aside class="content-panel route-timeline-panel">
      <h2>동선 목록</h2>
      <p v-if="!points.length" class="empty-state">표시할 확정 후보가 없습니다.</p>
      <div
        v-for="(point, index) in points"
        :key="point.candidateId"
        :class="['timeline-item', index === activePointIndex && 'following']"
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
        <button type="button" class="ghost-button" @click="router.push(`/admin/candidates/${point.candidateId}`)">
          후보 근거 보기
        </button>
      </div>
    </aside>
  </section>
</template>
