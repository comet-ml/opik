import React, { useCallback, useState } from "react";
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";
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

interface MetricBarChartProps {
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

const MetricBarChart = ({
  config,
  interval,
  renderValue = renderTooltipValue,
  customYTickFormatter,
  chartId,
  isPending,
  values,
  data,
  lines,
}: MetricBarChartProps) => {
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
      console.log("vakl", val);
      if (interval === INTERVAL_TYPE.HOURLY) {
        return dayjs(val).utc().format("MM/DD hh:mm A");
      }

      return dayjs(val).utc().format("MM/DD");
    },
    [interval],
  );

  if (isPending) {
    return (
      <div className="flex h-[var(--chart-height)] w-full  items-center justify-center">
        <Spinner />
      </div>
    );
  }

  return (
    <ChartContainer config={config} className="h-[var(--chart-height)] w-full">
      <BarChart
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
          dy={10}
          tick={DEFAULT_CHART_TICK}
          tickFormatter={xTickFormatter}
        />
        <YAxis
          width={yTickWidth}
          axisLine={false}
          tickLine={false}
          tick={DEFAULT_CHART_TICK}
          interval={yTickInterval}
          ticks={ticks}
          tickFormatter={yTickFormatter}
          domain={domain}
        />
        <ChartLegend
          content={
            <ChartHorizontalLegendContent
              setActiveLine={setActiveLine}
              chartId={chartId}
            />
          }
        />
        <ChartTooltip
          isAnimationActive={false}
          cursor={{ fillOpacity: 0.6 }}
          content={
            <ChartTooltipContent
              renderHeader={renderChartTooltipHeader}
              renderValue={renderValue}
            />
          }
        />
        {lines.map((line) => {
          const isActive = line === activeLine;
          let fillOpacity = 1;

          if (activeLine) {
            fillOpacity = isActive ? 1 : 0.4;
          }

          return (
            <Bar
              key={line}
              name={line}
              dataKey={line}
              fill={config[line].color || ""}
              fillOpacity={fillOpacity}
              maxBarSize={52}
            />
          );
        })}
      </BarChart>
    </ChartContainer>
  );
};

export default MetricBarChart;
