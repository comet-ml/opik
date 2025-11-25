import React, { memo } from "react";
import { useShallow } from "zustand/react/shallow";

import DashboardWidget from "@/components/shared/DashboardWidget";
import {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { useDashboardStore } from "@/store/DashboardStore";
import { ProjectDashboardConfig } from "@/types/dashboard";
import MetricContainerChart from "../TracesPage/MetricsTab/MetricChart/MetricChartContainer";
import { INTERVAL_DESCRIPTIONS } from "../TracesPage/MetricsTab/utils";

interface ProjectMetricsWidgetProps {
  sectionId: string;
  widgetId: string;
}

const ProjectMetricsWidget: React.FunctionComponent<
  ProjectMetricsWidgetProps
> = ({ sectionId, widgetId }) => {
  const { projectId, interval, intervalStart, intervalEnd } = useDashboardStore(
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

  const widget = useDashboardStore((state) => {
    const section = state.sections.find((s) => s.id === sectionId);
    return section?.widgets.find((w) => w.id === widgetId);
  });

  if (!widget) {
    return null;
  }

  const renderChartContent = () => {
    if (!widget?.metricType || !projectId || !interval) {
      return (
        <DashboardWidget.EmptyState
          title="No metric selected"
          message="Please configure this widget to display a metric"
        />
      );
    }

    const metricName = widget.metricType as METRIC_NAME_TYPE;
    const intervalType = interval as INTERVAL_TYPE;
    const description = interval
      ? INTERVAL_DESCRIPTIONS.TOTALS[intervalType] || ""
      : "";

    return (
      <div style={{ "--chart-height": "100%" } as React.CSSProperties}>
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
          chartType="line"
        />
      </div>
    );
  };

  return (
    <DashboardWidget>
      <DashboardWidget.Header
        title={widget.title}
        subtitle={widget.subtitle}
        actions={
          <DashboardWidget.Actions>
            <DashboardWidget.DeleteAction
              sectionId={sectionId}
              widgetId={widgetId}
              widgetTitle={widget.title}
            />
            <DashboardWidget.EditAction />
            <DashboardWidget.DuplicateAction
              sectionId={sectionId}
              widgetType={widget.type}
              widgetTitle={widget.title}
              widgetMetricType={widget.metricType}
            />
            <DashboardWidget.MoveAction
              sectionId={sectionId}
              widgetId={widgetId}
            />
            <div className="h-4 w-px bg-border" />
            <DashboardWidget.DragHandle />
          </DashboardWidget.Actions>
        }
      />
      <DashboardWidget.Content>{renderChartContent()}</DashboardWidget.Content>
    </DashboardWidget>
  );
};

const arePropsEqual = (
  prev: ProjectMetricsWidgetProps,
  next: ProjectMetricsWidgetProps,
) => {
  return prev.sectionId === next.sectionId && prev.widgetId === next.widgetId;
};

export default memo(ProjectMetricsWidget, arePropsEqual);
