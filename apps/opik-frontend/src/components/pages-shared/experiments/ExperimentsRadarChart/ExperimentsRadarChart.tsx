import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  DEFAULT_CHART_GRID_PROPS,
  DEFAULT_CHART_TICK,
} from "@/constants/chart";
import {
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import {
  calculateChartTruncateLength,
  getDefaultHashedColorsChartConfig,
  truncateChartLabel,
} from "@/lib/charts";
import React, { useCallback, useMemo, useState } from "react";
import { Radar, RadarChart, PolarGrid, PolarAngleAxis } from "recharts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { RadarDataPoint } from "@/types/chart";
import { ExperimentLabelsMap } from "@/components/pages/CompareExperimentsPage/CompareExperimentsDetails/useCompareExperimentsChartsData";
import ChartHorizontalLegendContent from "@/components/shared/ChartHorizontalLegendContent/ChartHorizontalLegendContent";

interface ExperimentsRadarChartProps {
  name: string;
  chartId: string;
  data: RadarDataPoint[];
  keys: string[];
  experimentLabelsMap: ExperimentLabelsMap;
}

const ExperimentsRadarChart: React.FC<ExperimentsRadarChartProps> = ({
  name,
  chartId,
  data,
  keys,
  experimentLabelsMap,
}) => {
  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(keys, experimentLabelsMap);
  }, [keys, experimentLabelsMap]);

  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const { name } = payload[0].payload;

      return (
        <div className="comet-body-xs-accented truncate pb-1.5">{name}</div>
      );
    },
    [],
  );

  const renderPolarAngleAxis = useCallback(
    ({
      ...props
    }: React.SVGProps<SVGTextElement> & {
      payload: {
        value: string;
      };
    }) => {
      const truncateLength = calculateChartTruncateLength({
        width,
      });
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
    [width],
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
            <PolarGrid {...DEFAULT_CHART_GRID_PROPS} />
            <PolarAngleAxis dataKey="name" tick={renderPolarAngleAxis} dy={3} />
            <ChartTooltip
              isAnimationActive={false}
              content={
                <ChartTooltipContent renderHeader={renderChartTooltipHeader} />
              }
            />
            {keys.map((key) => {
              const isActive = experimentLabelsMap[key] === activeLine;
              let strokeOpacity = 1;

              if (activeLine) {
                strokeOpacity = isActive ? 1 : 0.4;
              }

              return (
                <Radar
                  key={key}
                  name={experimentLabelsMap[key]}
                  dataKey={key}
                  stroke={config[key].color || ""}
                  fill={config[key].color || ""}
                  fillOpacity={0.05}
                  strokeWidth={1.5}
                  animationDuration={600}
                  strokeOpacity={strokeOpacity}
                />
              );
            })}
            <ChartLegend
              content={
                <ChartHorizontalLegendContent
                  setActiveLine={setActiveLine}
                  chartId={chartId}
                  containerClassName="mt-6 pt-0 flex size-full max-h-[34px] justify-center overflow-auto"
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
