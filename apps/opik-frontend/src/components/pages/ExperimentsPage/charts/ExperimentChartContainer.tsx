import React, { useCallback, useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, YAxis } from "recharts";
import isEmpty from "lodash/isEmpty";

import { Dataset } from "@/types/datasets";
import {
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import ExperimentChartLegendContent from "@/components/pages/ExperimentsPage/charts/ExperimentChartLegendContent";
import NoData from "@/components/shared/NoData/NoData";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";

const MIN_LEGEND_WIDTH = 140;
const MAX_LEGEND_WIDTH = 300;

export type DataRecord = {
  experimentId: string;
  experimentName: string;
  createdDate: string;
  scores: Record<string, number>;
};

export type ChartData = {
  dataset: Dataset;
  data: DataRecord[];
  lines: string[];
  index: number;
};

type ExperimentChartContainerProps = {
  className: string;
  chartData: ChartData;
};

const ExperimentChartContainer: React.FC<ExperimentChartContainerProps> = ({
  chartData,
  className,
}) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(chartData.lines);
  }, [chartData.lines]);

  const noData = useMemo(() => {
    return chartData.data.every((record) => isEmpty(record.scores));
  }, [chartData.data]);

  const values = useMemo(() => {
    return chartData.data.reduce<number[]>((acc, data) => {
      return [...acc, ...Object.values(data.scores)];
    }, []);
  }, [chartData.data]);

  const {
    width: tickWidth,
    ticks,
    domain,
    yTickFormatter,
    interval: tickInterval,
  } = useChartTickDefaultConfig(values, {
    tickPrecision: 2,
    numberOfTicks: 3,
  });

  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );

  const {
    dataset: { id: chartId, name },
  } = chartData;

  const legendWidth = Math.max(
    MIN_LEGEND_WIDTH,
    Math.min(width * 0.3, MAX_LEGEND_WIDTH),
  );

  const renderHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const { experimentName, createdDate } = payload[0].payload;

      return (
        <>
          <div className="comet-body-xs-accented mb-0.5 truncate">
            {experimentName}
          </div>
          <div className="comet-body-xs mb-1 text-light-slate">
            {createdDate}
          </div>
        </>
      );
    },
    [],
  );

  const isSinglePoint = chartData.data.length === 1;

  return (
    <Card className={cn("min-w-[400px]", className)} ref={ref}>
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
        <CardDescription className="comet-body-xs text-xs">
          Average scores
        </CardDescription>
      </CardHeader>
      <CardContent className="px-4 pb-3">
        {noData ? (
          <NoData
            className="min-h-32 text-light-slate"
            message="No scores to show"
          />
        ) : (
          <ChartContainer config={config} className="h-32 w-full">
            <LineChart
              data={chartData.data}
              margin={{ top: 5, bottom: 5, left: 5, right: 0 }}
            >
              <CartesianGrid vertical={false} />
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
              <ChartTooltip
                isAnimationActive={false}
                content={<ChartTooltipContent renderHeader={renderHeader} />}
              />
              <ChartLegend
                verticalAlign="top"
                layout="vertical"
                align="right"
                content={
                  <ExperimentChartLegendContent
                    setActiveLine={setActiveLine}
                    chartId={chartId}
                  />
                }
                width={legendWidth}
                height={128}
              />
              {chartData.lines.map((line) => {
                const isActive = line === activeLine;

                let strokeOpacity = 1;

                if (activeLine) {
                  strokeOpacity = isActive ? 1 : 0.4;
                }

                return (
                  <Line
                    type="linear"
                    key={line}
                    dataKey={(record) => record.scores[line]}
                    name={config[line].label as string}
                    stroke={config[line].color as string}
                    dot={
                      isSinglePoint
                        ? { fill: config[line].color, strokeWidth: 0 }
                        : false
                    }
                    activeDot={{ strokeWidth: 1.5, r: 4, stroke: "white" }}
                    strokeWidth={1.5}
                    strokeOpacity={strokeOpacity}
                    animationDuration={800}
                  />
                );
              })}
            </LineChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  );
};

export default ExperimentChartContainer;
