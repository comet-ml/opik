import { ChartData } from "./ExperimentChartContainer";
import { useState, useMemo, useCallback } from "react";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { LineChart } from "recharts";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import {
  ChartContainer,
  ChartTooltip,
  ChartLegend,
} from "@/components/ui/chart";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { CartesianGrid, YAxis, Line } from "recharts";
import ExperimentChartLegendContent from "./ExperimentChartLegendContent";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";

const MIN_LEGEND_WIDTH = 140;
const MAX_LEGEND_WIDTH = 300;

type ExperimentChartContentProps = {
  chartId: string;
  chartData: ChartData;
  containerWidth: number;
};

const ExperimentChartContent: React.FC<ExperimentChartContentProps> = ({
  chartId,
  chartData,
  containerWidth,
}) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(chartData.lines);
  }, [chartData.lines]);

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

  const legendWidth = Math.max(
    MIN_LEGEND_WIDTH,
    Math.min(containerWidth * 0.3, MAX_LEGEND_WIDTH),
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
  );
};

export default ExperimentChartContent;
