import { apiClient, unwrapData, unwrapPagedData } from "./httpClient";

export async function listAdminRecordings(params = {}) {
  return unwrapPagedData(await apiClient.get("/admin/recordings", { params }));
}

const manualRecordingPath = (cameraCode, suffix) =>
  `/admin/cameras/${encodeURIComponent(cameraCode)}/${suffix}`;

const idempotencyHeaders = (idempotencyKey) => ({
  headers: { "Idempotency-Key": idempotencyKey }
});

export async function createManualRecordingUploadUrl(cameraCode, payload, idempotencyKey) {
  return unwrapData(await apiClient.post(
    manualRecordingPath(cameraCode, "recording-upload-urls"),
    payload,
    idempotencyHeaders(idempotencyKey)
  ));
}

export async function uploadManualRecordingFile(uploadUrl, file, contentType = "video/mp4", options = {}) {
  const response = await fetch(uploadUrl, {
    method: "PUT",
    body: file,
    credentials: "omit",
    headers: { "Content-Type": contentType },
    signal: options.signal
  });
  if (!response.ok) {
    const error = new Error("녹화 파일을 저장소에 업로드하지 못했습니다.");
    error.status = response.status;
    throw error;
  }
}

export async function completeManualRecordingUpload(cameraCode, payload, idempotencyKey) {
  return unwrapData(await apiClient.post(
    manualRecordingPath(cameraCode, "recordings"),
    payload,
    idempotencyHeaders(idempotencyKey)
  ));
}

const analysisJobPath = (caseId, suffix = "") =>
  `/admin/cases/${encodeURIComponent(caseId)}/recording-analysis-jobs${suffix}`;

export async function createRecordingAnalysisJob(caseId, payload) {
  return unwrapData(await apiClient.post(analysisJobPath(caseId), payload));
}

export async function listRecordingAnalysisJobs(caseId) {
  return unwrapData(await apiClient.get(analysisJobPath(caseId)));
}

export async function listRecordingAnalysisJobsForDashboard(caseIds) {
  return unwrapData(await apiClient.get("/admin/recording-analysis-jobs", {
    params: { caseIds: caseIds.join(",") }
  }));
}

export async function fetchRecordingAnalysisJob(caseId, jobId) {
  return unwrapData(await apiClient.get(analysisJobPath(caseId, `/${encodeURIComponent(jobId)}`)));
}

export async function cancelRecordingAnalysisJob(caseId, jobId) {
  return unwrapData(await apiClient.post(analysisJobPath(caseId, `/${encodeURIComponent(jobId)}/cancel`)));
}

export async function retryRecordingAnalysisJob(caseId, jobId) {
  return unwrapData(await apiClient.post(analysisJobPath(caseId, `/${encodeURIComponent(jobId)}/retry`)));
}
