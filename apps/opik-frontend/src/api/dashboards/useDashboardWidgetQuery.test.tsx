import React from "react";
import { describe, it, expect, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AxiosError, AxiosResponse } from "axios";

const post = vi.fn();

vi.mock("@/api/api", () => ({
  default: { post: (...args: unknown[]) => post(...args) },
  DASHBOARDS_REST_ENDPOINT: "/v1/private/dashboards/",
  INSIGHTS_VIEWS_REST_ENDPOINT: "/v1/private/insights-views/",
}));

import useDashboardWidgetQuery from "./useDashboardWidgetQuery";
import { DASHBOARD_SCOPE } from "@/types/dashboard";

const httpError = (status: number) =>
  new AxiosError("failed", String(status), undefined, undefined, {
    status,
    data: { errors: ["Too many queries are running; retry shortly"] },
  } as AxiosResponse);

const renderWidgetQuery = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(
    () =>
      useDashboardWidgetQuery(
        {
          dashboardId: "d1",
          scope: DASHBOARD_SCOPE.WORKSPACE,
          widgetId: "w1",
          sql: "SELECT 1",
        },
        { retryDelay: 0 },
      ),
    { wrapper },
  );
};

describe("useDashboardWidgetQuery", () => {
  it("retries a 429 from the concurrent-query cap", async () => {
    post
      .mockRejectedValueOnce(httpError(429))
      .mockResolvedValueOnce({ data: { results: [{ n: 1 }] } });

    const { result } = renderWidgetQuery();

    await waitFor(() => expect(result.current.data).toEqual([{ n: 1 }]));
    expect(post).toHaveBeenCalledTimes(2);
  });

  it("does not retry a 400", async () => {
    post.mockReset().mockRejectedValue(httpError(400));

    const { result } = renderWidgetQuery();

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(post).toHaveBeenCalledTimes(1);
  });
});
