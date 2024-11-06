import React, { useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, YAxis } from "recharts";

import { Dataset } from "@/types/datasets";
import {
  ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import ExperimentChartTooltipContent from "@/components/pages/ExperimentsPage/charts/ExperimentChartTooltipContent";
import ExperimentChartLegendContent from "@/components/pages/ExperimentsPage/charts/ExperimentChartLegendContent";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";

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
  const [hiddenLines, setHiddenLines] = useState<string[]>([]);

  const config = useMemo(() => {
    return chartData.lines.reduce<ChartConfig>((acc, line) => {
      acc[line] = {
        label: line,
        color: TAG_VARIANTS_COLOR_MAP[generateTagVariant(line)!],
      };
      return acc;
    }, {});
  }, [chartData.lines]);

  const tickWidth = useMemo(() => {
    const MIN_WIDTH = 26;
    const MAX_WIDTH = 80;
    const CHARACTER_WIDTH = 7;
    const EXTRA_SPACE = 10;

    const values = chartData.data.reduce<number[]>((acc, data) => {
      return [
        ...acc,
        ...Object.values(data.scores).map(
          (v) => Math.round(v).toString().length,
        ),
      ];
    }, []);

    return Math.min(
      Math.max(MIN_WIDTH, Math.max(...values) * CHARACTER_WIDTH + EXTRA_SPACE),
      MAX_WIDTH,
    );
  }, [chartData.data]);

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

  return (
    <Card className={cn("min-w-[max(400px,40%)]", className)} ref={ref}>
      <CardHeader>
        <CardTitle>{name}</CardTitle>
      </CardHeader>
      <CardContent>
        <ChartContainer config={config} className="h-32 w-full">
          <LineChart
            data={chartData.data}
            margin={{ top: 10, bottom: 10, left: 0, right: 0 }}
          >
            <CartesianGrid vertical={false} />
            <YAxis
              width={tickWidth}
              axisLine={false}
              tickLine={false}
              tick={{
                stroke: "#94A3B8",
                fontSize: 10,
                fontWeight: 200,
              }}
              interval="preserveStartEnd"
            />
            <ChartTooltip
              cursor={false}
              isAnimationActive={false}
              content={<ExperimentChartTooltipContent />}
            />
            <ChartLegend
              verticalAlign="top"
              layout="vertical"
              align="right"
              content={
                <ExperimentChartLegendContent
                  setHideState={setHiddenLines}
                  chartId={chartId}
                />
              }
              width={legendWidth}
              height={128}
            />
            {chartData.lines.map((line) => {
              const hide = hiddenLines.includes(line);

              return (
                <Line
                  type="natural"
                  key={line}
                  isAnimationActive={false}
                  dataKey={(x) => x.scores[line] || undefined}
                  name={config[line].label as string}
                  stroke={config[line].color as string}
                  dot={{ strokeWidth: 1, r: 1 }}
                  activeDot={{ strokeWidth: 1, r: 3 }}
                  hide={hide}
                />
              );
            })}
          </LineChart>
        </ChartContainer>
      </CardContent>
    </Card>
  );
};

export default ExperimentChartContainer;
