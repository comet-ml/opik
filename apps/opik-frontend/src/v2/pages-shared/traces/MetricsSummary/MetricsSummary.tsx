import React, { useMemo, useState, useCallback } from "react";
import dayjs from "dayjs";
import { Braces, AlertTriangle, Clock, Coins, LucideIcon } from "lucide-react";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";

import { cn } from "@/lib/utils";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import MetricCard from "./MetricCard";
import useProjectKpiCards, {
  KpiEntityType,
  KpiMetric,
  KpiMetricType,
} from "@/api/projects/useProjectKpiCards";
import { Filters } from "@/types/filters";
import { LOGS_SOURCE } from "@/types/traces";
import { PercentageTrendType } from "@/shared/PercentageTrend/PercentageTrend";
import MetricContainerChart from "@/v2/pages-shared/dashboards/widgets/ProjectMetricsWidget/MetricChart/MetricChartContainer";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { CHART_TYPE } from "@/constants/chart";
import {
  durationYTickFormatter,
  renderDurationTooltipValue,
  costYTickFormatter,
  renderCostTooltipValue,
} from "@/v2/pages-shared/dashboards/widgets/ProjectMetricsWidget/chartUtils";
import { ChartTooltipRenderValueArguments } from "@/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import {
  calculateIntervalType,
  calculateIntervalStartAndEnd,
} from "@/v2/pages-shared/traces/MetricDateRangeSelect/utils";
import { DateRangeValue } from "@/shared/DateRangeSelect";

type MetricCardDef = {
  type: KpiMetricType;
  icon: LucideIcon;
  label: string;
  formatter: (value: number) => string;
  trend: PercentageTrendType;
};

const METRIC_CARDS: MetricCardDef[] = [
  {
    type: "count",
    icon: Braces,
    label: "",
    formatter: (v) => v.toLocaleString(),
    trend: "direct",
  },
  {
    type: "errors",
    icon: AlertTriangle,
    label: "Error rate",
    formatter: (v) => `${v.toFixed(1)}%`,
    trend: "inverted",
  },
  {
    type: "avg_duration",
    icon: Clock,
    label: "Avg duration",
    formatter: formatDuration,
    trend: "inverted",
  },
  {
    type: "total_cost",
    icon: Coins,
    label: "Total cost",
    formatter: (v) => formatCost(v, { noValue: "$0" }),
    trend: "inverted",
  },
];

type ChartMetricConfig = {
  metricName: METRIC_NAME_TYPE;
  chartType: CHART_TYPE.line | CHART_TYPE.bar;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
  renderValue?: (data: ChartTooltipRenderValueArguments) => ValueType;
  colorMap?: Record<string, string>;
  filterLineCallback?: (lineName: string) => boolean;
  labelsMap?: Record<string, string>;
};

const CHART_GREEN = "var(--chart-green)";
const CHART_RED = "var(--chart-red)";
const CHART_BLUE = "var(--chart-blue)";
const CHART_TEAL = "var(--chart-teal)";

const COUNT_METRIC_MAP: Record<KpiEntityType, METRIC_NAME_TYPE> = {
  traces: METRIC_NAME_TYPE.TRACE_COUNT,
  spans: METRIC_NAME_TYPE.SPAN_COUNT,
  threads: METRIC_NAME_TYPE.THREAD_COUNT,
};

const ERROR_RATE_METRIC_MAP: Partial<Record<KpiEntityType, METRIC_NAME_TYPE>> =
  {
    traces: METRIC_NAME_TYPE.TRACE_ERROR_RATE,
    spans: METRIC_NAME_TYPE.SPAN_ERROR_RATE,
  };

const AVG_DURATION_LINE_NAME_MAP: Record<KpiEntityType, string> = {
  traces: "trace_average_duration",
  spans: "span_average_duration",
  threads: "thread_average_duration",
};

const AVG_DURATION_METRIC_MAP: Record<KpiEntityType, METRIC_NAME_TYPE> = {
  traces: METRIC_NAME_TYPE.TRACE_AVERAGE_DURATION,
  spans: METRIC_NAME_TYPE.SPAN_AVERAGE_DURATION,
  threads: METRIC_NAME_TYPE.THREAD_AVERAGE_DURATION,
};

const getChartConfig = (
  kpiType: KpiMetricType,
  entityType: KpiEntityType,
): ChartMetricConfig => {
  switch (kpiType) {
    case "count":
      return {
        metricName: COUNT_METRIC_MAP[entityType],
        chartType: CHART_TYPE.bar,
        colorMap: { [entityType]: CHART_GREEN },
      };
    case "errors":
      return {
        metricName: ERROR_RATE_METRIC_MAP[entityType]!,
        chartType: CHART_TYPE.bar,
        colorMap: { [entityType]: CHART_RED },
      };
    case "avg_duration":
      return {
        metricName: AVG_DURATION_METRIC_MAP[entityType],
        chartType: CHART_TYPE.bar,
        customYTickFormatter: durationYTickFormatter,
        renderValue: renderDurationTooltipValue,
        colorMap: { [AVG_DURATION_LINE_NAME_MAP[entityType]]: CHART_TEAL },
      };
    case "total_cost":
      return {
        metricName: METRIC_NAME_TYPE.COST,
        chartType: CHART_TYPE.bar,
        customYTickFormatter: costYTickFormatter,
        renderValue: renderCostTooltipValue,
        colorMap: { cost: CHART_BLUE },
      };
  }
};

const SKELETON_BAR_COUNT = 30;
const SKELETON_BAR_HEIGHTS = Array.from(
  { length: SKELETON_BAR_COUNT },
  (_, i) => `${20 + ((i * 17 + 7) % 51)}%`,
);

const ChartPlaceholderBars: React.FC = () => (
  <div className="flex h-[var(--chart-height)] min-h-[80px] items-end gap-[3px]">
    {SKELETON_BAR_HEIGHTS.map((height, i) => (
      <div
        key={i}
        className="flex-1 rounded-t-sm bg-[hsl(var(--muted))]"
        style={{ height }}
      />
    ))}
  </div>
);

const ChartEmptyState: React.FC = () => (
  <div className="relative">
    <ChartPlaceholderBars />
    <div className="absolute inset-0 flex items-center justify-center">
      <span className="comet-body-s text-light-slate">Data not available</span>
    </div>
  </div>
);

const REFETCH_INTERVAL = 30000;

export type MetricsSummaryProps = {
  projectId: string;
  entityType: KpiEntityType;
  countLabel: string;
  filters?: Filters;
  intervalStart?: string;
  intervalEnd?: string;
  dateRange: DateRangeValue;
  logsSource?: LOGS_SOURCE;
};

const MetricsSummary: React.FC<MetricsSummaryProps> = ({
  projectId,
  entityType,
  countLabel,
  filters,
  intervalStart,
  intervalEnd,
  dateRange,
  logsSource,
}) => {
  const [selectedMetric, setSelectedMetric] = useState<KpiMetricType>("count");

  const chartIntervalConfig = useMemo(() => {
    const interval = calculateIntervalType(dateRange);
    const { intervalStart: chartStart, intervalEnd: chartEnd } =
      calculateIntervalStartAndEnd(dateRange);
    return {
      interval,
      intervalStart: chartStart,
      intervalEnd: chartEnd ?? dayjs().utc().format(),
    };
  }, [dateRange]);

  const { data, isPending } = useProjectKpiCards(
    {
      projectId,
      entityType,
      filters,
      intervalStart: intervalStart ?? chartIntervalConfig.intervalStart,
      intervalEnd: intervalEnd ?? chartIntervalConfig.intervalEnd,
      logsSource,
    },
    {
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const metricsMap = useMemo(() => {
    const map = new Map<KpiMetricType, KpiMetric>();
    data?.stats?.forEach((s) => map.set(s.type, s));
    return map;
  }, [data?.stats]);

  const allZero = useMemo(() => {
    if (!data?.stats?.length) return true;
    return data.stats.every(
      (s) => (s.current_value ?? 0) === 0 && (s.previous_value ?? 0) === 0,
    );
  }, [data?.stats]);

  const chartConfig = useMemo(
    () => getChartConfig(selectedMetric, entityType),
    [selectedMetric, entityType],
  );

  const chartFilters = useMemo(() => {
    if (entityType === "threads") return { threadFilters: filters };
    if (entityType === "spans") return { spanFilters: filters };
    return { traceFilters: filters };
  }, [entityType, filters]);

  const filteredCards = useMemo(
    () =>
      entityType === "threads"
        ? METRIC_CARDS.filter((card) => card.type !== "errors")
        : METRIC_CARDS,
    [entityType],
  );

  const handleSelectMetric = useCallback(
    (type: KpiMetricType) => setSelectedMetric(type),
    [],
  );

  const showData = !isPending && !allZero;

  return (
    <div>
      <div
        className="grid"
        style={{
          gridTemplateColumns: `repeat(${filteredCards.length}, minmax(0, 1fr))`,
        }}
      >
        {filteredCards.map((card, index) => {
          const metric = metricsMap.get(card.type);
          const currentValue = metric?.current_value ?? 0;
          const previousValue = metric?.previous_value ?? 0;
          const label = card.type === "count" ? countLabel : card.label;
          const isFirst = index === 0;
          const isLast = index === filteredCards.length - 1;

          return (
            <MetricCard
              key={card.type}
              icon={card.icon}
              label={label}
              value={showData ? card.formatter(currentValue) : "N/A"}
              currentRaw={showData ? currentValue : undefined}
              previousRaw={showData ? previousValue : undefined}
              trend={card.trend}
              selected={showData && selectedMetric === card.type}
              onClick={() => handleSelectMetric(card.type)}
              className={cn(
                isFirst && "rounded-tl-md",
                isLast && "rounded-tr-md",
              )}
            />
          );
        })}
      </div>
      <div
        className="rounded-b-md border border-t-0 bg-background p-4"
        style={{ "--chart-height": "80px" } as React.CSSProperties}
      >
        {showData ? (
          <MetricContainerChart
            name=""
            description=""
            chartType={chartConfig.chartType}
            projectId={projectId}
            interval={chartIntervalConfig.interval}
            intervalStart={chartIntervalConfig.intervalStart}
            intervalEnd={chartIntervalConfig.intervalEnd}
            metricName={chartConfig.metricName}
            customYTickFormatter={chartConfig.customYTickFormatter}
            renderValue={chartConfig.renderValue}
            chartId={`kpi-chart-${selectedMetric}`}
            chartOnly
            showLegend={false}
            customEmptyState={<ChartEmptyState />}
            customLoadingState={<ChartPlaceholderBars />}
            colorMap={chartConfig.colorMap}
            filterLineCallback={chartConfig.filterLineCallback}
            labelsMap={chartConfig.labelsMap}
            logsSource={logsSource}
            {...chartFilters}
          />
        ) : (
          <ChartEmptyState />
        )}
      </div>
    </div>
  );
};

export default MetricsSummary;
