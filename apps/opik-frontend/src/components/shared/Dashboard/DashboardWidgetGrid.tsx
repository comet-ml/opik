import React, { useCallback, memo } from "react";
import GridLayout, { Layout, WidthProvider } from "react-grid-layout";
import "react-grid-layout/css/styles.css";
import "react-resizable/css/styles.css";

import { DashboardLayout } from "@/types/dashboard";
import {
  GRID_COLUMNS,
  ROW_HEIGHT,
  GRID_MARGIN,
  normalizeLayout,
} from "@/lib/dashboard/layout";
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
  widgetIds: string[];
  layout: DashboardLayout;
}

const DashboardWidgetGrid: React.FunctionComponent<
  DashboardWidgetGridProps
> = ({ sectionId, widgetIds, layout }) => {
  const onAddWidgetCallback = useDashboardStore(
    (state) => state.onAddWidgetCallback,
  );
  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const sections = useDashboardStore((state) => state.sections);
  const updateLayout = useDashboardStore(selectUpdateLayout);

  const getWidget = (widgetId: string) => {
    const section = sections.find((s) => s.id === sectionId);
    return section?.widgets.find((w) => w.id === widgetId);
  };

  const handleAddWidget = () => {
    onAddWidgetCallback?.(sectionId);
  };
  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      const updatedLayout = newLayout.map((item) => ({
        i: item.i,
        x: item.x,
        y: item.y,
        w: item.w,
        h: item.h,
        minW: item.minW,
        maxW: item.maxW,
        minH: item.minH,
        maxH: item.maxH,
        static: item.static,
        moved: item.moved,
      }));

      const normalizedLayout = normalizeLayout(updatedLayout);
      updateLayout(sectionId, normalizedLayout);
    },
    [sectionId, updateLayout],
  );

  if (isEmpty(widgetIds)) {
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
      {widgetIds.map((widgetId) => {
        const widget = getWidget(widgetId);
        const WidgetComponent = widgetResolver?.(widget?.type || "chart");

        if (!WidgetComponent) return null;

        return (
          <div key={widgetId}>
            <WidgetComponent sectionId={sectionId} widgetId={widgetId} />
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
  if (prev.widgetIds.length !== next.widgetIds.length) return false;
  if (prev.widgetIds.some((id, i) => id !== next.widgetIds[i])) return false;
  if (prev.layout.length !== next.layout.length) return false;

  return prev.sectionId === next.sectionId;
};

export default memo(DashboardWidgetGrid, arePropsEqual);
