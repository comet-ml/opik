import React, { useState, useMemo, useCallback } from "react";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { Dot, LineChart, XAxis } from "recharts";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import ChartHorizontalLegendContent from "@/components/shared/ChartHorizontalLegendContent/ChartHorizontalLegendContent";
import {
  ChartContainer,
  ChartTooltip,
  ChartLegend,
} from "@/components/ui/chart";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { CartesianGrid, YAxis, Line } from "recharts";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import { LineDot } from "recharts/types/cartesian/Line";

export type DataRecord = {
  entityId: string;
  entityName: string;
  createdDate: string;
  value: number | null;
};

export type ChartData = {
  data: DataRecord[];
  line: string;
};

type OptimizationProgressChartContentProps = {
  bestEntityId?: string;
  chartData: ChartData;
};

const OptimizationProgressChartContent: React.FC<
  OptimizationProgressChartContentProps
> = ({ chartData, bestEntityId }) => {
  const { line, data } = chartData;
  const [, setActiveLine] = useState<string | null>(null);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig([line]);
  }, [line]);

  const values = useMemo(() => data.map((d) => d.value), [data]);

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

  const renderDot: LineDot = (props) => {
    // TODO lala error with key
    if (props.payload.entityId === bestEntityId) {
      return (
        <Dot
          {...props}
          fill={config[props.name as string].color}
          strokeWidth={8}
        />
      );
    }

    return (
      <Dot
        {...props}
        fill={config[props.name as string].color}
        strokeWidth={0}
      />
    );
  };

  return (
    <ChartContainer config={config} className="h-40 w-full">
      <LineChart
        data={chartData.data}
        margin={{ top: 10, bottom: 10, left: 10, right: 10 }}
      >
        <CartesianGrid vertical={false} />
        <XAxis
          dataKey="time"
          axisLine={false}
          tickLine={false}
          tick={DEFAULT_CHART_TICK}
          tickFormatter={(value, index) => data[index]?.entityName}
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
        <ChartTooltip
          isAnimationActive={false}
          content={<ChartTooltipContent renderHeader={renderHeader} />}
        />
        <ChartLegend
          content={
            <ChartHorizontalLegendContent
              setActiveLine={setActiveLine}
              chartId="optimization-progress-chart"
            />
          }
        />
        <Line
          type="linear"
          key={line}
          dataKey={(record) => record.value}
          name={config[line].label as string}
          stroke={config[line].color as string}
          dot={renderDot}
          activeDot={{ strokeWidth: 1.5, r: 4, stroke: "white" }}
          strokeWidth={1.5}
          strokeOpacity={1}
          animationDuration={800}
          connectNulls={false}
        />
      </LineChart>
    </ChartContainer>
  );
};

export default OptimizationProgressChartContent;
