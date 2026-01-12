import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  DEFAULT_CHART_GRID_PROPS,
  DEFAULT_CHART_TICK,
} from "@/constants/chart";
import {
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import {
  getDefaultHashedColorsChartConfig,
  truncateChartLabel,
} from "@/lib/charts";
import React, { useCallback, useMemo, useState } from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid } from "recharts";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { uniq } from "lodash";
import NoData from "@/components/shared/NoData/NoData";
import { BarDataPoint } from "@/types/chart";
import ChartHorizontalLegend from "@/components/shared/Charts/ChartHorizontalLegend/ChartHorizontalLegend";

interface ExperimentsBarChartProps {
  name: string;
  chartId: string;
  data: BarDataPoint[];
  keys: string[];
}

const CHART_INNER_PADDING = 100;

const ExperimentsBarChart: React.FC<ExperimentsBarChartProps> = ({
  name,
  chartId,
  data,
  keys,
}) => {
  const [activeBar, setActiveBar] = useState<string | null>(null);
  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(keys);
  }, [keys]);

  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const { name } = payload[0].payload;

      return (
        <div className="comet-body-xs-accented truncate pb-1.5">{name}</div>
      );
    },
    [],
  );

  const noData = !keys.length;

  const values = useMemo(() => {
    const valuesList = data.reduce<number[]>((acc, data) => {
      const valuesByScore = keys.map((name) => data[name] as number);
      return uniq([...acc, ...valuesByScore]);
    }, []);

    if (valuesList.every((v) => v === 0)) {
      return [];
    }

    return valuesList;
  }, [data, keys]);

  const {
    width: tickWidth,
    ticks,
    domain,
    yTickFormatter,
    interval: tickInterval,
  } = useChartTickDefaultConfig(values, {
    maxTickPrecision: 2,
  });

  const truncateXAxisLabel = useCallback(
    (label: string) => {
      const labelsCount = keys.length;
      const xAxisWidth = width - CHART_INNER_PADDING;
      const maxLabelWidth = xAxisWidth / labelsCount;

      const maxTruncateLength = Math.floor(maxLabelWidth / 7);

      return truncateChartLabel(label, maxTruncateLength);
    },
    [keys.length, width],
  );

  return (
    <Card ref={ref}>
      <CardHeader className="space-y-0.5 px-5 py-4">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
      </CardHeader>
      <CardContent className="px-5 pb-3">
        {noData ? (
          <NoData
            className="min-h-32 text-light-slate"
            message="No data to show"
          />
        ) : (
          <ChartContainer
            config={config}
            className="h-[var(--chart-height)] w-full"
          >
            <BarChart
              data={data}
              margin={{
                top: 5,
                right: 5,
                left: 5,
                bottom: 0,
              }}
            >
              <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />

              <XAxis
                dataKey="name"
                axisLine={false}
                tickLine={false}
                dy={10}
                tick={DEFAULT_CHART_TICK}
                tickFormatter={truncateXAxisLabel}
              />
              <YAxis
                width={tickWidth}
                axisLine={false}
                tickLine={false}
                tick={DEFAULT_CHART_TICK}
                interval={tickInterval}
                ticks={ticks}
                tickFormatter={yTickFormatter}
                domain={domain}
              />
              <ChartLegend
                content={
                  <ChartHorizontalLegend
                    setActiveLine={setActiveBar}
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
                  />
                }
              />
              {keys.map((key) => {
                const isActive = key === activeBar;
                let fillOpacity = 1;

                if (activeBar) {
                  fillOpacity = isActive ? 1 : 0.4;
                }

                return (
                  <Bar
                    key={key}
                    name={key}
                    dataKey={key}
                    fill={config[key].color || ""}
                    fillOpacity={fillOpacity}
                    maxBarSize={52}
                  />
                );
              })}
            </BarChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  );
};

export default ExperimentsBarChart;
