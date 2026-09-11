import { describe, it, expect } from "vitest";
import {
  GRID_COLUMNS,
  ROW_HEIGHT,
  GRID_MARGIN,
  CONTAINER_PADDING,
  MAX_WIDGET_HEIGHT,
  MIN_WIDGET_WIDTH,
  MIN_WIDGET_HEIGHT,
  getWidgetSizeConfig,
  getColumnHeights,
  findFirstAvailablePosition,
  calculateLayoutForAddingWidget,
  normalizeLayout,
  removeWidgetFromLayout,
  areLayoutsEqual,
} from "./layout";
import {
  DashboardLayout,
  DashboardWidget,
  WIDGET_TYPE,
} from "@/types/dashboard";

describe("constants", () => {
  it("should have correct grid configuration", () => {
    expect(GRID_COLUMNS).toBe(6);
    expect(ROW_HEIGHT).toBe(75);
    expect(GRID_MARGIN).toEqual([16, 16]);
    expect(CONTAINER_PADDING).toEqual([0, 0]);
    expect(MAX_WIDGET_HEIGHT).toBe(12);
    expect(MIN_WIDGET_WIDTH).toBe(1);
    expect(MIN_WIDGET_HEIGHT).toBe(1);
  });
});

describe("getWidgetSizeConfig", () => {
  it("should return correct size for PROJECT_METRICS widget", () => {
    const config = getWidgetSizeConfig(WIDGET_TYPE.PROJECT_METRICS);
    expect(config).toEqual({ w: 2, h: 4, minW: 2, minH: 4 });
  });

  it("should return correct size for PROJECT_STATS_CARD widget", () => {
    const config = getWidgetSizeConfig(WIDGET_TYPE.PROJECT_STATS_CARD);
    expect(config).toEqual({ w: 1, h: 2, minW: 1, minH: 2 });
  });

  it("should return correct size for TEXT_MARKDOWN widget", () => {
    const config = getWidgetSizeConfig(WIDGET_TYPE.TEXT_MARKDOWN);
    expect(config).toEqual({ w: 2, h: 4, minW: 1, minH: 4 });
  });

  it("should return default size for unknown widget type", () => {
    const config = getWidgetSizeConfig("unknown-type");
    expect(config).toEqual({
      w: 2,
      h: 2,
      minW: MIN_WIDGET_WIDTH,
      minH: MIN_WIDGET_HEIGHT,
    });
  });
});

describe("getColumnHeights", () => {
  it("should return all zeros for empty layout", () => {
    const heights = getColumnHeights([]);
    expect(heights).toEqual([0, 0, 0, 0, 0, 0]);
  });

  it("should calculate heights for single widget", () => {
    const layout: DashboardLayout = [{ i: "widget-1", x: 0, y: 0, w: 2, h: 3 }];
    const heights = getColumnHeights(layout);
    expect(heights).toEqual([3, 3, 0, 0, 0, 0]);
  });

  it("should calculate heights for multiple widgets", () => {
    const layout: DashboardLayout = [
      { i: "widget-1", x: 0, y: 0, w: 2, h: 3 },
      { i: "widget-2", x: 2, y: 0, w: 2, h: 5 },
    ];
    const heights = getColumnHeights(layout);
    expect(heights).toEqual([3, 3, 5, 5, 0, 0]);
  });

  it("should handle overlapping widgets correctly", () => {
    const layout: DashboardLayout = [
      { i: "widget-1", x: 0, y: 0, w: 2, h: 3 },
      { i: "widget-2", x: 1, y: 3, w: 2, h: 2 },
    ];
    const heights = getColumnHeights(layout);
    expect(heights).toEqual([3, 5, 5, 0, 0, 0]);
  });

  it("should handle widget spanning all columns", () => {
    const layout: DashboardLayout = [{ i: "widget-1", x: 0, y: 0, w: 6, h: 4 }];
    const heights = getColumnHeights(layout);
    expect(heights).toEqual([4, 4, 4, 4, 4, 4]);
  });

  it("should handle widgets at different vertical positions", () => {
    const layout: DashboardLayout = [
      { i: "widget-1", x: 0, y: 2, w: 2, h: 3 },
      { i: "widget-2", x: 2, y: 0, w: 2, h: 2 },
    ];
    const heights = getColumnHeights(layout);
    expect(heights).toEqual([5, 5, 2, 2, 0, 0]);
  });
});

describe("findFirstAvailablePosition", () => {
  it("should place first widget at origin", () => {
    const columnHeights = [0, 0, 0, 0, 0, 0];
    const position = findFirstAvailablePosition(2, 3, columnHeights);
    expect(position).toEqual({ x: 0, y: 0 });
  });

  it("should place widget in first available spot", () => {
    const columnHeights = [3, 3, 0, 0, 0, 0];
    const position = findFirstAvailablePosition(2, 2, columnHeights);
    expect(position).toEqual({ x: 2, y: 0 });
  });

  it("should place widget below existing widgets when no horizontal space", () => {
    const columnHeights = [3, 3, 5, 5, 4, 4];
    const position = findFirstAvailablePosition(2, 2, columnHeights);
    expect(position).toEqual({ x: 0, y: 3 });
  });

  it("should handle full-width widgets", () => {
    const columnHeights = [2, 2, 3, 3, 2, 2];
    const position = findFirstAvailablePosition(6, 1, columnHeights);
    expect(position).toEqual({ x: 0, y: 3 });
  });

  it("should find optimal position with uneven heights", () => {
    const columnHeights = [5, 5, 2, 2, 8, 8];
    const position = findFirstAvailablePosition(2, 3, columnHeights);
    expect(position).toEqual({ x: 2, y: 2 });
  });

  it("should handle single column widget", () => {
    const columnHeights = [3, 0, 2, 4, 1, 5];
    const position = findFirstAvailablePosition(1, 2, columnHeights);
    expect(position).toEqual({ x: 1, y: 0 });
  });
});

describe("calculateLayoutForAddingWidget", () => {
  const createWidget = (id: string, type: WIDGET_TYPE): DashboardWidget => ({
    id,
    type,
    title: `Widget ${id}`,
    config: {},
  });

  it("should add first widget to empty layout", () => {
    const widget = createWidget("widget-1", WIDGET_TYPE.TEXT_MARKDOWN);
    const layout = calculateLayoutForAddingWidget([], widget);

    expect(layout).toHaveLength(1);
    expect(layout[0]).toMatchObject({
      i: "widget-1",
      x: 0,
      y: 0,
      w: 2,
      h: 4,
    });
  });

  it("should add widget with custom size", () => {
    const widget = createWidget("widget-1", WIDGET_TYPE.TEXT_MARKDOWN);
    const layout = calculateLayoutForAddingWidget([], widget, { w: 3, h: 5 });

    expect(layout[0]).toMatchObject({
      w: 3,
      h: 5,
    });
  });

  it("should place second widget next to first", () => {
    const existingLayout: DashboardLayout = [
      { i: "widget-1", x: 0, y: 0, w: 2, h: 4 },
    ];
    const widget = createWidget("widget-2", WIDGET_TYPE.TEXT_MARKDOWN);
    const layout = calculateLayoutForAddingWidget(existingLayout, widget);

    expect(layout).toHaveLength(2);
    expect(layout[1]).toMatchObject({
      i: "widget-2",
      x: 2,
      y: 0,
    });
  });

  it("should set min and max constraints", () => {
    const widget = createWidget("widget-1", WIDGET_TYPE.PROJECT_METRICS);
    const layout = calculateLayoutForAddingWidget([], widget);

    expect(layout[0]).toMatchObject({
      minW: 2,
      minH: 4,
      maxW: GRID_COLUMNS,
      maxH: MAX_WIDGET_HEIGHT,
    });
  });

  it("should handle PROJECT_STATS_CARD widget size", () => {
    const widget = createWidget("widget-1", WIDGET_TYPE.PROJECT_STATS_CARD);
    const layout = calculateLayoutForAddingWidget([], widget);

    expect(layout[0]).toMatchObject({
      w: 1,
      h: 2,
      minW: 1,
      minH: 2,
    });
  });

  it("should place widget in optimal position with complex layout", () => {
    const existingLayout: DashboardLayout = [
      { i: "widget-1", x: 0, y: 0, w: 3, h: 4 },
      { i: "widget-2", x: 3, y: 0, w: 3, h: 2 },
    ];
    const widget = createWidget("widget-3", WIDGET_TYPE.PROJECT_STATS_CARD);
    const layout = calculateLayoutForAddingWidget(existingLayout, widget);

    expect(layout[2].y).toBe(2);
  });
});

describe("normalizeLayout", () => {
  it("should normalize empty layout", () => {
    const normalized = normalizeLayout([]);
    expect(normalized).toEqual([]);
  });

  it("should clamp widget position to grid bounds", () => {
    const layout: DashboardLayout = [
      { i: "widget-1", x: 7, y: -1, w: 2, h: 3 },
    ];
    const normalized = normalizeLayout(layout);

    expect(normalized[0].x).toBe(4);
    expect(normalized[0].y).toBe(0);
  });

  it("should clamp widget width to grid columns", () => {
    const layout: DashboardLayout = [
      { i: "widget-1", x: 0, y: 0, w: 10, h: 3 },
    ];
    const normalized = normalizeLayout(layout);

    expect(normalized[0].w).toBe(GRID_COLUMNS);
  });

  it("should clamp widget height to max height", () => {
    const layout: DashboardLayout = [
      { i: "widget-1", x: 0, y: 0, w: 2, h: 20 },
    ];
    const normalized = normalizeLayout(layout);

    expect(normalized[0].h).toBe(MAX_WIDGET_HEIGHT);
  });

  it("should enforce minimum widget dimensions", () => {
    const layout: DashboardLayout = [{ i: "widget-1", x: 0, y: 0, w: 0, h: 0 }];
    const normalized = normalizeLayout(layout);

    expect(normalized[0].w).toBe(MIN_WIDGET_WIDTH);
    expect(normalized[0].h).toBe(MIN_WIDGET_HEIGHT);
  });

  it("should apply widget-specific constraints", () => {
    const widgets: DashboardWidget[] = [
      {
        id: "widget-1",
        type: WIDGET_TYPE.PROJECT_METRICS,
        title: "Test",
        config: {},
      },
    ];
    const layout: DashboardLayout = [{ i: "widget-1", x: 0, y: 0, w: 1, h: 2 }];
    const normalized = normalizeLayout(layout, widgets);

    expect(normalized[0].minW).toBe(2);
    expect(normalized[0].minH).toBe(4);
    expect(normalized[0].w).toBe(2);
    expect(normalized[0].h).toBe(4);
  });

  it("should preserve valid layout items", () => {
    const layout: DashboardLayout = [{ i: "widget-1", x: 2, y: 3, w: 2, h: 4 }];
    const normalized = normalizeLayout(layout);

    expect(normalized[0]).toMatchObject({
      i: "widget-1",
      x: 2,
      y: 3,
      w: 2,
      h: 4,
    });
  });
});

describe("removeWidgetFromLayout", () => {
  const layout: DashboardLayout = [
    { i: "widget-1", x: 0, y: 0, w: 2, h: 3 },
    { i: "widget-2", x: 2, y: 0, w: 2, h: 3 },
    { i: "widget-3", x: 4, y: 0, w: 2, h: 3 },
  ];

  it("should remove widget from layout", () => {
    const result = removeWidgetFromLayout(layout, "widget-2");
    expect(result).toHaveLength(2);
    expect(result.find((item) => item.i === "widget-2")).toBeUndefined();
  });

  it("should preserve other widgets", () => {
    const result = removeWidgetFromLayout(layout, "widget-2");
    expect(result.find((item) => item.i === "widget-1")).toBeDefined();
    expect(result.find((item) => item.i === "widget-3")).toBeDefined();
  });

  it("should handle non-existent widget ID", () => {
    const result = removeWidgetFromLayout(layout, "non-existent");
    expect(result).toHaveLength(3);
  });

  it("should handle empty layout", () => {
    const result = removeWidgetFromLayout([], "widget-1");
    expect(result).toEqual([]);
  });

  it("should not mutate original layout", () => {
    const original = [...layout];
    removeWidgetFromLayout(layout, "widget-2");
    expect(layout).toEqual(original);
  });
});

describe("areLayoutsEqual", () => {
  const layout1: DashboardLayout = [
    { i: "widget-1", x: 0, y: 0, w: 2, h: 3 },
    { i: "widget-2", x: 2, y: 0, w: 2, h: 3 },
  ];

  it("should return true for identical layouts", () => {
    const layout2 = [...layout1];
    expect(areLayoutsEqual(layout1, layout2)).toBe(true);
  });

  it("should return true regardless of order", () => {
    const layout2 = [layout1[1], layout1[0]];
    expect(areLayoutsEqual(layout1, layout2)).toBe(true);
  });

  it("should return false for different lengths", () => {
    const layout2 = [layout1[0]];
    expect(areLayoutsEqual(layout1, layout2)).toBe(false);
  });

  it("should return false for different positions", () => {
    const layout2 = [
      { i: "widget-1", x: 1, y: 0, w: 2, h: 3 },
      { i: "widget-2", x: 2, y: 0, w: 2, h: 3 },
    ];
    expect(areLayoutsEqual(layout1, layout2)).toBe(false);
  });

  it("should return false for different sizes", () => {
    const layout2 = [
      { i: "widget-1", x: 0, y: 0, w: 3, h: 3 },
      { i: "widget-2", x: 2, y: 0, w: 2, h: 3 },
    ];
    expect(areLayoutsEqual(layout1, layout2)).toBe(false);
  });

  it("should return true for empty layouts", () => {
    expect(areLayoutsEqual([], [])).toBe(true);
  });

  it("should ignore additional properties", () => {
    const layout2: DashboardLayout = [
      { i: "widget-1", x: 0, y: 0, w: 2, h: 3, minW: 1, minH: 1 },
      { i: "widget-2", x: 2, y: 0, w: 2, h: 3, maxW: 6, maxH: 12 },
    ];
    expect(areLayoutsEqual(layout1, layout2)).toBe(true);
  });
});
