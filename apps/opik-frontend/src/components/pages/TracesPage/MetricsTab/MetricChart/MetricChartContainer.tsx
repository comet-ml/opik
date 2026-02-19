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
import useChartConfig from "@/hooks/useChartConfig";
import useProjectMetric, {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { ChartTooltipRenderValueArguments } from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import NoData from "@/components/shared/NoData/NoData";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";
import { COLOR_VARIANTS_MAP } from "@/constants/colorVariants";
import { Filter } from "@/types/filters";
import { CHART_TYPE } from "@/constants/chart";
import MetricLineChart from "./MetricLineChart";
import MetricBarChart from "./MetricBarChart";
import { BreakdownConfig } from "@/types/dashboard";
import { BREAKDOWN_GROUP_NAMES } from "@/components/shared/Dashboard/widgets/ProjectMetricsWidget/breakdown";

const renderTooltipValue = ({ value }: ChartTooltipRenderValueArguments) => {
  if (isNumber(value)) {
    return formatNumericData(value);
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

const customColorMap = {
  traces: COLOR_VARIANTS_MAP.purple.css,
  cost: COLOR_VARIANTS_MAP.purple.css,
  "duration.p50": COLOR_VARIANTS_MAP.turquoise.css,
  "duration.p90": COLOR_VARIANTS_MAP.burgundy.css,
  "duration.p99": COLOR_VARIANTS_MAP.purple.css,
  completion_tokens: COLOR_VARIANTS_MAP.turquoise.css,
  prompt_tokens: COLOR_VARIANTS_MAP.burgundy.css,
  total_tokens: COLOR_VARIANTS_MAP.purple.css,
  failed: COLOR_VARIANTS_MAP.pink.css,
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

  const [data, lines] = useMemo(() => {
    if (!traces?.filter((trace) => !!trace.name).length) {
      return [[], []];
    }

    const linesList: string[] = [];

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

        trace.data?.forEach((d) => {
          const index = timeToIndexMap.get(d.time);
          if (index !== undefined && transformedData[index]) {
            transformedData[index][displayName] = d.value;
          }
        });
      }
    });

    return [transformedData, linesList];
  }, [traces, filterLineCallback]);

  const noData = useMemo(() => {
    if (isPending) return false;
    if (data.length === 0) return true;

    return data.every((record) => lines.every((line) => isNil(record[line])));
  }, [data, lines, isPending]);

  const config = useChartConfig(lines, labelsMap, customColorMap);

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
