import React, { useState, useMemo, useCallback } from "react";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { Dot, XAxis, CartesianGrid, YAxis, AreaChart, Area } from "recharts";
import { LineDot } from "recharts/types/cartesian/Line";
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
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";

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
    const { key, ...rest } = props;
    const color = config[props.name as string].color;
    const height = 80;
    const radius = 8;
    if (props.payload.entityId === bestEntityId) {
      return (
        <React.Fragment key={key}>
          <Dot {...rest} fill={color} strokeWidth={0} r={radius} />
          <Dot
            r={5}
            fill={color}
            cx={props.cx}
            cy={props.cy}
            strokeWidth={1.5}
            stroke="white"
          />
          <rect
            x={props.cx - 0.75}
            y={props.cy + radius}
            width="1.5"
            height={height - radius - props.cy}
            fill={color}
          />
        </React.Fragment>
      );
    }

    return (
      <Dot key={key} {...rest} fill={color} strokeWidth={1.5} stroke="white" />
    );
  };

  return (
    <ChartContainer config={config} className="h-40 w-full">
      <AreaChart
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
        <defs>
          <linearGradient id="area" x1="0" y1="0" x2="0" y2="1">
            <stop
              offset="5%"
              stopColor={config[line].color as string}
              stopOpacity={0.2}
            />
            <stop
              offset="75%"
              stopColor={config[line].color as string}
              stopOpacity={0}
            />
          </linearGradient>
        </defs>
        <Area
          type="linear"
          key={line}
          dataKey={(record) => record.value}
          name={config[line].label as string}
          stroke={config[line].color as string}
          fillOpacity={1}
          fill="url(#area)"
          dot={renderDot}
          activeDot={{ strokeWidth: 2, stroke: "white" }}
          strokeWidth={1.5}
          strokeOpacity={1}
          animationDuration={800}
          connectNulls={false}
        />
      </AreaChart>
    </ChartContainer>
  );
};

export default OptimizationProgressChartContent;
