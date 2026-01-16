import {
  DashboardLayout,
  DashboardLayoutItem,
  DashboardWidget,
  WIDGET_TYPE,
  WidgetSize,
  WidgetSizeConfig,
} from "@/types/dashboard";
import isEmpty from "lodash/isEmpty";

export const GRID_COLUMNS = 6;
export const ROW_HEIGHT = 75;
export const GRID_MARGIN: [number, number] = [16, 16];
export const CONTAINER_PADDING: [number, number] = [0, 0];
export const MAX_WIDGET_HEIGHT = 12;
export const MIN_WIDGET_WIDTH = 1;
export const MIN_WIDGET_HEIGHT = 1;

export const getWidgetSizeConfig = (type: string): WidgetSizeConfig => {
  switch (type) {
    case WIDGET_TYPE.PROJECT_METRICS:
      return { w: 2, h: 4, minW: 2, minH: 4 };
    case WIDGET_TYPE.PROJECT_STATS_CARD:
      return { w: 1, h: 2, minW: 1, minH: 2 };
    case WIDGET_TYPE.TEXT_MARKDOWN:
      return { w: 2, h: 4, minW: 1, minH: 4 };
    case WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES:
      return { w: 2, h: 4, minW: 2, minH: 4 };
    case WIDGET_TYPE.EXPERIMENT_LEADERBOARD:
      return { w: 6, h: 6, minW: 4, minH: 4 };
    default:
      return { w: 2, h: 2, minW: MIN_WIDGET_WIDTH, minH: MIN_WIDGET_HEIGHT };
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
  size?: WidgetSize,
): DashboardLayout => {
  const sizeConfig = getWidgetSizeConfig(widget.type);
  const { w, h } = size || { w: sizeConfig.w, h: sizeConfig.h };
  const { minW, minH } = sizeConfig;

  const newItem: DashboardLayoutItem = {
    i: widget.id,
    x: 0,
    y: 0,
    w,
    h,
    minW,
    minH,
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

export const normalizeLayout = (
  layout: DashboardLayout,
  widgets?: DashboardWidget[],
): DashboardLayout => {
  return layout.map((item) => {
    const widget = widgets?.find((w) => w.id === item.i);
    const { minW, minH } = widget
      ? getWidgetSizeConfig(widget.type)
      : { minW: MIN_WIDGET_WIDTH, minH: MIN_WIDGET_HEIGHT };

    return {
      i: item.i,
      x: Math.max(0, Math.min(item.x, GRID_COLUMNS - item.w)),
      y: Math.max(0, item.y),
      w: Math.max(minW, Math.min(item.w, GRID_COLUMNS)),
      h: Math.max(minH, Math.min(item.h, MAX_WIDGET_HEIGHT)),
      minW,
      minH,
      maxW: GRID_COLUMNS,
      maxH: MAX_WIDGET_HEIGHT,
    };
  });
};

export const removeWidgetFromLayout = (
  layout: DashboardLayout,
  widgetId: string,
): DashboardLayout => {
  return layout.filter((item) => item.i !== widgetId);
};

export const areLayoutsEqual = (
  prev: DashboardLayout,
  next: DashboardLayout,
): boolean => {
  if (prev.length !== next.length) return false;

  const sortedPrev = [...prev].sort((a, b) => a.i.localeCompare(b.i));
  const sortedNext = [...next].sort((a, b) => a.i.localeCompare(b.i));

  return sortedPrev.every((prevItem, i) => {
    const nextItem = sortedNext[i];
    return (
      prevItem.i === nextItem.i &&
      prevItem.x === nextItem.x &&
      prevItem.y === nextItem.y &&
      prevItem.w === nextItem.w &&
      prevItem.h === nextItem.h
    );
  });
};

export const getLayoutItemSize = (
  layout: DashboardLayout,
  widgetId: string,
): { w: number; h: number } | undefined => {
  const item = layout.find((item) => item.i === widgetId);
  return item ? { w: item.w, h: item.h } : undefined;
};
