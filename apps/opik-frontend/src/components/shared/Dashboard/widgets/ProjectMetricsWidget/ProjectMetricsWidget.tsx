import React, { memo, useMemo, useCallback } from "react";
import { useShallow } from "zustand/react/shallow";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { useDashboardStore, selectMixedConfig } from "@/store/DashboardStore";
import { DashboardWidgetComponentProps } from "@/types/dashboard";
import { Filter } from "@/types/filters";
import { isFilterValid } from "@/lib/filters";
import MetricContainerChart from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChartContainer";
import { CHART_TYPE } from "@/constants/chart";
import {
  INTERVAL_DESCRIPTIONS,
  renderDurationTooltipValue,
  durationYTickFormatter,
  renderCostTooltipValue,
  costYTickFormatter,
  tokenYTickFormatter,
} from "@/components/pages/TracesPage/MetricsTab/utils";
import { calculateIntervalConfig } from "@/components/pages-shared/traces/MetricDateRangeSelect/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";

const ProjectMetricsWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const globalConfig = useDashboardStore(
    useShallow((state) => {
      const config = selectMixedConfig(state);
      return {
        projectId: config?.projectIds?.[0],
        dateRange: config?.dateRange ?? DEFAULT_DATE_PRESET,
      };
    }),
  );

  const widget = useDashboardStore(
    useShallow((state) => {
      if (preview) {
        return state.previewWidget;
      }
      if (!sectionId || !widgetId) return null;
      const section = state.sections.find((s) => s.id === sectionId);
      return section?.widgets.find((w) => w.id === widgetId);
    }),
  );

  const onAddEditWidgetCallback = useDashboardStore(
    (state) => state.onAddEditWidgetCallback,
  );

  const handleEdit = useCallback(() => {
    if (sectionId && widgetId) {
      onAddEditWidgetCallback?.({ sectionId, widgetId });
    }
  }, [sectionId, widgetId, onAddEditWidgetCallback]);

  const widgetProjectId = widget?.config?.projectId as string | undefined;

  const { projectId, interval, intervalStart, intervalEnd } = useMemo(() => {
    const finalProjectId = globalConfig.projectId || widgetProjectId;

    const { interval, intervalStart, intervalEnd } = calculateIntervalConfig(
      globalConfig.dateRange,
    );

    return {
      projectId: finalProjectId,
      interval,
      intervalStart,
      intervalEnd,
    };
  }, [widgetProjectId, globalConfig.projectId, globalConfig.dateRange]);

  const metricType = widget?.config?.metricType as string | undefined;
  const metricName = metricType as METRIC_NAME_TYPE | undefined;
  const isCostMetric = metricName === METRIC_NAME_TYPE.COST;
  const isDurationMetric =
    metricName === METRIC_NAME_TYPE.TRACE_DURATION ||
    metricName === METRIC_NAME_TYPE.THREAD_DURATION;
  const isCountMetric =
    metricName === METRIC_NAME_TYPE.TOKEN_USAGE ||
    metricName === METRIC_NAME_TYPE.TRACE_COUNT ||
    metricName === METRIC_NAME_TYPE.THREAD_COUNT;

  if (!widget) {
    return null;
  }

  const renderChartContent = () => {
    const chartType =
      (widget.config?.chartType as CHART_TYPE.line | CHART_TYPE.bar) ||
      CHART_TYPE.line;
    const traceFilters = widget.config?.traceFilters as Filter[] | undefined;
    const threadFilters = widget.config?.threadFilters as Filter[] | undefined;

    if (!projectId) {
      return (
        <DashboardWidget.EmptyState
          title="Project not configured"
          message="This widget requires a project ID. Configure it in the widget settings or set a default project for the dashboard."
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    if (!metricType || !interval) {
      return (
        <DashboardWidget.EmptyState
          title="No metric selected"
          message="Please configure this widget to display a metric"
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    const validTraceFilters = traceFilters?.filter(isFilterValid);
    const validThreadFilters = threadFilters?.filter(isFilterValid);

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
          metricName={metricName!}
          interval={intervalType}
          intervalStart={intervalStart}
          intervalEnd={intervalEnd}
          projectId={projectId!}
          chartType={chartType}
          traceFilters={validTraceFilters}
          threadFilters={validThreadFilters}
          renderValue={
            isCostMetric
              ? renderCostTooltipValue
              : isDurationMetric
                ? renderDurationTooltipValue
                : undefined
          }
          customYTickFormatter={
            isCostMetric
              ? costYTickFormatter
              : isDurationMetric
                ? durationYTickFormatter
                : isCountMetric
                  ? tokenYTickFormatter
                  : undefined
          }
          chartOnly
        />
      </div>
    );
  };

  return (
    <DashboardWidget>
      <DashboardWidget.Header
        title={widget.title}
        subtitle={widget.subtitle}
        preview={preview}
        actions={
          !preview ? (
            <DashboardWidget.ActionsMenu
              sectionId={sectionId!}
              widgetId={widgetId!}
              widgetTitle={widget.title}
            />
          ) : undefined
        }
        dragHandle={!preview ? <DashboardWidget.DragHandle /> : undefined}
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
