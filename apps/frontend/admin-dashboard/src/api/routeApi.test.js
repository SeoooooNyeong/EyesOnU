import { afterEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "./httpClient";
import { getCaseRoute } from "./routeApi";

const originalAdapter = apiClient.defaults.adapter;

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter;
});

describe("routeApi", () => {
  it("사건 식별자를 인코딩해 확정 동선 응답을 반환한다", async () => {
    const route = { caseId: 17, caseNumber: "EFU-17", missingName: "김민수", points: [] };
    const adapter = vi.fn(async (config) => ({
      config,
      data: { data: route },
      headers: {},
      request: {},
      status: 200,
      statusText: "OK"
    }));
    apiClient.defaults.adapter = adapter;

    await expect(getCaseRoute("case/17")).resolves.toEqual(route);
    expect(adapter.mock.calls[0][0]).toMatchObject({
      method: "get",
      url: "/admin/cases/case%2F17/route"
    });
  });
});
