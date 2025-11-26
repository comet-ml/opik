import React, { memo, useMemo } from "react";
import { useShallow } from "zustand/react/shallow";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget";
import {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { useDashboardStore, selectPreviewWidget } from "@/store/DashboardStore";
import {
  ProjectDashboardConfig,
  DashboardWidgetComponentProps,
} from "@/types/dashboard";
import { Filter } from "@/types/filters";
import MetricContainerChart from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChartContainer";
import { INTERVAL_DESCRIPTIONS } from "@/components/pages/TracesPage/MetricsTab/utils";

const ProjectMetricsWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const globalConfig = useDashboardStore(
    useShallow((state) => {
      const config = state.config as ProjectDashboardConfig | null;
      return {
        projectId: config?.projectId,
        interval: config?.interval,
        intervalStart: config?.intervalStart,
        intervalEnd: config?.intervalEnd,
      };
    }),
  );

  const storeWidget = useDashboardStore((state) => {
    if (preview || !sectionId || !widgetId) return null;
    const section = state.sections.find((s) => s.id === sectionId);
    return section?.widgets.find((w) => w.id === widgetId);
  });

  const previewWidget = useDashboardStore(selectPreviewWidget);
  const widget = preview ? previewWidget : storeWidget;

  const widgetProjectId = widget?.config?.projectId as string | undefined;

  const { projectId, interval, intervalStart, intervalEnd } = useMemo(() => {
    const finalProjectId = widgetProjectId || globalConfig.projectId;

    return {
      projectId: finalProjectId,
      interval: globalConfig.interval,
      intervalStart: globalConfig.intervalStart,
      intervalEnd: globalConfig.intervalEnd,
    };
  }, [
    widgetProjectId,
    globalConfig.projectId,
    globalConfig.interval,
    globalConfig.intervalStart,
    globalConfig.intervalEnd,
  ]);

  if (!widget) {
    return null;
  }

  const renderChartContent = () => {
    const metricType = widget?.config?.metricType as string | undefined;
    const chartType = (widget?.config?.chartType as "line" | "bar") || "line";
    const traceFilters = widget?.config?.traceFilters as Filter[] | undefined;
    const threadFilters = widget?.config?.threadFilters as Filter[] | undefined;

    if (!metricType || !projectId || !interval) {
      return (
        <DashboardWidget.EmptyState
          title="No metric selected"
          message="Please configure this widget to display a metric"
        />
      );
    }

    const metricName = metricType as METRIC_NAME_TYPE;
    const intervalType = interval as INTERVAL_TYPE;
    const description = interval
      ? INTERVAL_DESCRIPTIONS.TOTALS[intervalType] || ""
      : "";

    return (
      <div
        className="h-full"
        style={{ "--chart-height": "100%" } as React.CSSProperties}
      >
        <MetricContainerChart
          chartId={`${widgetId}_chart`}
          key={`${widgetId}_chart`}
          name={widget.title}
          description={description}
          metricName={metricName}
          interval={intervalType}
          intervalStart={intervalStart}
          intervalEnd={intervalEnd}
          projectId={projectId!}
          chartType={chartType}
          traceFilters={traceFilters}
          threadFilters={threadFilters}
          chartOnly
        />
      </div>
    );
  };

  if (preview) {
    return (
      <DashboardWidget.PreviewContent>
        {renderChartContent()}
      </DashboardWidget.PreviewContent>
    );
  }

  return (
    <DashboardWidget>
      <DashboardWidget.Header
        title={widget.title}
        subtitle={widget.subtitle}
        actions={
          <DashboardWidget.ActionsMenu
            sectionId={sectionId!}
            widgetId={widgetId!}
            widgetType={widget.type}
            widgetTitle={widget.title}
            widgetConfig={widget.config}
          />
        }
        dragHandle={<DashboardWidget.DragHandle />}
      />
      <DashboardWidget.Content>{renderChartContent()}</DashboardWidget.Content>
    </DashboardWidget>
  );
};

const arePropsEqual = (
  prev: DashboardWidgetComponentProps,
  next: DashboardWidgetComponentProps,
) => {
  if (prev.preview !== next.preview) return false;
  if (prev.preview && next.preview) return true;
  return prev.sectionId === next.sectionId && prev.widgetId === next.widgetId;
};

export default memo(ProjectMetricsWidget, arePropsEqual);
