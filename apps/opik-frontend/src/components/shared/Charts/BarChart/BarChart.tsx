import React, { useState, useCallback, useMemo } from "react";
import {
  Bar,
  BarChart as RechartsBarChart,
  CartesianGrid,
  XAxis,
  YAxis,
} from "recharts";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";

import {
  ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import ChartHorizontalLegend from "@/components/shared/Charts/ChartHorizontalLegend/ChartHorizontalLegend";
import {
  DEFAULT_CHART_TICK,
  DEFAULT_CHART_GRID_PROPS,
} from "@/constants/chart";
import { extractChartValues } from "@/lib/charts";

type ChartDataPoint = Record<string, number | string | null>;

const defaultRenderTooltipValue = ({ value }: { value: ValueType }) => value;

interface BarChartProps {
  chartId: string;
  config: ChartConfig;
  data: ChartDataPoint[];
  xAxisKey?: string;
  xTickFormatter?: (value: string) => string;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
  renderTooltipValue?: (data: { value: ValueType }) => ValueType;
  renderTooltipHeader?: (
    args: ChartTooltipRenderHeaderArguments,
  ) => React.ReactNode;
  showLegend?: boolean;
  maxBarSize?: number;
  className?: string;
}

const BarChart: React.FunctionComponent<BarChartProps> = ({
  chartId,
  config,
  data,
  xAxisKey = "time",
  xTickFormatter,
  customYTickFormatter,
  renderTooltipValue = defaultRenderTooltipValue,
  renderTooltipHeader,
  showLegend = true,
  maxBarSize = 52,
  className = "h-[var(--chart-height)] w-full",
}) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const lines = useMemo(() => Object.keys(config), [config]);

  const values = useMemo(
    () => extractChartValues(data, config),
    [data, config],
  );

  const {
    width: yTickWidth,
    ticks,
    domain,
    interval: yTickInterval,
    yTickFormatter,
  } = useChartTickDefaultConfig(values, {
    tickFormatter: customYTickFormatter,
  });

  const defaultTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const name = payload?.[0]?.payload?.[xAxisKey];
      return (
        <div className="comet-body-xs-accented truncate pb-1.5">{name}</div>
      );
    },
    [xAxisKey],
  );

  const tooltipHeader = renderTooltipHeader || defaultTooltipHeader;

  return (
    <ChartContainer config={config} className={className}>
      <RechartsBarChart
        data={data}
        margin={{
          top: 5,
          right: 10,
          left: 5,
          bottom: 5,
        }}
      >
        <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />

        <XAxis
          dataKey={xAxisKey}
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
        {showLegend && (
          <ChartLegend
            content={
              <ChartHorizontalLegend
                setActiveLine={setActiveLine}
                chartId={chartId}
              />
            }
          />
        )}
        <ChartTooltip
          isAnimationActive={false}
          cursor={{ fillOpacity: 0.6 }}
          content={
            <ChartTooltipContent
              renderHeader={tooltipHeader}
              renderValue={renderTooltipValue}
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
              maxBarSize={maxBarSize}
            />
          );
        })}
      </RechartsBarChart>
    </ChartContainer>
  );
};

export default BarChart;
