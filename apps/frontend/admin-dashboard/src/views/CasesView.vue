<script setup>
import { onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { listCases } from "../api/caseApi";
import BasePagination from "../components/common/BasePagination.vue";
import StateBlock from "../components/common/StateBlock.vue";
import StatusBadge from "../components/common/StatusBadge.vue";
import { mapCaseListItem } from "../domain/caseMapper";

const router = useRouter();
const rows = ref([]);
const loading = ref(true);
const error = ref("");
const page = ref(1);
const totalPages = ref(1);
const totalCount = ref(0);
const failedPhotoUrls = reactive(new Map());

let latestRequestId = 0;

const listParams = () => ({
  page: page.value - 1,
  size: 10,
  sort: "reportedAt,desc"
});

const errorMessage = (cause, fallback) => cause?.message || fallback;

const shouldShowPhoto = (item) => (
  typeof item.photoUrl === "string"
  && item.photoUrl.trim().length > 0
  && failedPhotoUrls.get(item.id) !== item.photoUrl
);

const handlePhotoError = (item) => {
  failedPhotoUrls.set(item.id, item.photoUrl);
};

const load = async () => {
  const requestId = ++latestRequestId;
  loading.value = true;
  error.value = "";

  try {
    const result = await listCases(listParams());
    if (requestId !== latestRequestId) return;

    failedPhotoUrls.clear();
    rows.value = (result.data || []).map(mapCaseListItem);
    totalPages.value = Math.max(1, result.meta?.totalPages || 0);
    totalCount.value = result.meta?.totalElements || 0;
  } catch (cause) {
    if (requestId !== latestRequestId) return;

    rows.value = [];
    totalPages.value = 1;
    totalCount.value = 0;
    error.value = errorMessage(cause, "사건 목록을 불러오지 못했습니다.");
  } finally {
    if (requestId === latestRequestId) loading.value = false;
  }
};

watch(page, load);
onMounted(load);
</script>

<template>
  <section class="content-panel">
    <div class="section-heading"><div><h2>사건 관리</h2><p>최신 사건 조회, 페이지 이동, 상세 이동을 지원합니다.</p></div><button class="primary-button" @click="router.push('/admin/cases/new')">신규 사건 등록</button></div>
    <StateBlock :loading="loading" :error="error" :empty="rows.length === 0" @retry="load">
      <div class="table-scroll">
        <table class="case-table">
          <thead><tr><th>사건 번호</th><th>사진</th><th>이름</th><th>성별</th><th>나이</th><th>신고 시각</th><th>목격 위치</th><th>상태</th><th></th></tr></thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td class="mono">{{ item.caseNumber }}</td><td><span class="thumb"><img v-if="shouldShowPhoto(item)" :src="item.photoUrl" :alt="`${item.name || '실종자'} 기준 사진`" @error="handlePhotoError(item)" /><span v-else>사진 없음</span></span></td><td>{{ item.name }}</td><td>{{ item.gender }}</td><td>{{ item.age }}</td><td>{{ item.reportedAt }}</td><td>{{ item.lastSeenLocation }}</td><td><span v-if="item.status === 'field_search'" class="status-badge green">현장 탐색</span><StatusBadge v-else :status="item.status" /></td><td><button class="ghost-button" @click="router.push(`/admin/cases/${item.id}`)">상세보기</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <BasePagination v-model:page="page" :total-pages="totalPages" :total-count="totalCount" />
    </StateBlock>
  </section>
</template>
