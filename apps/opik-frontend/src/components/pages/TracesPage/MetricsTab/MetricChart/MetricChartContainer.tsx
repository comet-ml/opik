import React, { useMemo } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import isNil from "lodash/isNil";
import isNumber from "lodash/isNumber";
import { formatNumericData } from "@/lib/utils";

import { TransformedData } from "@/types/projects";
import {
  getDefaultHashedColorsChartConfig,
  generateBreakdownColorMap,
} from "@/lib/charts";
import useProjectMetric, {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { ChartTooltipRenderValueArguments } from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import NoData from "@/components/shared/NoData/NoData";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { Filter } from "@/types/filters";
import { CHART_TYPE } from "@/constants/chart";
import MetricLineChart from "./MetricLineChart";
import MetricBarChart from "./MetricBarChart";
import { BreakdownConfig } from "@/types/dashboard";
import { BREAKDOWN_GROUP_NAMES } from "@/constants/breakdown";

const MAX_DECIMAL_PLACES = 4;

const renderTooltipValue = ({ value }: ChartTooltipRenderValueArguments) => {
  if (isNumber(value)) {
    return formatNumericData(value, MAX_DECIMAL_PLACES);
  }
  return value;
};

interface MetricContainerChartProps {
  name: string;
  description: string;
  chartType: CHART_TYPE.line | CHART_TYPE.bar;
  projectId: string;
  interval: INTERVAL_TYPE;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
  metricName: METRIC_NAME_TYPE;
  renderValue?: (data: ChartTooltipRenderValueArguments) => ValueType;
  labelsMap?: Record<string, string>;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
  chartId: string;
  traceFilters?: Filter[];
  threadFilters?: Filter[];
  spanFilters?: Filter[];
  filterLineCallback?: (lineName: string) => boolean;
  chartOnly?: boolean;
  breakdown?: BreakdownConfig;
}

const predefinedColorMap = {
  traces: TAG_VARIANTS_COLOR_MAP.purple,
  cost: TAG_VARIANTS_COLOR_MAP.purple,
  "duration.p50": TAG_VARIANTS_COLOR_MAP.turquoise,
  "duration.p90": TAG_VARIANTS_COLOR_MAP.burgundy,
  "duration.p99": TAG_VARIANTS_COLOR_MAP.purple,
  completion_tokens: TAG_VARIANTS_COLOR_MAP.turquoise,
  prompt_tokens: TAG_VARIANTS_COLOR_MAP.burgundy,
  total_tokens: TAG_VARIANTS_COLOR_MAP.purple,
  failed: TAG_VARIANTS_COLOR_MAP.pink,
};

const METRIC_CHART_TYPE = {
  [CHART_TYPE.line]: MetricLineChart,
  [CHART_TYPE.bar]: MetricBarChart,
};

const MetricContainerChart = ({
  name,
  description,
  metricName,
  projectId,
  interval,
  intervalStart,
  intervalEnd,
  renderValue = renderTooltipValue,
  labelsMap,
  customYTickFormatter,
  chartId,
  chartType = CHART_TYPE.line,
  traceFilters,
  threadFilters,
  spanFilters,
  filterLineCallback,
  chartOnly = false,
  breakdown,
}: MetricContainerChartProps) => {
  const { data: response, isPending } = useProjectMetric(
    {
      projectId,
      metricName,
      interval,
      intervalStart,
      intervalEnd,
      traceFilters,
      threadFilters,
      spanFilters,
      breakdown,
    },
    {
      enabled: !!projectId,
      refetchInterval: 30000,
    },
  );

  const traces = response?.results;

  const [data, lines, perBucketRanking] = useMemo(() => {
    if (!traces?.filter((trace) => !!trace.name).length) {
      return [[], [], undefined];
    }

    const linesList: string[] = [];
    const lineTotals: Record<string, number> = {};

    // collect all unique time values from all traces
    const sortedTimeValues = Array.from(
      new Set(traces.flatMap((t) => t.data?.map((d) => d.time) ?? [])),
    ).sort((a, b) => new Date(a).getTime() - new Date(b).getTime());

    const transformedData: TransformedData[] = sortedTimeValues.map((time) => ({
      time,
    }));

    const timeToIndexMap = new Map<string, number>(
      sortedTimeValues.map((time, index) => [time, index]),
    );

    traces.forEach((trace) => {
      const shouldInclude = filterLineCallback
        ? filterLineCallback(trace.name)
        : true;

      if (shouldInclude) {
        // Use display_name if available (for "Others" group), otherwise use name
        const displayName =
          trace.name === BREAKDOWN_GROUP_NAMES.OTHERS
            ? BREAKDOWN_GROUP_NAMES.OTHERS_DISPLAY
            : trace.name;
        linesList.push(displayName);

        // Calculate total value for sorting
        let total = 0;
        trace.data?.forEach((d) => {
          const index = timeToIndexMap.get(d.time);
          if (index !== undefined && transformedData[index]) {
            transformedData[index][displayName] = d.value;
          }
          if (isNumber(d.value)) {
            total += d.value;
          }
        });
        lineTotals[displayName] = total;
      }
    });

    // For bar charts with breakdown, sort bars by value within each time bucket (descending)
    // This ensures the largest value bar is rendered first (leftmost) for each date
    if (breakdown && chartType === CHART_TYPE.bar && linesList.length > 1) {
      // Create per-bucket ranking: for each time bucket, store the sorted order of groups
      const ranking: Record<string, string[]> = {};

      transformedData.forEach((dataPoint) => {
        const time = dataPoint.time as string;
        // Get all groups with their values for this time bucket
        const groupValues = linesList.map((groupName) => ({
          name: groupName,
          value: isNumber(dataPoint[groupName])
            ? (dataPoint[groupName] as number)
            : 0,
        }));

        // Sort by value descending
        groupValues.sort((a, b) => b.value - a.value);
        ranking[time] = groupValues.map((g) => g.name);
      });

      // Sort linesList by total for legend ordering (highest total first)
      linesList.sort((a, b) => (lineTotals[b] || 0) - (lineTotals[a] || 0));

      return [transformedData, linesList, ranking];
    }

    return [transformedData, linesList, undefined];
  }, [traces, filterLineCallback, breakdown, chartType]);

  const noData = useMemo(() => {
    if (isPending) return false;
    if (data.length === 0) return true;

    return data.every((record) => lines.every((line) => isNil(record[line])));
  }, [data, lines, isPending]);

  const config = useMemo(() => {
    // Use distinct colors for breakdown groups to ensure visual distinction
    if (breakdown) {
      const breakdownColorMap = generateBreakdownColorMap(lines);
      return getDefaultHashedColorsChartConfig(
        lines,
        labelsMap,
        breakdownColorMap,
      );
    }
    // Use predefined colors for non-breakdown charts (legacy behavior)
    return getDefaultHashedColorsChartConfig(
      lines,
      labelsMap,
      predefinedColorMap,
    );
  }, [lines, labelsMap, breakdown]);

  const CHART = METRIC_CHART_TYPE[chartType];

  const chartContent = noData ? (
    <NoData
      className="h-[var(--chart-height)] min-h-32 text-light-slate"
      message="No data to show"
    />
  ) : (
    <CHART
      config={config}
      interval={interval}
      renderValue={renderValue}
      customYTickFormatter={customYTickFormatter}
      chartId={chartId}
      isPending={isPending}
      data={data}
      perBucketRanking={perBucketRanking}
    />
  );

  if (chartOnly) return chartContent;

  return (
    <Card>
      <CardHeader className="space-y-0.5 p-5">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
        <CardDescription className="comet-body-xs text-xs">
          {description}
        </CardDescription>
      </CardHeader>
      <CardContent className="p-5">{chartContent}</CardContent>
    </Card>
  );
};

export default MetricContainerChart;
