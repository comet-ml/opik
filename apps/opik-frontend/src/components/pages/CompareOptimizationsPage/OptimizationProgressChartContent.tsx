import React, { useState, useMemo, useCallback } from "react";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { Dot, XAxis, CartesianGrid, YAxis, AreaChart, Area } from "recharts";
import { LineDot } from "recharts/types/cartesian/Line";
import debounce from "lodash/debounce";

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
  const [position, setPosition] = useState<
    { x: number; y: number } | undefined
  >();

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig([line]);
  }, [line]);

  const lineColor = config[line].color as string;

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

  // There is no way to subscribe to any event when the chart is rendered
  // onAnimationEnd is called before dots are rendered
  // we use this function to update the position of the popover
  const updatePositionDebounced = useMemo(
    () =>
      debounce((val: { x: number; y: number }) => {
        if (val) {
          const { x, y } = val;
          setPosition((state) => {
            if (state?.x !== x || state?.y !== y) {
              return {
                x,
                y,
              };
            }

            return state;
          });
        }
      }, 100),
    [],
  );

  const renderPopover = () => {
    if (!position) return null;

    const leftCorrection = -9;

    const bgStyles: React.CSSProperties = {
      backgroundColor: `color-mix(in srgb, ${lineColor} 10%, white 100%)`,
    };

    return (
      <div
        style={
          {
            "--line-color": lineColor,
            top: position.y,
            left: position.x + leftCorrection,
          } as React.CSSProperties
        }
        className="pointer-events-none absolute z-10"
      >
        <div
          className="comet-body-s rounded px-2 py-1 text-[--line-color]"
          style={bgStyles}
        >
          Best prompt
        </div>
        <div
          className="mx-auto -mt-1.5 size-2.5 rotate-45 rounded-[2px]"
          style={bgStyles}
        ></div>
      </div>
    );
  };

  const renderDot: LineDot = (props) => {
    const { key, ...rest } = props;
    const color = config[props.name as string].color;
    const height = 80;
    const radius = 8;
    if (props.payload.entityId === bestEntityId) {
      updatePositionDebounced({ x: props.cx, y: props.cy });
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
            height={height - props.cy}
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
    <>
      {renderPopover()}
      <ChartContainer config={config} className="h-40 w-full">
        <AreaChart
          data={chartData.data}
          margin={{ top: 10, bottom: 10, left: 10, right: 10 }}
        >
          <CartesianGrid vertical={false} />
          <XAxis
            axisLine={false}
            tickLine={false}
            tick={DEFAULT_CHART_TICK}
            interval={tickInterval}
            tickFormatter={(value) => data[value]?.entityName}
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
              <stop offset="5%" stopColor={lineColor} stopOpacity={0.2} />
              <stop offset="75%" stopColor={lineColor} stopOpacity={0} />
            </linearGradient>
          </defs>
          <Area
            type="linear"
            key={line}
            dataKey={(record) => record.value}
            name={config[line].label as string}
            stroke={lineColor}
            fillOpacity={1}
            fill="url(#area)"
            dot={renderDot}
            activeDot={{ strokeWidth: 2, stroke: "white" }}
            strokeWidth={1.5}
            strokeOpacity={1}
            animationDuration={100}
            connectNulls={false}
          />
        </AreaChart>
      </ChartContainer>
    </>
  );
};

export default OptimizationProgressChartContent;
