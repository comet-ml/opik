import {
  DashboardLayout,
  DashboardLayoutItem,
  DashboardWidget,
} from "@/types/dashboard";
import isEmpty from "lodash/isEmpty";

export const GRID_COLUMNS = 6;
export const ROW_HEIGHT = 155;
export const GRID_MARGIN: [number, number] = [16, 16];
export const MAX_WIDGET_HEIGHT = 6;
export const MIN_WIDGET_WIDTH = 1;
export const MIN_WIDGET_HEIGHT = 1;

export const getDefaultWidgetSize = (
  type: string,
): { w: number; h: number } => {
  switch (type) {
    case "chart":
      return { w: 2, h: 2 };
    case "metric":
      return { w: 2, h: 1 };
    case "table":
      return { w: 6, h: 3 };
    case "text":
      return { w: 2, h: 1 };
    default:
      return { w: 2, h: 2 };
  }
};

export const getColumnHeights = (layout: DashboardLayout): number[] => {
  const heights = new Array(GRID_COLUMNS).fill(0);

  layout.forEach((item) => {
    const startCol = item.x;
    const endCol = Math.min(item.x + item.w, GRID_COLUMNS);
    const itemBottom = item.y + item.h;

    for (let col = startCol; col < endCol; col++) {
      heights[col] = Math.max(heights[col], itemBottom);
    }
  });

  return heights;
};

export const findFirstAvailablePosition = (
  w: number,
  h: number,
  columnHeights: number[],
): { x: number; y: number } => {
  let minHeight = Infinity;
  let bestX = 0;

  for (let x = 0; x <= GRID_COLUMNS - w; x++) {
    const maxHeightInRange = Math.max(...columnHeights.slice(x, x + w));

    if (maxHeightInRange < minHeight) {
      minHeight = maxHeightInRange;
      bestX = x;
    }
  }

  return { x: bestX, y: minHeight };
};

export const calculateLayoutForAddingWidget = (
  layout: DashboardLayout,
  widget: DashboardWidget,
): DashboardLayout => {
  const { w, h } = getDefaultWidgetSize(widget.type);

  const newItem: DashboardLayoutItem = {
    i: widget.id,
    x: 0,
    y: 0,
    w,
    h,
    minW: MIN_WIDGET_WIDTH,
    minH: MIN_WIDGET_HEIGHT,
    maxW: GRID_COLUMNS,
    maxH: MAX_WIDGET_HEIGHT,
  };

  if (isEmpty(layout)) {
    return [newItem];
  }

  const columnHeights = getColumnHeights(layout);
  const { x, y } = findFirstAvailablePosition(w, h, columnHeights);

  newItem.x = x;
  newItem.y = y;

  return [...layout, newItem];
};

export const normalizeLayout = (layout: DashboardLayout): DashboardLayout => {
  return layout.map((item) => ({
    ...item,
    x: Math.max(0, Math.min(item.x, GRID_COLUMNS - item.w)),
    y: Math.max(0, item.y),
    w: Math.max(MIN_WIDGET_WIDTH, Math.min(item.w, GRID_COLUMNS)),
    h: Math.max(MIN_WIDGET_HEIGHT, Math.min(item.h, MAX_WIDGET_HEIGHT)),
    minW: MIN_WIDGET_WIDTH,
    minH: MIN_WIDGET_HEIGHT,
    maxW: GRID_COLUMNS,
    maxH: MAX_WIDGET_HEIGHT,
  }));
};

export const removeWidgetFromLayout = (
  layout: DashboardLayout,
  widgetId: string,
): DashboardLayout => {
  return layout.filter((item) => item.i !== widgetId);
};
