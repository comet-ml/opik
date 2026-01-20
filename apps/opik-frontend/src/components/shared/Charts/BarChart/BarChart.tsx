import React, { useState, useCallback, useMemo } from "react";
import {
  Bar,
  BarChart as RechartsBarChart,
  CartesianGrid,
  XAxis,
  YAxis,
  Cell,
} from "recharts";
import {
  ValueType,
  NameType,
} from "recharts/types/component/DefaultTooltipContent";
import { TooltipProps } from "recharts";

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
  // Optional per-bucket ranking for descending order bar rendering
  // Maps time bucket to array of group names in descending value order
  perBucketRanking?: Record<string, string[]>;
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
  perBucketRanking,
}) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const lines = useMemo(() => Object.keys(config), [config]);

  // Transform data for per-bucket ranking (descending order within each time bucket)
  const { transformedData, rankKeys, groupAtRank } = useMemo(() => {
    if (!perBucketRanking || lines.length === 0) {
      return { transformedData: data, rankKeys: lines, groupAtRank: null };
    }

    // Create rank keys (rank0, rank1, rank2, etc.)
    const rankKeys = lines.map((_, idx) => `__rank${idx}`);

    // Map: for each data point index and rank, which group is at that rank
    const groupAtRank: Record<number, Record<string, string>> = {};

    const transformedData = data.map((point, dataIdx) => {
      const time = point[xAxisKey] as string;
      const ranking = perBucketRanking[time] || lines;

      groupAtRank[dataIdx] = {};
      const newPoint: ChartDataPoint = { [xAxisKey]: time };

      ranking.forEach((groupName, rankIdx) => {
        const rankKey = `__rank${rankIdx}`;
        newPoint[rankKey] = point[groupName];
        groupAtRank[dataIdx][rankKey] = groupName;
      });

      return newPoint;
    });

    return { transformedData, rankKeys, groupAtRank };
  }, [data, perBucketRanking, lines, xAxisKey]);

  const values = useMemo(
    () =>
      extractChartValues(
        transformedData,
        perBucketRanking
          ? rankKeys.reduce((acc, key, idx) => {
              acc[key] = config[lines[idx]] || { label: key };
              return acc;
            }, {} as ChartConfig)
          : config,
      ),
    [transformedData, config, perBucketRanking, rankKeys, lines],
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

  // Custom tooltip content wrapper that translates rank keys to group names
  const renderTooltipContent = useCallback(
    (props: TooltipProps<ValueType, NameType>) => {
      // If we have per-bucket ranking, transform the payload to show original group names
      if (perBucketRanking && groupAtRank && props.payload) {
        const dataIndex = props.payload[0]?.payload
          ? transformedData.findIndex(
              (d) => d[xAxisKey] === props.payload![0]?.payload?.[xAxisKey],
            )
          : -1;

        if (dataIndex >= 0) {
          const transformedPayload = props.payload.map((item) => {
            const rankKey = item.dataKey as string;
            const groupName = groupAtRank[dataIndex]?.[rankKey];
            if (groupName) {
              return {
                ...item,
                name: groupName,
                color: config[groupName]?.color || item.color,
              };
            }
            return item;
          });

          // Sort payload by value descending to match bar order
          transformedPayload.sort((a, b) => {
            const aVal = typeof a.value === "number" ? a.value : 0;
            const bVal = typeof b.value === "number" ? b.value : 0;
            return bVal - aVal;
          });

          return (
            <ChartTooltipContent
              active={props.active}
              payload={transformedPayload}
              renderHeader={tooltipHeader}
              renderValue={renderTooltipValue}
            />
          );
        }
      }

      return (
        <ChartTooltipContent
          active={props.active}
          payload={props.payload}
          renderHeader={tooltipHeader}
          renderValue={renderTooltipValue}
        />
      );
    },
    [
      perBucketRanking,
      groupAtRank,
      transformedData,
      xAxisKey,
      config,
      tooltipHeader,
      renderTooltipValue,
    ],
  );

  return (
    <ChartContainer config={config} className={className}>
      <RechartsBarChart
        data={transformedData}
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
            // For per-bucket ranking, override the legend payload to show original group names
            payload={
              perBucketRanking
                ? lines.map((line) => ({
                    value: line,
                    color: config[line]?.color || "",
                    type: "square" as const,
                    id: line,
                    dataKey: line,
                  }))
                : undefined
            }
          />
        )}
        <ChartTooltip
          isAnimationActive={false}
          cursor={{ fillOpacity: 0.6 }}
          content={renderTooltipContent}
        />
        {perBucketRanking && groupAtRank
          ? // Render bars with per-bucket descending order using Cell components
            rankKeys.map((rankKey) => {
              return (
                <Bar
                  key={rankKey}
                  dataKey={rankKey}
                  maxBarSize={maxBarSize}
                  // Hide from legend - we use the original lines for legend
                  legendType="none"
                >
                  {transformedData.map((entry, dataIdx) => {
                    const groupName = groupAtRank[dataIdx]?.[rankKey];
                    const groupConfig = groupName ? config[groupName] : null;
                    const color = groupConfig?.color || "";
                    const isActive = groupName === activeLine;
                    let fillOpacity = 1;
                    if (activeLine) {
                      fillOpacity = isActive ? 1 : 0.4;
                    }
                    return (
                      <Cell
                        key={`${rankKey}-${dataIdx}`}
                        fill={color}
                        fillOpacity={fillOpacity}
                      />
                    );
                  })}
                </Bar>
              );
            })
          : // Standard rendering without per-bucket ranking
            lines.map((line) => {
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
