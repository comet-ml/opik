import React, { memo, useMemo, useCallback } from "react";
import { useShallow } from "zustand/react/shallow";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useDashboardStore, selectMixedConfig } from "@/store/DashboardStore";
import { DashboardWidgetComponentProps } from "@/types/dashboard";
import { Filter } from "@/types/filters";
import { isFilterValid } from "@/lib/filters";
import { calculateIntervalConfig } from "@/components/pages-shared/traces/MetricDateRangeSelect/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import useTracesStatistic from "@/api/traces/useTracesStatistic";
import useSpansStatistic from "@/api/traces/useSpansStatistic";
import { ColumnStatistic } from "@/types/shared";
import { Skeleton } from "@/components/ui/skeleton";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import {
  getMetricDefinition,
  formatMetricValue,
  formatMetricTooltipValue,
  isFeedbackScoreMetric,
  extractFeedbackScoreName,
} from "./metrics";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import { resolveProjectIdFromConfig } from "@/lib/dashboard/utils";

const renderMetricDisplay = (
  label: string,
  value: string,
  tooltipValue?: string,
) => (
  <div className="flex h-full flex-col items-stretch justify-center">
    <TooltipWrapper content={label}>
      <div className="comet-body truncate text-center text-muted-foreground">
        {label}
      </div>
    </TooltipWrapper>
    <TooltipWrapper content={tooltipValue ?? value}>
      <div className="comet-title-xl mt-2 truncate text-center">{value}</div>
    </TooltipWrapper>
  </div>
);

const ProjectStatsCardWidget: React.FunctionComponent<
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

  const { projectId, infoMessage, intervalStart, intervalEnd } = useMemo(() => {
    const { projectId: resolvedProjectId, infoMessage } =
      resolveProjectIdFromConfig(
        widgetProjectId,
        globalConfig.projectId,
        overrideDefaults,
      );

    const { intervalStart, intervalEnd } = calculateIntervalConfig(
      globalConfig.dateRange,
    );

    return {
      projectId: resolvedProjectId,
      infoMessage,
      intervalStart,
      intervalEnd,
    };
  }, [
    widgetProjectId,
    globalConfig.projectId,
    globalConfig.dateRange,
    overrideDefaults,
  ]);

  const source = widget?.config?.source as TRACE_DATA_TYPE | undefined;
  const metric = widget?.config?.metric as string | undefined;
  const traceFilters = widget?.config?.traceFilters as Filter[] | undefined;
  const spanFilters = widget?.config?.spanFilters as Filter[] | undefined;

  const validTraceFilters = useMemo(
    () => traceFilters?.filter(isFilterValid),
    [traceFilters],
  );
  const validSpanFilters = useMemo(
    () => spanFilters?.filter(isFilterValid),
    [spanFilters],
  );

  const tracesStatistic = useTracesStatistic(
    {
      projectId: projectId!,
      filters: validTraceFilters,
      fromTime: intervalStart,
      toTime: intervalEnd,
    },
    { enabled: source === TRACE_DATA_TYPE.traces && !!projectId },
  );

  const spansStatistic = useSpansStatistic(
    {
      projectId: projectId!,
      filters: validSpanFilters,
      fromTime: intervalStart,
      toTime: intervalEnd,
    },
    { enabled: source === TRACE_DATA_TYPE.spans && !!projectId },
  );

  const { data, isLoading, error } = useMemo(
    () =>
      source === TRACE_DATA_TYPE.traces ? tracesStatistic : spansStatistic,
    [source, tracesStatistic, spansStatistic],
  );

  if (!widget) {
    return null;
  }

  const renderCardContent = () => {
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

    if (!source || !metric) {
      return (
        <DashboardWidget.EmptyState
          title="No metric selected"
          message="Choose a metric to display in this widget"
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    if (isLoading) {
      return (
        <div className="flex h-full items-center justify-center p-6">
          <div className="w-full space-y-3">
            <Skeleton className="h-8 w-32" />
            <Skeleton className="h-16 w-full" />
          </div>
        </div>
      );
    }

    if (error) {
      return (
        <DashboardWidget.EmptyState
          title="Error loading data"
          message={error.message || "Failed to load metric data"}
        />
      );
    }

    if (!data?.stats) {
      return (
        <DashboardWidget.EmptyState
          title="No data available"
          message="No statistics available for this metric"
        />
      );
    }

    const stats = data.stats as ColumnStatistic[];

    if (isFeedbackScoreMetric(metric)) {
      const scoreName = extractFeedbackScoreName(metric);
      const feedbackScoreStat = stats.find((s) => s.name === metric);
      const scoreValue = feedbackScoreStat?.value as number | undefined;

      return renderMetricDisplay(
        `Average ${scoreName}`,
        scoreValue !== undefined ? formatScoreDisplay(scoreValue) : "-",
      );
    }

    const metricDef = getMetricDefinition(metric, source);

    if (!metricDef) {
      return (
        <DashboardWidget.EmptyState
          title="Invalid metric"
          message={`Unknown metric: ${metric}`}
        />
      );
    }

    const statItem = stats.find((s) => s.name === metricDef.statName);
    const selectedValue = statItem?.value as
      | number
      | string
      | object
      | undefined;

    return renderMetricDisplay(
      metricDef.label,
      selectedValue !== undefined
        ? formatMetricValue(selectedValue, metricDef)
        : "-",
      selectedValue !== undefined
        ? formatMetricTooltipValue(selectedValue, metricDef)
        : undefined,
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
      <DashboardWidget.Content>{renderCardContent()}</DashboardWidget.Content>
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

export default memo(ProjectStatsCardWidget, arePropsEqual);
