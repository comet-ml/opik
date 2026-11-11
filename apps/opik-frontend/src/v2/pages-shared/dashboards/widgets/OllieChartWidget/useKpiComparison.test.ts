import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import useDashboardWidgetQuery from "@/api/dashboards/useDashboardWidgetQuery";
import { DASHBOARD_SCOPE } from "@/types/dashboard";
import useKpiComparison from "./useKpiComparison";

vi.mock("@/api/dashboards/useDashboardWidgetQuery");

const DAY = 24 * 60 * 60_000;
const fetchedAt = Date.now();

// Refresh on a KPI: whether the comparison query runs again.
const refreshRunsComparison = ({
  intervalEnd,
  error,
}: {
  intervalEnd?: string;
  error: Error | null;
}) => {
  const refetch = vi.fn();
  vi.mocked(useDashboardWidgetQuery).mockReturnValue({
    data: error ? undefined : [{ value: 1 }],
    error,
    isFetching: false,
    refetch,
  } as never);
  const { result } = renderHook(() =>
    useKpiComparison({
      savedQuery: {
        dashboardId: "dash-1",
        scope: DASHBOARD_SCOPE.WORKSPACE,
        widgetId: "w-1",
        queryId: "q-1",
      },
      enabled: true,
      retryNotFound: false,
      intervalStart: new Date(fetchedAt - 30 * DAY).toISOString(),
      intervalEnd,
      value: { dataUpdatedAt: fetchedAt, isFetching: false },
    }),
  );
  result.current.refetch();
  return refetch.mock.calls.length > 0;
};

describe("useKpiComparison refresh", () => {
  it("leaves an open range's comparison to the next window", () => {
    expect(refreshRunsComparison({ error: null })).toBe(false);
  });

  it("runs a failed comparison on an open range again", () => {
    expect(refreshRunsComparison({ error: new Error("failed") })).toBe(true);
  });

  it("runs a closed range's comparison again", () => {
    expect(
      refreshRunsComparison({
        intervalEnd: new Date(fetchedAt - DAY).toISOString(),
        error: null,
      }),
    ).toBe(true);
  });
});
