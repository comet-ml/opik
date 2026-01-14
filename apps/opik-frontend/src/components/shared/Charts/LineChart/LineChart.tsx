import React, { useState, useCallback, useMemo } from "react";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  XAxis,
  YAxis,
} from "recharts";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";
import snakeCase from "lodash/snakeCase";

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

interface LineChartProps {
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
  showArea?: boolean;
  connectNulls?: boolean;
  className?: string;
}

const LineChart: React.FunctionComponent<LineChartProps> = ({
  chartId,
  config,
  data,
  xAxisKey = "time",
  xTickFormatter,
  customYTickFormatter,
  renderTooltipValue = defaultRenderTooltipValue,
  renderTooltipHeader,
  showLegend = true,
  showArea = true,
  connectNulls = true,
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

  const isSingleLine = lines.length === 1;
  const isSinglePoint =
    data.filter((point) => lines.every((line) => point[line] !== null))
      .length === 1;

  const [firstLine] = lines;

  const activeDot = { strokeWidth: 1.5, r: 4, stroke: "white" };

  return (
    <ChartContainer config={config} className={className}>
      <ComposedChart
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
              renderHeader={tooltipHeader}
              renderValue={renderTooltipValue}
            />
          }
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

        {isSingleLine && showArea ? (
          <>
            <defs>
              <linearGradient
                id={`chart-area-gradient-${snakeCase(firstLine)}`}
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
              fill={`url(#chart-area-gradient-${snakeCase(firstLine)})`}
              connectNulls={connectNulls}
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
                connectNulls={connectNulls}
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

export default LineChart;
