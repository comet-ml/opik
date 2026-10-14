import React from "react";
import { act, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useDashboardStore } from "@/store/DashboardStore";
import useDashboardWidgetQuery from "@/api/dashboards/useDashboardWidgetQuery";
import { DashboardWidget as DashboardWidgetType } from "@/types/dashboard";
import OllieChartWidget from "./OllieChartWidget";

vi.mock("@/api/dashboards/useDashboardWidgetQuery");
vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => false,
}));
vi.mock("@/icons/ollie-owl.svg?react", () => ({ default: () => null }));
vi.mock("@/shared/VegaChart/VegaChart", () => ({
  default: ({ rows }: { rows: unknown[] }) => <div>{rows.length} rows</div>,
}));
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
      EmptyState: ({ title }: { title: string }) => <div>{title}</div>,
    }),
  };
});

const queryMock = vi.mocked(useDashboardWidgetQuery);

const ollieWidget = (id: string): DashboardWidgetType =>
  ({
    id,
    type: "ollie_chart",
    title: "Cost by model",
    config: { spec: { mark: "bar" }, query: { id: "q-1" } },
  }) as unknown as DashboardWidgetType;

const setStore = (previewWidget: DashboardWidgetType) =>
  useDashboardStore.setState({
    sections: [
      {
        id: "s1",
        title: "Section",
        widgets: [ollieWidget("w-1")],
        layout: [],
      },
    ],
    previewWidget,
    runtimeConfig: { dashboardId: "dash-1" } as never,
  });

describe("OllieChartWidget preview", () => {
  beforeEach(() => {
    queryMock.mockReset();
    queryMock.mockReturnValue({
      data: [{ model: "a", cost: 1 }],
      isPending: false,
      isFetching: false,
      error: null,
      refetch: vi.fn(),
      dataUpdatedAt: 1,
    } as never);
  });

  it("runs the saved query when editing a saved widget", () => {
    setStore(ollieWidget("w-1"));
    render(<OllieChartWidget preview />);

    const [params, options] = queryMock.mock.calls.at(-1)!;
    expect(params.widgetId).toBe("w-1");
    expect(options?.enabled).toBe(true);
    expect(screen.getByText("1 rows")).toBeTruthy();
  });

  it("does not query for a widget that is not saved yet", () => {
    setStore(ollieWidget("preview"));
    render(<OllieChartWidget preview />);

    expect(queryMock.mock.calls.at(-1)![1]?.enabled).toBe(false);
    expect(screen.getByText("No data")).toBeTruthy();
  });
});

describe("OllieChartWidget saved widget", () => {
  const renderSaved = (savedWidgetIds: string[] | null, error?: unknown) => {
    setStore(ollieWidget("preview"));
    useDashboardStore.setState({ savedWidgetIds });
    const refetch = vi.fn();
    queryMock.mockReset();
    queryMock.mockReturnValue({
      data: undefined,
      isPending: false,
      isFetching: false,
      error: error ?? null,
      refetch,
      dataUpdatedAt: 0,
    } as never);
    render(<OllieChartWidget sectionId="s1" widgetId="w-1" />);
    return refetch;
  };

  const notFound = () => Object.assign(new Error("not found"), { status: 404 });

  it("runs the query again once a widget whose retries ran out gets saved", () => {
    const refetch = renderSaved(["w-2"], notFound());
    expect(refetch).not.toHaveBeenCalled();

    act(() => {
      useDashboardStore.setState({ savedWidgetIds: ["w-1", "w-2"] });
    });
    expect(refetch).toHaveBeenCalledTimes(1);

    act(() => {
      useDashboardStore.setState({ savedWidgetIds: ["w-1"] });
    });
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it("does not run a saved widget's 404 again", () => {
    const refetch = renderSaved(["w-1"], notFound());

    act(() => {
      useDashboardStore.setState({ savedWidgetIds: ["w-1", "w-3"] });
    });
    expect(refetch).not.toHaveBeenCalled();
  });

  it.each([
    [403, "Data is masked"],
    [404, "Query not found"],
    [501, "Charts unavailable"],
    [500, "Query failed"],
  ])("explains a %s from the query", (status, title) => {
    renderSaved(
      ["w-1"],
      Object.assign(new Error("backend message"), { status }),
    );

    expect(screen.getByText(title)).toBeTruthy();
  });

  it.each([
    ["the server has the widget", ["w-1"], false],
    ["the widget isn't saved yet", ["w-2"], true],
    ["the dashboard isn't loaded", null, true],
  ])("retries a 404 only when %s", (_, savedWidgetIds, retryNotFound) => {
    renderSaved(savedWidgetIds);

    expect(queryMock.mock.calls.at(-1)![1]?.retryNotFound).toBe(retryNotFound);
  });
});
