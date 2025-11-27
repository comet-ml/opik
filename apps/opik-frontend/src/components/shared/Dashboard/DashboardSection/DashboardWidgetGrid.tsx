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
  const onAddWidgetCallback = useDashboardStore(
    (state) => state.onAddWidgetCallback,
  );
  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const updateLayout = useDashboardStore(selectUpdateLayout);

  const handleAddWidget = () => {
    onAddWidgetCallback?.(sectionId);
  };

  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      if (newLayout.length === 0) return;
      const normalizedLayout = normalizeLayout(newLayout);
      updateLayout(sectionId, normalizedLayout);
    },
    [sectionId, updateLayout],
  );

  if (isEmpty(widgets)) {
    return <DashboardWidgetGridEmpty onAddWidget={handleAddWidget} />;
  }

  return (
    <ResponsiveGridLayout
      // eslint-disable-next-line tailwindcss/no-custom-classname
      className="layout"
      layout={layout}
      cols={GRID_COLUMNS}
      rowHeight={ROW_HEIGHT}
      margin={GRID_MARGIN}
      containerPadding={[0, 0]}
      onLayoutChange={handleLayoutChange}
      draggableHandle=".drag-handle"
      useCSSTransforms
      preventCollision={false}
      compactType="vertical"
      isBounded={true}
      maxRows={Infinity}
      autoSize={true}
    >
      {widgets.map((widget) => {
        const widgetComponents = widgetResolver?.(
          widget.type || WIDGET_TYPE.CHART_METRIC,
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
