import React, { useCallback, memo } from "react";
import GridLayout, { Layout, WidthProvider } from "react-grid-layout";
import "react-grid-layout/css/styles.css";
import "react-resizable/css/styles.css";

import {
  DashboardLayout,
  DashboardWidget,
  WIDGET_TYPE,
} from "@/types/dashboard";
import {
  GRID_COLUMNS,
  ROW_HEIGHT,
  GRID_MARGIN,
  CONTAINER_PADDING,
  normalizeLayout,
  areLayoutsEqual,
} from "@/lib/dashboard/layout";
import { areWidgetArraysEqual } from "@/lib/dashboard/utils";
import {
  useDashboardStore,
  selectUpdateLayout,
  selectWidgetResolver,
} from "@/store/DashboardStore";
import DashboardWidgetGridEmpty from "./DashboardWidgetGridEmpty";
import isEmpty from "lodash/isEmpty";

const ResponsiveGridLayout = WidthProvider(GridLayout);

interface DashboardWidgetGridProps {
  sectionId: string;
  widgets: DashboardWidget[];
  layout: DashboardLayout;
}

const DashboardWidgetGrid: React.FunctionComponent<
  DashboardWidgetGridProps
> = ({ sectionId, widgets, layout }) => {
  const onAddEditWidgetCallback = useDashboardStore(
    (state) => state.onAddEditWidgetCallback,
  );
  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const updateLayout = useDashboardStore(selectUpdateLayout);

  const handleAddWidget = () => {
    onAddEditWidgetCallback?.({ sectionId });
  };

  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      if (newLayout.length === 0) return;
      const normalizedLayout = normalizeLayout(newLayout, widgets);
      updateLayout(sectionId, normalizedLayout);
    },
    [sectionId, updateLayout, widgets],
  );

  if (isEmpty(widgets)) {
    return <DashboardWidgetGridEmpty onAddWidget={handleAddWidget} />;
  }

  return (
    <ResponsiveGridLayout
      className="layout"
      layout={layout}
      cols={GRID_COLUMNS}
      rowHeight={ROW_HEIGHT}
      margin={GRID_MARGIN}
      containerPadding={CONTAINER_PADDING}
      onLayoutChange={handleLayoutChange}
      draggableHandle=".comet-drag-handle"
      useCSSTransforms
      preventCollision={false}
      compactType="vertical"
      isBounded={true}
      maxRows={Infinity}
      autoSize={true}
    >
      {widgets.map((widget) => {
        const widgetComponents = widgetResolver?.(
          widget.type || WIDGET_TYPE.PROJECT_METRICS,
        );

        if (!widgetComponents) return null;

        const { Widget } = widgetComponents;

        return (
          <div key={widget.id}>
            <Widget sectionId={sectionId} widgetId={widget.id} />
          </div>
        );
      })}
    </ResponsiveGridLayout>
  );
};

const arePropsEqual = (
  prev: DashboardWidgetGridProps,
  next: DashboardWidgetGridProps,
) => {
  return (
    prev.sectionId === next.sectionId &&
    areWidgetArraysEqual(prev.widgets, next.widgets) &&
    areLayoutsEqual(prev.layout, next.layout)
  );
};

export default memo(DashboardWidgetGrid, arePropsEqual);
