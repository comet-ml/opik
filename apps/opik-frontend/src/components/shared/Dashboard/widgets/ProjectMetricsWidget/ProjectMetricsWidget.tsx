import React, { memo, useMemo, useCallback } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useShallow } from "zustand/react/shallow";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { useDashboardStore, selectMixedConfig } from "@/store/DashboardStore";
import {
  DashboardWidgetComponentProps,
  BreakdownConfig,
} from "@/types/dashboard";
import { Filter } from "@/types/filters";
import { isFilterValid, createFilter } from "@/lib/filters";
import MetricContainerChart from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChartContainer";
import { LOGS_TYPE, PROJECT_TAB } from "@/constants/traces";
import useAppStore from "@/store/AppStore";
import { CHART_TYPE } from "@/constants/chart";
import {
  INTERVAL_DESCRIPTIONS,
  renderDurationTooltipValue,
  durationYTickFormatter,
  renderCostTooltipValue,
  costYTickFormatter,
  tokenYTickFormatter,
} from "@/components/pages/TracesPage/MetricsTab/utils";
import { renderScoreTooltipValue } from "@/lib/feedback-scores";
import { calculateIntervalConfig } from "@/components/pages-shared/traces/MetricDateRangeSelect/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import { resolveProjectIdFromConfig } from "@/lib/dashboard/utils";
import {
  BREAKDOWN_FIELD,
  BREAKDOWN_GROUP_NAMES,
  buildBreakdownDrilldownFilter,
  getMetricEntityType,
} from "./breakdown";

const ProjectMetricsWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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
  const durationMetrics = widget?.config?.durationMetrics as
    | string[]
    | undefined;
  const usageMetrics = widget?.config?.usageMetrics as string[] | undefined;
  const breakdown = widget?.config?.breakdown as BreakdownConfig | undefined;

  // Check if this is a feedback score metric
  const isFeedbackScoreMetric =
    metricName === METRIC_NAME_TYPE.FEEDBACK_SCORES ||
    metricName === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES ||
    metricName === METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES;

  // Check if this is a token usage metric
  const isTokenUsageMetric =
    metricName === METRIC_NAME_TYPE.TOKEN_USAGE ||
    metricName === METRIC_NAME_TYPE.SPAN_TOKEN_USAGE;

  // Only pass breakdown if it's enabled (field is not NONE)
  // Also skip if METADATA is selected but no metadataKey is provided
  // For feedback score metrics, we need exactly one metric selected to add it as subMetric
  // For duration metrics, we need exactly one percentile selected to add it as subMetric
  const effectiveBreakdown = useMemo(() => {
    if (!breakdown || breakdown.field === BREAKDOWN_FIELD.NONE) {
      return undefined;
    }

    // Check if METADATA field without metadataKey
    if (
      breakdown.field === BREAKDOWN_FIELD.METADATA &&
      (!breakdown.metadataKey || breakdown.metadataKey.trim() === "")
    ) {
      return undefined;
    }

    // For feedback score metrics, include the selected metric name as subMetric
    if (
      isFeedbackScoreMetric &&
      feedbackScores &&
      feedbackScores.length === 1
    ) {
      return {
        ...breakdown,
        subMetric: feedbackScores[0],
      };
    }

    // For feedback score metrics without exactly one metric selected, don't apply breakdown
    if (isFeedbackScoreMetric) {
      return undefined;
    }

    // For duration metrics, include the selected percentile as subMetric
    if (isDurationMetric && durationMetrics && durationMetrics.length === 1) {
      return {
        ...breakdown,
        subMetric: durationMetrics[0],
      };
    }

    // For duration metrics without exactly one percentile selected, don't apply breakdown
    if (isDurationMetric) {
      return undefined;
    }

    // For token usage metrics, include the selected usage key as subMetric
    if (isTokenUsageMetric && usageMetrics && usageMetrics.length === 1) {
      return {
        ...breakdown,
        subMetric: usageMetrics[0],
      };
    }

    // For token usage metrics without exactly one metric selected, don't apply breakdown
    if (isTokenUsageMetric) {
      return undefined;
    }

    return breakdown;
  }, [
    breakdown,
    isFeedbackScoreMetric,
    feedbackScores,
    isDurationMetric,
    durationMetrics,
    isTokenUsageMetric,
    usageMetrics,
  ]);

  const filterLineCallback = useCallback(
    (lineName: string) => {
      // When breakdown is applied, the line names are group names (e.g., tag values),
      // not feedback score names, duration percentile names, or usage key names, so we shouldn't filter them
      if (effectiveBreakdown) {
        return true;
      }

      // Filter for feedback score metrics
      if (isFeedbackScoreMetric) {
        if (!feedbackScores || feedbackScores.length === 0) return true;
        return feedbackScores.includes(lineName);
      }

      // Filter for duration metrics (p50, p90, p99)
      if (isDurationMetric) {
        if (!durationMetrics || durationMetrics.length === 0) return true;
        // Line names are like "duration.p50", "duration.p90", etc.
        // We need to check if the percentile part matches
        const percentile = lineName.split(".").pop();
        return percentile ? durationMetrics.includes(percentile) : true;
      }

      // Filter for token usage metrics (completion_tokens, prompt_tokens, total_tokens)
      if (isTokenUsageMetric) {
        if (!usageMetrics || usageMetrics.length === 0) return true;
        return usageMetrics.includes(lineName);
      }

      return true;
    },
    [
      feedbackScores,
      durationMetrics,
      usageMetrics,
      isFeedbackScoreMetric,
      isDurationMetric,
      isTokenUsageMetric,
      effectiveBreakdown,
    ],
  );

  const getLabelAction = useCallback(
    (label: string) => {
      if (!projectId || !effectiveBreakdown || !metricName) return undefined;

      if (
        label === BREAKDOWN_GROUP_NAMES.OTHERS_DISPLAY ||
        label === BREAKDOWN_GROUP_NAMES.UNKNOWN
      ) {
        return undefined;
      }

      const entityType = getMetricEntityType(metricName);

      const entityConfig = {
        span: {
          logsType: LOGS_TYPE.spans,
          filtersKey: "spans_filters",
          tooltip: "View filtered spans",
          widgetFilters: widget?.config?.spanFilters as Filter[] | undefined,
        },
        thread: {
          logsType: LOGS_TYPE.threads,
          filtersKey: "threads_filters",
          tooltip: "View filtered threads",
          widgetFilters: widget?.config?.threadFilters as Filter[] | undefined,
        },
        trace: {
          logsType: LOGS_TYPE.traces,
          filtersKey: "traces_filters",
          tooltip: "View filtered traces",
          widgetFilters: widget?.config?.traceFilters as Filter[] | undefined,
        },
      }[entityType];

      const { logsType, filtersKey, tooltip: tooltipText } = entityConfig;
      const widgetFilters =
        entityConfig.widgetFilters?.filter(isFilterValid) ?? [];

      const drilldownFilter = buildBreakdownDrilldownFilter(
        effectiveBreakdown.field,
        label,
        effectiveBreakdown.metadataKey,
      );
      if (!drilldownFilter) return undefined;

      const filter = createFilter(drilldownFilter);

      return {
        onClick: () => {
          navigate({
            to: "/$workspaceName/projects/$projectId/traces",
            params: {
              projectId,
              workspaceName,
            },
            search: {
              tab: PROJECT_TAB.logs,
              logsType,
              [filtersKey]: [...widgetFilters, filter],
              time_range: globalConfig.dateRange,
            },
          });
        },
        tooltip: tooltipText,
      };
    },
    [
      projectId,
      effectiveBreakdown,
      metricName,
      navigate,
      workspaceName,
      widget?.config?.spanFilters,
      widget?.config?.traceFilters,
      widget?.config?.threadFilters,
      globalConfig.dateRange,
    ],
  );

  if (!widget) {
    return null;
  }

  const isAggregateTotal = effectiveBreakdown && breakdown?.aggregateTotal;

  const effectiveInterval = isAggregateTotal ? INTERVAL_TYPE.TOTAL : interval;

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

    if (!metricType || !effectiveInterval) {
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

    const intervalType = effectiveInterval as INTERVAL_TYPE;
    const description = effectiveInterval
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
          isAggregateTotal={!!isAggregateTotal}
          intervalStart={intervalStart}
          intervalEnd={intervalEnd}
          projectId={projectId!}
          chartType={chartType}
          traceFilters={validTraceFilters}
          threadFilters={validThreadFilters}
          spanFilters={validSpanFilters}
          filterLineCallback={filterLineCallback}
          breakdown={effectiveBreakdown}
          getLabelAction={effectiveBreakdown ? getLabelAction : undefined}
          renderValue={
            isCostMetric
              ? renderCostTooltipValue
              : isDurationMetric
                ? renderDurationTooltipValue
                : isFeedbackScoreMetric
                  ? renderScoreTooltipValue
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
