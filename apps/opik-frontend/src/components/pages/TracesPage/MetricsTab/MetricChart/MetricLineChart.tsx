import React, { useCallback, useState } from "react";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import dayjs from "dayjs";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { Spinner } from "@/components/ui/spinner";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
  ChartTooltipRenderValueArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { formatDate } from "@/lib/date";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import ChartHorizontalLegendContent from "@/components/shared/ChartHorizontalLegendContent/ChartHorizontalLegendContent";
import { ProjectMetricValue, TransformedData } from "@/types/projects";

const renderTooltipValue = ({ value }: ChartTooltipRenderValueArguments) =>
  value;

interface MetricChartProps {
  config: ChartConfig;
  interval: INTERVAL_TYPE;
  renderValue?: (data: ChartTooltipRenderValueArguments) => ValueType;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
  chartId: string;
  data: TransformedData[];
  lines: string[];
  values: ProjectMetricValue[];
  isPending: boolean;
}

const MetricLineChart = ({
  config,
  interval,
  renderValue = renderTooltipValue,
  customYTickFormatter,
  chartId,
  isPending,
  values,
  data,
  lines,
}: MetricChartProps) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);

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
        <div className="comet-body-xs mb-1 text-light-slate">
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
    <ChartContainer config={config} className="h-[var(--chart-height)] w-full">
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
            <ChartHorizontalLegendContent
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
                animationDuration={800}
              />
            );
          })
        )}
      </ComposedChart>
    </ChartContainer>
  );
};

export default MetricLineChart;
