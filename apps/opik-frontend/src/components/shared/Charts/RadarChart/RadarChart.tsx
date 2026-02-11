import React, { useState, useCallback, useMemo } from "react";
import {
  Radar,
  RadarChart as RechartsRadarChart,
  PolarGrid,
  PolarAngleAxis,
} from "recharts";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";

import {
  ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import ChartHorizontalLegend from "@/components/shared/Charts/ChartHorizontalLegend/ChartHorizontalLegend";
import {
  DEFAULT_CHART_TICK,
  DEFAULT_CHART_GRID_PROPS,
} from "@/constants/chart";
import { calculateChartTruncateLength, truncateChartLabel } from "@/lib/charts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

type ChartDataPoint = Record<string, number | string | null>;

const defaultRenderTooltipValue = ({ value }: { value: ValueType }) => value;

interface RadarChartProps {
  chartId: string;
  config: ChartConfig;
  data: ChartDataPoint[];
  angleAxisKey?: string;
  renderTooltipValue?: (data: { value: ValueType }) => ValueType;
  renderTooltipHeader?: (
    args: ChartTooltipRenderHeaderArguments,
  ) => React.ReactNode;
  showLegend?: boolean;
  legendClassName?: string;
  fillOpacity?: number;
  className?: string;
}

const RadarChart: React.FunctionComponent<RadarChartProps> = ({
  chartId,
  config,
  data,
  angleAxisKey = "name",
  renderTooltipValue = defaultRenderTooltipValue,
  renderTooltipHeader,
  showLegend = true,
  legendClassName = "mt-6 pt-0 flex size-full max-h-[34px] justify-center overflow-auto",
  fillOpacity = 0.05,
  className = "size-full h-[var(--chart-height)]",
}) => {
  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );
  const [activeLine, setActiveLine] = useState<string | null>(null);

  const lines = useMemo(() => Object.keys(config), [config]);

  const defaultTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const name = payload?.[0]?.payload?.[angleAxisKey];
      return (
        <div className="comet-body-xs-accented truncate pb-1.5">{name}</div>
      );
    },
    [angleAxisKey],
  );

  const tooltipHeader = renderTooltipHeader || defaultTooltipHeader;

  const renderPolarAngleAxis = useCallback(
    ({
      ...props
    }: React.SVGProps<SVGTextElement> & {
      payload: {
        value: string;
      };
    }) => {
      const truncateLength = calculateChartTruncateLength({ width });
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
    <div ref={ref} className={className}>
      <ChartContainer config={config} className="size-full">
        <RechartsRadarChart data={data} cy="45%" margin={{ top: 0, bottom: 0 }}>
          <PolarGrid {...DEFAULT_CHART_GRID_PROPS} />
          <PolarAngleAxis
            dataKey={angleAxisKey}
            tick={renderPolarAngleAxis}
            dy={3}
          />
          <ChartTooltip
            isAnimationActive={false}
            content={
              <ChartTooltipContent
                renderHeader={tooltipHeader}
                renderValue={renderTooltipValue}
              />
            }
          />
          {lines.map((line) => {
            const isActive = line === activeLine;
            let strokeOpacity = 1;

            if (activeLine) {
              strokeOpacity = isActive ? 1 : 0.4;
            }

            return (
              <Radar
                key={line}
                name={line}
                dataKey={line}
                stroke={config[line].color || ""}
                fill={config[line].color || ""}
                fillOpacity={fillOpacity}
                strokeWidth={1.5}
                animationDuration={600}
                strokeOpacity={strokeOpacity}
              />
            );
          })}
          {showLegend && (
            <ChartLegend
              content={
                <ChartHorizontalLegend
                  setActiveLine={setActiveLine}
                  chartId={chartId}
                  containerClassName={legendClassName}
                />
              }
            />
          )}
        </RechartsRadarChart>
      </ChartContainer>
    </div>
  );
};

export default RadarChart;
