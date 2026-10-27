import React from "react";
import { act, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useDashboardStore } from "@/store/DashboardStore";
import useDashboardWidgetQuery from "@/api/dashboards/useDashboardWidgetQuery";
import { DashboardWidget as DashboardWidgetType } from "@/types/dashboard";
import OllieChartWidget from "./OllieChartWidget";

type Row = Record<string, unknown>;
// What OllieKpi rendered with, and the value's fetch time at that render.
const renders: { previousRows?: Row[] | null; fetchedAt: number }[] = [];
let fetchedAt = 0;

vi.mock("./OllieKpi", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  default: ({ previousRows }: { previousRows?: Row[] | null }) => {
    renders.push({ previousRows, fetchedAt });
    return null;
  },
}));
vi.mock("@/api/dashboards/useDashboardWidgetQuery");
vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => false,
}));
vi.mock("@/icons/ollie-owl.svg?react", () => ({ default: () => null }));
vi.mock("@/shared/Dashboard/DashboardWidget/DashboardWidget", () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => (
    <>{children}</>
  );
  return {
    default: Object.assign(Pass, {
      PreviewHeader: () => null,
      Header: () => null,
      DragHandle: () => null,
      Content: Pass,
      EmptyState: () => null,
    }),
  };
});

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

describe("OllieChartWidget KPI comparison after a refetch", () => {
  it("never pairs the refetched value with the window built for the previous fetch", () => {
    const widget = {
      id: "w-1",
      type: "ollie_chart",
      title: "Total cost",
      config: {
        spec: {
          kpi: {
            value: "value",
            format: "cost_total",
            aggregate: "sum",
            window_days: 30,
          },
        },
        query: { id: "q-1" },
      },
    } as unknown as DashboardWidgetType;
    useDashboardStore.setState({
      sections: [{ id: "s1", title: "Section", widgets: [widget], layout: [] }],
      previewWidget: widget,
      runtimeConfig: { dashboardId: "dash-1" } as never,
    });
    // The comparison query ends where the dashboard's range starts; its rows record the fetch time it was built for.
    vi.mocked(useDashboardWidgetQuery).mockImplementation(
      (params) =>
        ({
          data: params.intervalEnd
            ? [
                {
                  value: 1,
                  builtFor:
                    2 * new Date(params.intervalEnd).getTime() -
                    new Date(params.intervalStart!).getTime(),
                },
              ]
            : [{ value: 5 }],
          isPending: false,
          isFetching: false,
          error: null,
          refetch: vi.fn(),
          dataUpdatedAt: params.intervalEnd ? 0 : fetchedAt,
        }) as never,
    );

    fetchedAt = Date.now();
    render(<OllieChartWidget preview />);
    // A refetch three hours later; the widget is memoised, so the new result reaches it through a re-render the
    // store triggers, as a query update would.
    fetchedAt += 3 * HOUR;
    act(() =>
      useDashboardStore.setState({
        previewWidget: { ...widget, title: "Total cost, refreshed" },
      }),
    );

    const compared = renders.filter((r) => r.previousRows);
    expect(compared.length).toBeGreaterThan(0);
    for (const r of compared) {
      expect(r.previousRows![0].builtFor).toBe(
        Math.floor(r.fetchedAt / MINUTE) * MINUTE,
      );
    }
  });
});
