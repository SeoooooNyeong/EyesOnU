import { apiClient, unwrapData } from "./httpClient";

export async function getCaseRoute(caseId) {
  const encodedCaseId = encodeURIComponent(caseId);
  return unwrapData(await apiClient.get(`/admin/cases/${encodedCaseId}/route`));
}
