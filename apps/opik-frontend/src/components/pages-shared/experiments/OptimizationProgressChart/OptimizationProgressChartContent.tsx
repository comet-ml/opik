import React, { useState, useMemo, useCallback } from "react";
import useChartConfig from "@/hooks/useChartConfig";
import { Dot, XAxis, CartesianGrid, YAxis, AreaChart, Area } from "recharts";
import { LineDot } from "recharts/types/cartesian/Line";
import debounce from "lodash/debounce";

import { ChartTooltipRenderHeaderArguments } from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import OptimizationProgressTooltip from "./OptimizationProgressTooltip";
import ChartHorizontalLegend from "@/components/shared/Charts/ChartHorizontalLegend/ChartHorizontalLegend";
import {
  ChartContainer,
  ChartTooltip,
  ChartLegend,
} from "@/components/ui/chart";
import {
  DEFAULT_CHART_GRID_PROPS,
  DEFAULT_CHART_TICK,
} from "@/constants/chart";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import {
  extractSecondaryScoreNames,
  getScoreValue,
  generateDistinctColorMap,
} from "./optimizationChartUtils";

export type DataRecord = {
  entityId: string;
  entityName: string;
  createdDate: string;
  value: number | null;
  allFeedbackScores?: { name: string; value: number }[];
};

export type ChartData = {
  data: DataRecord[];
  objectiveName: string;
};

type OptimizationProgressChartContentProps = {
  bestEntityId?: string;
  chartData: ChartData;
};

const OptimizationProgressChartContent: React.FC<
  OptimizationProgressChartContentProps
> = ({ chartData, bestEntityId }) => {
  const { objectiveName, data } = chartData;
  const [activeLine, setActiveLine] = useState<string | null>(null);
  const [position, setPosition] = useState<
    { x: number; y: number } | undefined
  >();

  // Extract all score names (main objective + secondary scores)
  const secondaryScoreNames = useMemo(
    () => extractSecondaryScoreNames(data, objectiveName),
    [data, objectiveName],
  );

  const allScoreNames = useMemo(
    () => [objectiveName, ...secondaryScoreNames],
    [objectiveName, secondaryScoreNames],
  );

  // Generate distinct color map for all scores
  const customColorMap = useMemo(
    () => generateDistinctColorMap(objectiveName, secondaryScoreNames),
    [objectiveName, secondaryScoreNames],
  );

  const config = useChartConfig(allScoreNames, undefined, customColorMap);

  const mainObjectiveColor = config[objectiveName].color as string;

  // Collect all values (main objective + secondary scores) for Y-axis scaling
  const values = useMemo(() => {
    const allValues: DataRecord["value"][] = [];
    data.forEach((record) => {
      // Main objective value
      allValues.push(record.value);
      // Secondary scores values
      secondaryScoreNames.forEach((scoreName) => {
        allValues.push(getScoreValue(record, scoreName));
      });
    });
    return allValues;
  }, [data, secondaryScoreNames]);

  const {
    width: tickWidth,
    ticks,
    domain,
    yTickFormatter,
    interval: tickInterval,
  } = useChartTickDefaultConfig(values, {
    maxTickPrecision: 2,
    targetTickCount: 3,
    showMinMaxDomain: true,
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

    const topCorrection = 12;

    const bgStyles: React.CSSProperties = {
      backgroundColor: `color-mix(in srgb, ${mainObjectiveColor} 10%, white 100%)`,
    };

    return (
      <div
        style={
          {
            "--main-objective-color": mainObjectiveColor,
            top: position.y,
            left: position.x,
            transform: `translate(-50%, calc(-100% - ${topCorrection}px))`,
          } as React.CSSProperties
        }
        className="pointer-events-none absolute z-10"
      >
        <div
          className="comet-body-s rounded px-2 py-1 text-[--main-objective-color]"
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

  // Render dot for main objective (primary score)
  const renderMainDot: LineDot = (props) => {
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

  // Render smaller, less prominent dots for secondary scores
  const renderSecondaryDot: LineDot = (props) => {
    const { key, ...rest } = props;
    const color = config[props.name as string].color;
    const smallRadius = 5; // Slightly larger for better visibility

    return (
      <Dot
        key={key}
        {...rest}
        fill={color}
        strokeWidth={1.2}
        stroke="white"
        r={smallRadius}
      />
    );
  };

  return (
    <div className="relative">
      {renderPopover()}
      <ChartContainer config={config} className="h-40 w-full">
        <AreaChart
          data={chartData.data}
          margin={{ top: 10, bottom: 10, left: 10, right: 10 }}
        >
          <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
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
            content={
              <OptimizationProgressTooltip
                objectiveName={objectiveName}
                renderHeader={renderHeader}
              />
            }
          />
          <ChartLegend
            content={
              <ChartHorizontalLegend
                setActiveLine={setActiveLine}
                chartId="optimization-progress-chart"
              />
            }
          />
          <defs>
            <linearGradient id="area" x1="0" y1="0" x2="0" y2="1">
              <stop
                offset="5%"
                stopColor={mainObjectiveColor}
                stopOpacity={0.2}
              />
              <stop
                offset="75%"
                stopColor={mainObjectiveColor}
                stopOpacity={0}
              />
            </linearGradient>
          </defs>

          {/* Main objective line with prominent styling */}
          <Area
            type="linear"
            key={objectiveName}
            dataKey={(record) => record.value}
            name={config[objectiveName].label as string}
            stroke={mainObjectiveColor}
            fillOpacity={
              activeLine === null || activeLine === objectiveName ? 1 : 0.2
            }
            fill="url(#area)"
            dot={renderMainDot}
            activeDot={{ strokeWidth: 2, stroke: "white" }}
            strokeWidth={2.5}
            strokeOpacity={
              activeLine === null || activeLine === objectiveName ? 1 : 0.2
            }
            animationDuration={100}
            connectNulls={false}
          />

          {/* Secondary score lines with subtle styling */}
          {secondaryScoreNames.map((scoreName) => {
            const scoreColor = config[scoreName].color as string;
            const isHighlighted = activeLine === scoreName;
            const isDimmed = activeLine !== null && activeLine !== scoreName;
            return (
              <Area
                type="linear"
                key={scoreName}
                dataKey={(record) => getScoreValue(record, scoreName)}
                name={config[scoreName].label as string}
                stroke={scoreColor}
                fillOpacity={0}
                fill="transparent"
                dot={renderSecondaryDot}
                activeDot={{ strokeWidth: 1.5, stroke: "white", r: 5 }}
                strokeWidth={isHighlighted ? 1.5 : 1.0}
                strokeOpacity={isDimmed ? 0.15 : isHighlighted ? 0.8 : 0.5}
                animationDuration={100}
                connectNulls={false}
              />
            );
          })}
        </AreaChart>
      </ChartContainer>
    </div>
  );
};

export default OptimizationProgressChartContent;
