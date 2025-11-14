import React, { useMemo } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import isNil from "lodash/isNil";

import { ProjectMetricValue, TransformedData } from "@/types/projects";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import useProjectMetric, {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import { ChartTooltipRenderValueArguments } from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import NoData from "@/components/shared/NoData/NoData";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { Filter } from "@/types/filters";
import MetricLineChart from "./MetricLineChart";
import MetricBarChart from "./MetricBarChart";

const renderTooltipValue = ({ value }: ChartTooltipRenderValueArguments) =>
  value;

interface MetricContainerChartProps {
  name: string;
  description: string;
  chartType: "bar" | "line";
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
  line: MetricLineChart,
  bar: MetricBarChart,
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
  chartType = "line",
  traceFilters,
  threadFilters,
}: MetricContainerChartProps) => {
  const { data: traces, isPending } = useProjectMetric(
    {
      projectId,
      metricName,
      interval,
      intervalStart,
      intervalEnd,
      traceFilters,
      threadFilters,
    },
    {
      enabled: !!projectId,
      refetchInterval: 30000,
    },
  );

  const [data, lines, values] = useMemo(() => {
    if (!traces?.filter((trace) => !!trace.name).length) {
      return [[], [], []];
    }

    const lines: string[] = [];
    const values: ProjectMetricValue[] = [];
    const timeValues = traces[0].data?.map((entry) => entry.time);
    const transformedData: TransformedData[] = timeValues.map((time) => ({
      time,
    }));

    traces.forEach((trace) => {
      lines.push(trace.name);

      trace.data.forEach((d, dataIndex) => {
        values.push(d.value);
        if (transformedData[dataIndex]) {
          transformedData[dataIndex][trace.name] = d.value;
        }
      });
    });

    return [transformedData, lines.sort(), values];
  }, [traces]);

  const noData = useMemo(() => {
    if (isPending) return false;
    if (data.length === 0) return true;

    return data.every((record) => lines.every((line) => isNil(record[line])));
  }, [data, lines, isPending]);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(
      lines,
      labelsMap,
      predefinedColorMap,
    );
  }, [lines, labelsMap]);

  const CHART = METRIC_CHART_TYPE[chartType];

  return (
    <Card>
      <CardHeader className="space-y-0.5 p-5">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
        <CardDescription className="comet-body-xs text-xs">
          {description}
        </CardDescription>
      </CardHeader>
      <CardContent className="p-5">
        {noData ? (
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
            lines={lines}
            values={values}
          />
        )}
      </CardContent>
    </Card>
  );
};

export default MetricContainerChart;
