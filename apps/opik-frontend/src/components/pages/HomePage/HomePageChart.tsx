import React, { useCallback, useMemo } from "react";
import { Area, AreaChart, CartesianGrid, Dot, XAxis, YAxis } from "recharts";
import { LineDot } from "recharts/types/cartesian/Line";

import {
  ChartConfig,
  ChartContainer,
  ChartTooltip,
} from "@/components/ui/chart";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import dayjs from "dayjs";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
  ChartTooltipRenderValueArguments,
} from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import { formatDate } from "@/lib/date";
import { Props as DotProps } from "recharts/types/shape/Dot";
import { ChartData } from "@/components/pages/HomePage/helpers";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";

type MetricOverviewChartProps = {
  chartData: ChartData;
  renderValue?: (data: ChartTooltipRenderValueArguments) => ValueType;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
};

const HomePageChart: React.FC<MetricOverviewChartProps> = ({
  chartData,
  renderValue,
  customYTickFormatter,
}) => {
  const {
    width: tickWidth,
    ticks,
    domain,
    yTickFormatter,
    interval: tickInterval,
  } = useChartTickDefaultConfig(chartData.values, {
    tickFormatter: customYTickFormatter,
    maxTickPrecision: 2,
    targetTickCount: 3,
  });

  const config = useMemo(() => {
    return chartData.projects.reduce<ChartConfig>((acc, project) => {
      acc[project.id] = {
        label: project.name,
        color: TAG_VARIANTS_COLOR_MAP[generateTagVariant(project.name)!],
      };
      return acc;
    }, {});
  }, [chartData.projects]);

  const renderDot: LineDot = useCallback((props: DotProps) => {
    const { key, ...rest } = props;

    return (
      <Dot
        key={key}
        {...rest}
        fill={props.stroke}
        strokeWidth={1.5}
        stroke="white"
      />
    );
  }, []);

  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      return (
        <div className="comet-body-xs mb-1 text-light-slate">
          {formatDate(payload?.[0]?.payload?.date, { utc: true })} UTC
        </div>
      );
    },
    [],
  );

  return (
    <ChartContainer config={config} className="size-full">
      <AreaChart
        data={chartData.data}
        margin={{ top: 10, bottom: 10, left: 10, right: 10 }}
      >
        <CartesianGrid vertical={false} />
        <XAxis
          dataKey="date"
          axisLine={false}
          tickLine={false}
          tick={DEFAULT_CHART_TICK}
          interval={tickInterval}
          tickFormatter={(value) => dayjs(value).utc().format("MM/DD")}
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
          content={
            <ChartTooltipContent
              renderHeader={renderChartTooltipHeader}
              renderValue={renderValue}
            />
          }
        />
        {chartData.projects.map((project) => {
          const line = project.id;
          const lineColor = config[line].color as string;

          return (
            <>
              <defs>
                <linearGradient id={line} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={lineColor} stopOpacity={0.2} />
                  <stop offset="75%" stopColor={lineColor} stopOpacity={0} />
                </linearGradient>
              </defs>
              <Area
                type="linear"
                key={line}
                dataKey={(record) => record.map[line]}
                name={config[line].label as string}
                stroke={lineColor}
                fillOpacity={1}
                fill={`url(#${line})`}
                dot={renderDot}
                activeDot={{ strokeWidth: 2, stroke: "white" }}
                strokeWidth={1.5}
                strokeOpacity={1}
                animationDuration={100}
                connectNulls={false}
              />
            </>
          );
        })}
      </AreaChart>
    </ChartContainer>
  );
};

export default HomePageChart;
