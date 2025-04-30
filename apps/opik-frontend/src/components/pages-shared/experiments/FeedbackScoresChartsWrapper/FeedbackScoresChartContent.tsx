import { useState, useMemo, useCallback } from "react";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { Dot, LineChart } from "recharts";
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
import ChartVerticalLegendContent from "@/components/shared/ChartVerticalLegendContent/ChartHorizontalLegendContent";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import { LineDot } from "recharts/types/cartesian/Line";
import { Dataset } from "@/types/datasets";

const MIN_LEGEND_WIDTH = 140;
const MAX_LEGEND_WIDTH = 300;

export type DataRecord = {
  entityId: string;
  entityName: string;
  createdDate: string;
  scores: Record<string, number>;
};

export type ChartData = {
  dataset: Dataset;
  data: DataRecord[];
  lines: string[];
};

type FeedbackScoresChartContentProps = {
  chartId: string;
  chartData: ChartData;
  containerWidth: number;
};

const FeedbackScoresChartContent: React.FC<FeedbackScoresChartContentProps> = ({
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
      const { entityName, createdDate } = payload[0].payload;

      return (
        <>
          <div className="comet-body-xs-accented mb-0.5 truncate">
            {entityName}
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

  const shouldShowDot = (dataIndex: number, line: string): boolean => {
    if (isSinglePoint) return true;

    const { data = [] } = chartData;

    const hasValueAt = (idx: number): boolean => {
      const value = data[idx]?.scores?.[line];
      return value !== null && value !== undefined;
    };

    if (!hasValueAt(dataIndex)) return false;

    const hasPreviousValue = dataIndex > 0 && hasValueAt(dataIndex - 1);
    const hasNextValue =
      dataIndex < data.length - 1 && hasValueAt(dataIndex + 1);

    return !hasPreviousValue && !hasNextValue;
  };

  const renderDot: LineDot = (props) => {
    if (shouldShowDot(props.index, props.name)) {
      const { key, ...rest } = props;
      return (
        <Dot
          key={key}
          {...rest}
          fill={config[props.name as string].color}
          strokeWidth={0}
        />
      );
    }

    return <></>;
  };

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
            <ChartVerticalLegendContent
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
              dot={renderDot}
              activeDot={{ strokeWidth: 1.5, r: 4, stroke: "white" }}
              strokeWidth={1.5}
              strokeOpacity={strokeOpacity}
              animationDuration={800}
              connectNulls={false}
            />
          );
        })}
      </LineChart>
    </ChartContainer>
  );
};

export default FeedbackScoresChartContent;
