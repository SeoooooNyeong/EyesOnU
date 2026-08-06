<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fetchAdminCandidate, reviewAdminCandidate } from "../api/candidateApi";
import {
  candidateSourceLabel,
  candidateSourceTone,
  formatCandidateDate,
  reviewStatusLabel,
  similarityPercent,
  similarityTone
} from "../domain/candidateMapper";

const route = useRoute();
const router = useRouter();
const item = ref(null);
const loading = ref(true);
const error = ref("");
const reviewError = ref("");
const reviewing = ref(false);
const videoRef = ref(null);
const videoLoadError = ref(false);
const clipPlaying = ref(false);
const reviewForm = reactive({ reviewStatus: "CONFIRMED", reviewComment: "" });

const syncReviewForm = (candidate) => {
  reviewForm.reviewStatus = candidate.reviewStatus || "CONFIRMED";
  reviewForm.reviewComment = candidate.reviewComment || "";
};

const similarityToneClass = computed(() => similarityTone(item.value?.bestSimilarity));
const videoUrl = computed(() => item.value?.clipUrl || item.value?.recordingUrl || "");
const hasClip = computed(() => Boolean(item.value?.clipUrl));
const hasRecordingFallback = computed(() => !hasClip.value && Boolean(item.value?.recordingUrl));
const clipWindow = computed(() => {
  const detectedAt = Date.parse(item.value?.lastDetectedAt || "");
  const recordingStart = Date.parse(item.value?.recordingStartTime || "");
  const recordingEnd = Date.parse(item.value?.recordingEndTime || "");
  if (!hasRecordingFallback.value || !Number.isFinite(detectedAt)
      || !Number.isFinite(recordingStart) || !Number.isFinite(recordingEnd)) return null;
  const duration = Math.max(0, (recordingEnd - recordingStart) / 1000);
  const detectedOffset = Math.max(0, Math.min(duration, (detectedAt - recordingStart) / 1000));
  return {
    start: Math.max(0, detectedOffset - 10),
    end: Math.min(duration, detectedOffset + 10),
    duration
  };
});
const clipWindowLabel = computed(() => {
  if (!clipWindow.value) return "탐지 전후 20초 구간";
  return `탐지 전후 구간 · ${Math.round(clipWindow.value.start)}~${Math.round(clipWindow.value.end)}초`;
});
const mapEmbedUrl = computed(() => {
  const latitude = Number(item.value?.cameraLatitude);
  const longitude = Number(item.value?.cameraLongitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return "";
  const padding = 0.0015;
  return `https://www.openstreetmap.org/export/embed.html?bbox=${longitude - padding}%2C${latitude - padding}%2C${longitude + padding}%2C${latitude + padding}&layer=mapnik&marker=${latitude}%2C${longitude}`;
});
const externalMapUrl = computed(() => {
  const latitude = Number(item.value?.cameraLatitude);
  const longitude = Number(item.value?.cameraLongitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return "";
  const name = encodeURIComponent(item.value?.cameraName || item.value?.cameraCode || "CCTV");
  return `https://map.kakao.com/link/map/${name},${latitude},${longitude}`;
});

const resetVideoState = () => {
  clipPlaying.value = false;
  videoLoadError.value = false;
};

const playDetectionWindow = async () => {
  const video = videoRef.value;
  if (!video) return;
  resetVideoState();
  if (hasRecordingFallback.value && clipWindow.value) {
    video.currentTime = clipWindow.value.start;
    clipPlaying.value = true;
  }
  try {
    await video.play();
  } catch {
    clipPlaying.value = false;
  }
};

const handleVideoTimeUpdate = () => {
  if (clipPlaying.value && clipWindow.value && videoRef.value.currentTime >= clipWindow.value.end) {
    videoRef.value.pause();
    clipPlaying.value = false;
  }
};

const handleVideoEnded = () => {
  clipPlaying.value = false;
};

const submitReview = async () => {
  if (!item.value) return;
  reviewing.value = true;
  reviewError.value = "";
  try {
    item.value = await reviewAdminCandidate(route.params.candidateId, {
      ...reviewForm,
      version: item.value.version
    });
    syncReviewForm(item.value);
  } catch (exception) {
    reviewError.value = exception.message || "후보 판정을 저장하지 못했습니다.";
    if (exception.code === "OPTIMISTIC_LOCK_CONFLICT" || exception.status === 409) {
      try {
        item.value = await fetchAdminCandidate(route.params.candidateId);
        syncReviewForm(item.value);
        reviewError.value = "다른 관리자가 변경한 최신 정보를 불러왔습니다. 다시 확인한 뒤 저장해 주세요.";
      } catch {
        reviewError.value = "최신 후보 정보를 불러오지 못했습니다. 페이지를 새로고침해 주세요.";
      }
    }
  } finally {
    reviewing.value = false;
  }
};

onMounted(async () => {
  try {
    item.value = await fetchAdminCandidate(route.params.candidateId);
    syncReviewForm(item.value);
  } catch (exception) {
    error.value = exception.response?.data?.message || "후보 상세를 불러오지 못했습니다.";
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <p v-if="loading" class="empty-state">후보 상세를 불러오는 중입니다.</p>
  <p v-else-if="error" class="error-message">{{ error }}</p>
  <section v-else-if="item" class="candidate-detail evidence-detail-layout">
    <main class="candidate-evidence-main">
      <article class="content-panel candidate-review-panel">
        <div class="candidate-detail-header">
          <div>
            <span class="eyebrow">후보 판정 근거</span>
            <h2>{{ item.caseNumber }}</h2>
            <p class="candidate-subtitle">{{ item.missingName || "이름 미상" }} · {{ item.cameraName || item.cameraCode || "CCTV 미등록" }}</p>
          </div>
          <div class="candidate-header-badges">
            <span :class="['status-badge', candidateSourceTone(item.sourceType)]">출처 · {{ candidateSourceLabel(item.sourceType) }}</span>
            <span :class="['similarity-pill', 'similarity-score', similarityToneClass]">AI 유사도 {{ similarityPercent(item.bestSimilarity) }}%</span>
          </div>
        </div>

        <div class="ai-decision-notice" role="note">
          <strong>판정 전 확인</strong>
          <span>AI 유사도는 신원 확정의 근거가 아닙니다. 기준 사진, 영상 맥락, 탐지 위치와 시각을 함께 확인한 뒤 관제자가 판정해야 합니다.</span>
        </div>

        <div class="review-compare-grid candidate-photo-compare">
          <div>
            <span class="review-label">실종자 기준 사진</span>
            <div class="review-image reference-photo">
              <img v-if="item.missingPhotoUrl" :src="item.missingPhotoUrl" :alt="`${item.missingName || '실종자'} 기준 사진`" />
              <span v-else class="image-placeholder large">등록된 기준 사진 없음</span>
            </div>
          </div>
          <div>
            <span class="review-label">AI 후보 사진</span>
            <div class="review-image capture">
              <img v-if="item.cropUrl" :src="item.cropUrl" alt="AI 후보 크롭 이미지" />
              <span v-else class="image-placeholder large">후보 이미지 없음</span>
            </div>
          </div>
        </div>
        <div class="source-frame-block">
          <div>
            <span class="review-label">탐지 원본 프레임</span>
            <p>후보가 포함된 전체 화면을 함께 확인하세요.</p>
          </div>
          <a v-if="item.frameUrl" :href="item.frameUrl" target="_blank" rel="noopener noreferrer" class="ghost-button">원본 프레임 크게 보기</a>
          <div class="review-image source-frame-image">
            <img v-if="item.frameUrl" :src="item.frameUrl" alt="탐지 원본 프레임" />
            <span v-else class="image-placeholder large">원본 프레임 없음</span>
          </div>
        </div>
      </article>

      <article class="content-panel candidate-video-panel">
        <div class="section-heading">
          <div>
            <h2>탐지 전후 영상 클립</h2>
            <p v-if="hasClip">저장된 전후 숏 클립을 인라인으로 재생합니다.</p>
            <p v-else-if="hasRecordingFallback">숏 클립이 없으므로 원본 녹화본에서 탐지 시각 전후 구간을 재생합니다.</p>
            <p v-else>이 후보에 연결된 영상이 아직 준비되지 않았습니다.</p>
          </div>
          <span v-if="item.clipStatus" class="status-badge">클립 {{ item.clipStatus }}</span>
        </div>
        <div v-if="videoUrl" class="candidate-video-wrap">
          <video
            ref="videoRef"
            :src="videoUrl"
            controls
            preload="metadata"
            playsinline
            @loadedmetadata="resetVideoState"
            @timeupdate="handleVideoTimeUpdate"
            @ended="handleVideoEnded"
            @error="videoLoadError = true"
          />
          <div class="candidate-video-actions">
            <button type="button" class="primary-button" @click="playDetectionWindow">{{ hasClip ? "클립 재생" : "탐지 전후 구간 재생" }}</button>
            <span>{{ hasClip ? "탐지 전후 숏 클립" : clipWindowLabel }}</span>
          </div>
          <p v-if="videoLoadError" class="error-message">영상 URL을 재생할 수 없습니다. 원본 녹화 상태를 확인해 주세요.</p>
        </div>
        <div v-else class="state-view candidate-media-empty">
          <strong>재생 가능한 영상이 없습니다.</strong>
          <p v-if="item.clipStatus && item.clipStatus !== 'READY'">클립 상태: {{ item.clipStatus }} · 생성이 완료되면 이 화면에서 재생할 수 있습니다.</p>
          <p v-else>실시간 후보는 저장된 녹화 영상이 없을 수 있습니다. 탐지 프레임과 위치 정보를 기준으로 검토해 주세요.</p>
        </div>
      </article>

      <article class="content-panel candidate-map-panel">
        <div class="section-heading">
          <div>
            <h2>CCTV 위치</h2>
            <p>{{ item.cameraName || item.cameraCode || "카메라 미등록" }} · {{ item.cameraAddress || "설치 주소 미등록" }}</p>
          </div>
          <a v-if="externalMapUrl" :href="externalMapUrl" target="_blank" rel="noopener noreferrer" class="ghost-button">카카오맵에서 열기</a>
        </div>
        <div v-if="mapEmbedUrl" class="candidate-map-frame">
          <iframe :src="mapEmbedUrl" title="탐지 CCTV 위치 지도" loading="lazy" />
        </div>
        <div v-else class="state-view">
          <strong>CCTV 좌표가 등록되지 않았습니다.</strong>
          <p>주소: {{ item.cameraAddress || "—" }}</p>
        </div>
        <div class="camera-coordinate-row">
          <span><small>위도</small><strong>{{ item.cameraLatitude ?? "—" }}</strong></span>
          <span><small>경도</small><strong>{{ item.cameraLongitude ?? "—" }}</strong></span>
          <span><small>탐지 시각</small><strong>{{ formatCandidateDate(item.lastDetectedAt) }}</strong></span>
        </div>
      </article>
    </main>

    <aside class="candidate-side-stack">
      <article class="content-panel candidate-info-panel">
        <h2>후보 정보</h2>
        <div class="candidate-info-list">
          <span><small>탐지 출처</small><strong>{{ candidateSourceLabel(item.sourceType) }}</strong></span>
          <span><small>카메라</small><strong>{{ item.cameraCode || "—" }}</strong></span>
          <span><small>Track ID</small><strong>{{ item.trackId || "—" }}</strong></span>
          <span><small>최초 탐지</small><strong>{{ formatCandidateDate(item.firstDetectedAt) }}</strong></span>
          <span><small>최근 탐지</small><strong>{{ formatCandidateDate(item.lastDetectedAt) }}</strong></span>
          <span><small>탐지 횟수</small><strong>{{ item.detectionCount ?? "—" }}</strong></span>
          <span><small>평균 유사도</small><strong>{{ similarityPercent(item.averageSimilarity) }}%</strong></span>
          <span><small>판정 상태</small><strong>{{ reviewStatusLabel(item.reviewStatus) }}</strong></span>
        </div>
      </article>

      <article class="content-panel">
        <div class="section-heading compact-heading"><div><h2>탐지 이력</h2><p>동일 후보로 묶인 이벤트입니다.</p></div></div>
        <div v-if="item.detections?.length" class="table-scroll">
          <table class="case-table">
            <thead><tr><th>시각</th><th>이벤트</th><th>유사도</th><th>크롭</th></tr></thead>
            <tbody>
              <tr v-for="detection in item.detections" :key="detection.eventId + detection.detectedAt">
                <td>{{ formatCandidateDate(detection.detectedAt) }}</td>
                <td>{{ detection.eventId }}</td>
                <td>{{ similarityPercent(detection.similarity) }}%</td>
                <td><a v-if="detection.cropUrl" :href="detection.cropUrl" target="_blank" rel="noopener noreferrer">이미지 보기</a><span v-else>—</span></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">탐지 이력이 없습니다.</p>
      </article>

      <form class="content-panel candidate-review-form" @submit.prevent="submitReview">
        <h2>관제자 판정</h2>
        <p class="review-form-help">판정 결과와 근거를 남겨야 다음 담당자가 같은 근거를 확인할 수 있습니다.</p>
        <label>판정 상태
          <select v-model="reviewForm.reviewStatus">
            <option value="KEPT">후보 유지</option>
            <option value="CONFIRMED">대상 확인</option>
            <option value="REJECTED">대상 아님</option>
          </select>
        </label>
        <label>판정 근거
          <textarea v-model="reviewForm.reviewComment" maxlength="2000" rows="5" placeholder="사진·영상·위치·시각을 확인한 근거를 입력하세요." />
        </label>
        <p v-if="reviewError" class="error-message">{{ reviewError }}</p>
        <button class="primary-button" type="submit" :disabled="reviewing">{{ reviewing ? "저장 중..." : "판정 저장" }}</button>
      </form>

      <button class="ghost-button candidate-back-button" type="button" @click="router.push('/admin/candidates')">목록으로</button>
    </aside>
  </section>
</template>

<style scoped>
.evidence-detail-layout { align-items: start; }
.candidate-evidence-main { min-width: 0; display: grid; gap: 16px; }
.candidate-detail-header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
.candidate-detail-header h2 { margin: 4px 0 0; }
.candidate-subtitle { margin: 5px 0 0; color: #64748b; font-size: 13px; }
.candidate-header-badges { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.candidate-photo-compare { margin-top: 18px; }
.reference-photo { background: #f8fafc; }
.review-image img, .candidate-video-wrap video { width: 100%; height: 100%; object-fit: contain; display: block; }
.source-frame-block { display: grid; grid-template-columns: 1fr auto; gap: 10px 14px; align-items: center; margin-top: 4px; }
.source-frame-block p { margin: 3px 0 0; color: #64748b; font-size: 12px; }
.source-frame-image { grid-column: 1 / -1; min-height: 160px; aspect-ratio: 16 / 7; }
.ai-decision-notice { display: grid; gap: 4px; margin-top: 16px; padding: 12px 14px; border: 1px solid #e5c87a; border-radius: 8px; background: #fffbeb; color: #76571b; font-size: 12px; line-height: 1.5; }
.ai-decision-notice strong { color: #5f4615; font-size: 13px; }
.candidate-video-panel, .candidate-map-panel { min-width: 0; }
.candidate-video-wrap { display: grid; gap: 10px; }
.candidate-video-wrap video { width: 100%; height: auto; max-height: 420px; aspect-ratio: 16 / 9; border-radius: 8px; background: #17202d; }
.candidate-video-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; color: #64748b; font-size: 12px; }
.candidate-video-actions .primary-button { min-height: 34px; }
.candidate-media-empty { min-height: 180px; }
.candidate-map-frame { overflow: hidden; height: 330px; border: 1px solid #dfe6ec; border-radius: 8px; background: #f8fafc; }
.candidate-map-frame iframe { width: 100%; height: 100%; border: 0; }
.camera-coordinate-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.camera-coordinate-row span { display: grid; gap: 4px; padding: 9px 10px; border: 1px solid #edf0f4; border-radius: 7px; background: #f8fafc; }
.camera-coordinate-row small, .candidate-info-list small { color: #7b8794; font-size: 11px; }
.camera-coordinate-row strong { color: #253342; font-size: 12px; overflow-wrap: anywhere; }
.compact-heading { margin-bottom: 10px; }
.candidate-info-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.candidate-info-list span { display: grid; gap: 4px; min-width: 0; }
.candidate-info-list strong { color: #253342; font-size: 13px; overflow-wrap: anywhere; }
.review-form-help { margin: -6px 0 4px; color: #64748b; font-size: 12px; line-height: 1.5; }
.candidate-review-form { display: grid; gap: 12px; }
.candidate-review-form label { display: grid; gap: 6px; color: #64748b; font-size: 12px; font-weight: 700; }
.candidate-review-form textarea { min-height: 110px; resize: vertical; }
.candidate-back-button { width: 100%; }

@media (max-width: 860px) {
  .candidate-detail-header, .source-frame-block { display: grid; grid-template-columns: 1fr; }
  .candidate-header-badges { justify-content: flex-start; }
  .source-frame-image { grid-column: auto; }
  .camera-coordinate-row, .candidate-info-list { grid-template-columns: 1fr; }
}
</style>
