import React, { memo, useMemo } from "react";
import { useShallow } from "zustand/react/shallow";
import { get, isObject } from "lodash";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget";
import { useDashboardStore, selectPreviewWidget } from "@/store/DashboardStore";
import {
  ProjectDashboardConfig,
  DashboardWidgetComponentProps,
} from "@/types/dashboard";
import { Filter } from "@/types/filters";
import { isFilterValid } from "@/lib/filters";
import { calculateIntervalConfig } from "@/components/pages-shared/traces/MetricDateRangeSelect/utils";
import { DateRangeSerializedValue } from "@/components/shared/DateRangeSelect";
import useTracesStatistic from "@/api/traces/useTracesStatistic";
import useSpansStatistic from "@/api/traces/useSpansStatistic";
import { ColumnStatistic, STATISTIC_AGGREGATION_TYPE } from "@/types/shared";
import { Skeleton } from "@/components/ui/skeleton";
import { formatNumericData } from "@/lib/utils";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";

const getStatisticFormatter = (
  statName: string,
  metric: string,
): ((value: number) => string) => {
  const lowerStatName = statName.toLowerCase();
  const lowerMetric = metric.toLowerCase();

  if (lowerStatName.includes("duration") || lowerMetric.includes("duration")) {
    return formatDuration;
  }

  if (
    lowerStatName.includes("cost") ||
    lowerMetric.includes("cost") ||
    lowerStatName.includes("estimated_cost")
  ) {
    return (value: number) => formatCost(value);
  }

  return formatNumericData;
};

const formatMetricValue = (
  value: number | string | object,
  statType: STATISTIC_AGGREGATION_TYPE,
  metric: string,
  statName: string,
): string => {
  if (statType === STATISTIC_AGGREGATION_TYPE.PERCENTAGE) {
    const percentageValue = value as {
      p50?: number;
      p90?: number;
      p99?: number;
    };
    const formatter = getStatisticFormatter(statName, metric);
    if (metric.includes("p50")) return formatter(percentageValue.p50 || 0);
    if (metric.includes("p90")) return formatter(percentageValue.p90 || 0);
    if (metric.includes("p99")) return formatter(percentageValue.p99 || 0);
    return formatter(percentageValue.p50 || 0);
  }

  if (statType === STATISTIC_AGGREGATION_TYPE.AVG) {
    const numValue = Number(value);
    const formatter = getStatisticFormatter(statName, metric);
    return formatter(numValue);
  }

  if (statType === STATISTIC_AGGREGATION_TYPE.COUNT) {
    const numValue = Number(value);
    return numValue.toLocaleString();
  }

  return String(value);
};

const getMetricLabel = (
  statName: string,
  statType: STATISTIC_AGGREGATION_TYPE,
  metric: string,
): string => {
  const nameLabel = statName
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");

  if (statType === STATISTIC_AGGREGATION_TYPE.PERCENTAGE) {
    const percentile = metric.includes("p50")
      ? "P50"
      : metric.includes("p90")
        ? "P90"
        : metric.includes("p99")
          ? "P99"
          : "";
    return percentile ? `${percentile} ${nameLabel}` : nameLabel;
  }

  if (statType === STATISTIC_AGGREGATION_TYPE.AVG) {
    return `Average ${nameLabel}`;
  }

  if (statType === STATISTIC_AGGREGATION_TYPE.COUNT) {
    return `Total ${nameLabel}`;
  }

  return nameLabel;
};

const StatCardWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const globalConfig = useDashboardStore(
    useShallow((state) => {
      const config = state.config as ProjectDashboardConfig | null;
      return {
        projectId: config?.projectId,
        dateRange: config?.dateRange as DateRangeSerializedValue,
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

  const { projectId, intervalStart, intervalEnd } = useMemo(() => {
    const finalProjectId = widgetProjectId || globalConfig.projectId;

    const { intervalStart, intervalEnd } = calculateIntervalConfig(
      globalConfig.dateRange,
    );

    return {
      projectId: finalProjectId,
      intervalStart,
      intervalEnd,
    };
  }, [widgetProjectId, globalConfig.projectId, globalConfig.dateRange]);

  const source = widget?.config?.source as "traces" | "spans" | undefined;
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
    { enabled: source === "traces" && !!projectId },
  );

  const spansStatistic = useSpansStatistic(
    {
      projectId: projectId!,
      filters: validSpanFilters,
      fromTime: intervalStart,
      toTime: intervalEnd,
    },
    { enabled: source === "spans" && !!projectId },
  );

  const { data, isLoading, error } = useMemo(
    () => (source === "traces" ? tracesStatistic : spansStatistic),
    [source, tracesStatistic, spansStatistic],
  );

  if (!widget) {
    return null;
  }

  const renderCardContent = () => {
    if (!source || !metric || !projectId) {
      return (
        <DashboardWidget.EmptyState
          title="No metric selected"
          message="Please configure this widget to display a metric"
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

    let statItem: ColumnStatistic | undefined;
    let selectedValue: number | string | object | undefined;
    let selectedType: STATISTIC_AGGREGATION_TYPE | undefined;

    statItem = stats.find((s) => s.name === metric);

    if (!statItem && metric.includes(".")) {
      const parts = metric.split(".");
      const baseName = parts[0];
      const subPath = parts.slice(1).join(".");

      statItem = stats.find((s) => s.name === baseName);

      if (statItem) {
        selectedType = statItem.type;

        if (statItem.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE) {
          const percentageValue = statItem.value as {
            p50?: number;
            p90?: number;
            p99?: number;
          };
          if (subPath.includes("p50")) {
            selectedValue = { p50: percentageValue.p50 };
          } else if (subPath.includes("p90")) {
            selectedValue = { p90: percentageValue.p90 };
          } else if (subPath.includes("p99")) {
            selectedValue = { p99: percentageValue.p99 };
          } else {
            selectedValue = percentageValue;
          }
        } else {
          const statValue = statItem.value;
          if (isObject(statValue) && statValue !== null) {
            const nestedValue = get(statValue, subPath);
            selectedValue = nestedValue !== undefined ? nestedValue : statValue;
          } else {
            selectedValue = statValue;
          }
        }
      }
    } else if (statItem) {
      selectedValue = statItem.value as number | string | object;
      selectedType = statItem.type;
    }

    if (
      !statItem ||
      selectedValue === undefined ||
      selectedType === undefined
    ) {
      return (
        <DashboardWidget.EmptyState
          title="Metric not found"
          message={`Could not find metric: ${metric}`}
        />
      );
    }

    const formattedValue = formatMetricValue(
      selectedValue,
      selectedType,
      metric,
      statItem.name,
    );
    const label = getMetricLabel(statItem.name, selectedType, metric);

    return (
      <div className="flex h-full flex-col items-stretch justify-center">
        <div className="comet-body truncate text-center text-muted-foreground">
          {label}
        </div>
        <div className="comet-title-xl mt-2 truncate text-center">
          {formattedValue}
        </div>
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

export default memo(StatCardWidget, arePropsEqual);
