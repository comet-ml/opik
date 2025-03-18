import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
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
import { Radar, RadarChart, PolarGrid, PolarAngleAxis } from "recharts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExperimentRadarChartLegendContent from "./ExperimentRadarChartLegendContent";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

export type RadarDataPoint = Record<string, string | number>;

interface ExperimentsRadarChartProps {
  name: string;
  chartId: string;
  data: RadarDataPoint[];
  names: string[];
}

const ExperimentsRadarChart: React.FC<ExperimentsRadarChartProps> = ({
  name,
  chartId,
  data,
  names,
}) => {
  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(names);
  }, [names]);

  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const { name } = payload[0].payload;

      return (
        <>
          <div className="comet-body-xs-accented truncate pb-1.5">{name}</div>
        </>
      );
    },
    [],
  );

  const calculateTruncateLength = useCallback((width: number) => {
    const minWidth = 400;
    const minLength = 14;
    const charsPerWidth = 0.35;

    if (width <= minWidth) {
      return minLength;
    }

    const extraWidth = width - minWidth;
    const extraChars = Math.floor(extraWidth * charsPerWidth);

    return minLength + extraChars;
  }, []);

  const renderPolarAngleAxis = useCallback(
    ({
      ...props
    }: React.SVGProps<SVGTextElement> & {
      payload: {
        value: string;
      };
    }) => {
      const truncateLength = calculateTruncateLength(width);
      const truncatedLabel = truncateChartLabel(
        props.payload.value,
        truncateLength,
      );

      return (
        <TooltipWrapper content={props.payload.value}>
          <text {...props} {...DEFAULT_CHART_TICK}>
            {truncatedLabel}
          </text>
        </TooltipWrapper>
      );
    },
    [width, calculateTruncateLength],
  );

  return (
    <Card ref={ref}>
      <CardHeader className="space-y-0.5 px-5 py-4">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
      </CardHeader>
      <CardContent className="px-5 pb-3">
        <ChartContainer
          config={config}
          className="size-full h-[var(--chart-height)]"
        >
          <RadarChart data={data} cy="45%" margin={{ top: 0, bottom: 0 }}>
            <PolarGrid />
            <PolarAngleAxis dataKey="name" tick={renderPolarAngleAxis} dy={3} />
            <ChartTooltip
              isAnimationActive={false}
              content={
                <ChartTooltipContent renderHeader={renderChartTooltipHeader} />
              }
            />
            {names.map((name) => {
              const isActive = name === activeLine;
              let strokeOpacity = 1;

              if (activeLine) {
                strokeOpacity = isActive ? 1 : 0.4;
              }

              return (
                <Radar
                  key={name}
                  name={name}
                  dataKey={name}
                  stroke={config[name].color || ""}
                  fill={config[name].color || ""}
                  fillOpacity={0.05}
                  strokeWidth={1.5}
                  animationDuration={600}
                  strokeOpacity={strokeOpacity}
                />
              );
            })}
            <ChartLegend
              content={
                <ExperimentRadarChartLegendContent
                  setActiveLine={setActiveLine}
                  chartId={chartId}
                />
              }
            />
          </RadarChart>
        </ChartContainer>
      </CardContent>
    </Card>
  );
};

export default ExperimentsRadarChart;
