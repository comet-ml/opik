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
import { resolveProjectIdFromConfig } from "@/lib/dashboard/utils";

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
  const overrideDefaults = widget?.config?.overrideDefaults as
    | boolean
    | undefined;

  const { projectId, infoMessage, interval, intervalStart, intervalEnd } =
    useMemo(() => {
      const { projectId: resolvedProjectId, infoMessage } =
        resolveProjectIdFromConfig(
          widgetProjectId,
          globalConfig.projectId,
          overrideDefaults,
        );

      const { interval, intervalStart, intervalEnd } = calculateIntervalConfig(
        globalConfig.dateRange,
      );

      return {
        projectId: resolvedProjectId,
        infoMessage,
        interval,
        intervalStart,
        intervalEnd,
      };
    }, [
      widgetProjectId,
      globalConfig.projectId,
      globalConfig.dateRange,
      overrideDefaults,
    ]);

  const metricType = widget?.config?.metricType as string | undefined;
  const metricName = metricType as METRIC_NAME_TYPE | undefined;
  const isCostMetric = metricName === METRIC_NAME_TYPE.COST;
  const isDurationMetric =
    metricName === METRIC_NAME_TYPE.TRACE_DURATION ||
    metricName === METRIC_NAME_TYPE.THREAD_DURATION ||
    metricName === METRIC_NAME_TYPE.SPAN_DURATION;
  const isCountMetric =
    metricName === METRIC_NAME_TYPE.TOKEN_USAGE ||
    metricName === METRIC_NAME_TYPE.TRACE_COUNT ||
    metricName === METRIC_NAME_TYPE.THREAD_COUNT ||
    metricName === METRIC_NAME_TYPE.SPAN_COUNT ||
    metricName === METRIC_NAME_TYPE.SPAN_TOKEN_USAGE;

  const feedbackScores = widget?.config?.feedbackScores as string[] | undefined;

  const filterLineCallback = useCallback(
    (lineName: string) => {
      if (
        metricName !== METRIC_NAME_TYPE.FEEDBACK_SCORES &&
        metricName !== METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES &&
        metricName !== METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES
      )
        return true;
      if (!feedbackScores || feedbackScores.length === 0) return true;
      return feedbackScores.includes(lineName);
    },
    [feedbackScores, metricName],
  );

  if (!widget) {
    return null;
  }

  const renderChartContent = () => {
    const chartType =
      (widget.config?.chartType as CHART_TYPE.line | CHART_TYPE.bar) ||
      CHART_TYPE.line;
    const traceFilters = widget.config?.traceFilters as Filter[] | undefined;
    const threadFilters = widget.config?.threadFilters as Filter[] | undefined;
    const spanFilters = widget.config?.spanFilters as Filter[] | undefined;

    if (!projectId) {
      return (
        <DashboardWidget.EmptyState
          title="Project not configured"
          message="This widget needs a project to display data. Select a default project for the dashboard or set a custom one in the widget settings."
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    if (!metricType || !interval) {
      return (
        <DashboardWidget.EmptyState
          title="No metric selected"
          message="Choose a metric to display in this widget"
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    const validTraceFilters = traceFilters?.filter(isFilterValid);
    const validThreadFilters = threadFilters?.filter(isFilterValid);
    const validSpanFilters = spanFilters?.filter(isFilterValid);

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
          spanFilters={validSpanFilters}
          filterLineCallback={filterLineCallback}
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
      {preview ? (
        <DashboardWidget.PreviewHeader infoMessage={infoMessage} />
      ) : (
        <DashboardWidget.Header
          title={widget.title || widget.generatedTitle || ""}
          subtitle={widget.subtitle}
          infoMessage={infoMessage}
          actions={
            <DashboardWidget.ActionsMenu
              sectionId={sectionId!}
              widgetId={widgetId!}
              widgetTitle={widget.title}
            />
          }
          dragHandle={<DashboardWidget.DragHandle />}
        />
      )}
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
