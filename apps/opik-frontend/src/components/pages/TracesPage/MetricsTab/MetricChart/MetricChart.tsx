import React, { useCallback, useMemo, useState } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { ProjectMetricValue } from "@/types/projects";
import {
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import dayjs from "dayjs";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { Spinner } from "@/components/ui/spinner";
import useProjectMetric, {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
  ChartTooltipRenderValueArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { formatDate } from "@/lib/date";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import MetricChartLegendContent from "./MetricChartLegendContent";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";

const renderTooltipValue = ({ value }: ChartTooltipRenderValueArguments) =>
  value;

interface MetricChartProps {
  name: string;
  description: string;
  projectId: string;
  interval: INTERVAL_TYPE;
  intervalStart: string;
  intervalEnd: string;
  disableLoadingData: boolean;
  metricName: METRIC_NAME_TYPE;
  renderValue?: (data: ChartTooltipRenderValueArguments) => ValueType;
  labelsMap?: Record<string, string>;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
  chartId: string;
}

type TransformedDataValueType = null | number | string;
type TransformedData = { [key: string]: TransformedDataValueType };

const predefinedColorMap = {
  traces: TAG_VARIANTS_COLOR_MAP.purple,
  cost: TAG_VARIANTS_COLOR_MAP.purple,
  "duration.p50": TAG_VARIANTS_COLOR_MAP.turquoise,
  "duration.p90": TAG_VARIANTS_COLOR_MAP.burgundy,
  "duration.p99": TAG_VARIANTS_COLOR_MAP.purple,
  completion_tokens: TAG_VARIANTS_COLOR_MAP.turquoise,
  prompt_tokens: TAG_VARIANTS_COLOR_MAP.burgundy,
  total_tokens: TAG_VARIANTS_COLOR_MAP.purple,
};

const MetricChart = ({
  name,
  description,
  metricName,
  projectId,
  interval,
  intervalStart,
  intervalEnd,
  disableLoadingData,
  renderValue = renderTooltipValue,
  labelsMap,
  customYTickFormatter,
  chartId,
}: MetricChartProps) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);
  const { data: traces, isPending } = useProjectMetric(
    {
      projectId,
      metricName,
      interval,
      intervalStart,
      intervalEnd,
    },
    {
      enabled: !!projectId && !disableLoadingData,
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
        transformedData[dataIndex][trace.name] = d.value;
      });
    });

    return [transformedData, lines.sort(), values];
  }, [traces]);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(
      lines,
      labelsMap,
      predefinedColorMap,
    );
  }, [lines, labelsMap]);

  const {
    width: yTickWidth,
    ticks,
    domain,
    interval: yTickInterval,
    yTickFormatter,
  } = useChartTickDefaultConfig(values, {
    tickFormatter: customYTickFormatter,
  });

  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      return (
        <div className="comet-body-xs text-light-slate mb-1">
          {formatDate(payload?.[0]?.payload?.time, true)} UTC
        </div>
      );
    },
    [],
  );

  const xTickFormatter = useCallback(
    (val: string) => {
      if (interval === INTERVAL_TYPE.HOURLY) {
        return dayjs(val).utc().format("MM/DD hh:mm A");
      }

      return dayjs(val).utc().format("MM/DD");
    },
    [interval],
  );

  const renderContent = () => {
    const isSingleLine = lines.length === 1;
    const isSinglePoint =
      data.filter((point) => lines.every((line) => point[line] !== null))
        .length === 1;

    const [firstLine] = lines;

    const activeDot = { strokeWidth: 1.5, r: 4, stroke: "white" };

    if (isPending) {
      return (
        <div className="flex h-[var(--chart-height)] w-full  items-center justify-center">
          <Spinner />
        </div>
      );
    }

    return (
      <ChartContainer
        config={config}
        className="h-[var(--chart-height)] w-full"
      >
        <ComposedChart
          data={data}
          margin={{
            top: 5,
            right: 10,
            left: 5,
            bottom: 5,
          }}
        >
          <CartesianGrid vertical={false} />
          <XAxis
            dataKey="time"
            axisLine={false}
            tickLine={false}
            tick={DEFAULT_CHART_TICK}
            tickFormatter={xTickFormatter}
          />
          <YAxis
            tick={DEFAULT_CHART_TICK}
            axisLine={false}
            width={yTickWidth}
            tickLine={false}
            tickFormatter={yTickFormatter}
            ticks={ticks}
            domain={domain}
            interval={yTickInterval}
          />
          <ChartTooltip
            isAnimationActive={false}
            content={
              <ChartTooltipContent
                renderHeader={renderChartTooltipHeader}
                renderValue={renderValue}
              />
            }
          />
          <Tooltip />

          <ChartLegend
            content={
              <MetricChartLegendContent
                setActiveLine={setActiveLine}
                chartId={chartId}
              />
            }
          />

          {isSingleLine ? (
            <>
              <defs>
                <linearGradient
                  id={`chart-area-gradient-${firstLine}`}
                  x1="0"
                  y1="0"
                  x2="0"
                  y2="1"
                >
                  <stop
                    offset="0%"
                    stopColor={config[firstLine].color}
                    stopOpacity={0.3}
                  ></stop>
                  <stop
                    offset="50%"
                    stopColor={config[firstLine].color}
                    stopOpacity={0}
                  ></stop>
                </linearGradient>
              </defs>
              <Area
                type="monotone"
                dataKey={firstLine}
                stroke={config[firstLine].color}
                fill={`url(#chart-area-gradient-${firstLine})`}
                connectNulls
                strokeWidth={1.5}
                activeDot={activeDot}
                dot={
                  isSinglePoint
                    ? {
                        fill: config[firstLine].color,
                        strokeWidth: 0,
                        fillOpacity: 1,
                      }
                    : false
                }
                strokeOpacity={1}
              />
            </>
          ) : (
            lines.map((line) => {
              const isActive = line === activeLine;

              let strokeOpacity = 1;

              if (activeLine) {
                strokeOpacity = isActive ? 1 : 0.4;
              }

              return (
                <Line
                  key={line}
                  type="linear"
                  dataKey={line}
                  stroke={config[line].color || ""}
                  dot={
                    isSinglePoint
                      ? { fill: config[line].color, strokeWidth: 0 }
                      : false
                  }
                  activeDot={activeDot}
                  connectNulls
                  strokeWidth={1.5}
                  strokeOpacity={strokeOpacity}
                />
              );
            })
          )}
        </ComposedChart>
      </ChartContainer>
    );
  };

  return (
    <Card>
      <CardHeader className="space-y-0.5 p-5">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
        <CardDescription className="comet-body-xs text-xs">
          {description}
        </CardDescription>
      </CardHeader>
      <CardContent className="p-5">{renderContent()}</CardContent>
    </Card>
  );
};

export default MetricChart;
