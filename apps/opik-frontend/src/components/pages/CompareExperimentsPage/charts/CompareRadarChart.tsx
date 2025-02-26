import React, { useMemo } from "react";
import {
  Radar,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  ResponsiveContainer,
  Tooltip,
} from "recharts";
import { Experiment } from "@/types/datasets";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { ChartContainer } from "@/components/ui/chart";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { getExperimentColorsConfig } from "@/lib/charts";

type RadarDataPoint = {
  name: string;
  [key: string]: string | number;
};

type CompareRadarChartProps = {
  data: RadarDataPoint[];
  experiments: Experiment[];
};

const CompareRadarChart: React.FC<CompareRadarChartProps> = ({
  data,
  experiments,
}) => {
  const config = useMemo(() => {
    return getExperimentColorsConfig(experiments.map((exp) => exp.name));
  }, [experiments]);

  // Calculate domain based on data values
  const domain = useMemo(() => {
    const allValues = data
      .flatMap((d) => experiments.map((exp) => d[exp.name]))
      .filter((v) => v !== undefined);

    const min = Math.min(...allValues);
    const max = Math.max(...allValues);
    const padding = (max - min) * 0.1; // Add 10% padding

    return [Math.max(0, min - padding), max + padding];
  }, [data, experiments]);

  const renderTooltipHeader = ({
    payload,
  }: ChartTooltipRenderHeaderArguments) => {
    if (!payload?.[0]) return null;
    return (
      <div className="comet-body-xs-accented mb-0.5 truncate">
        {payload[0].payload.name}
      </div>
    );
  };

  const RadarChartContent = () => {
    return (
      <ResponsiveContainer width="100%" height="100%">
        <RadarChart
          data={data}
          margin={{ top: 5, bottom: 5, left: 5, right: 0 }}
        >
          <PolarGrid strokeDasharray="3 3" />
          <PolarAngleAxis
            dataKey="name"
            tick={{
              ...DEFAULT_CHART_TICK,
              fontSize: "12px",
            }}
          />
          <PolarRadiusAxis
            domain={domain}
            tickCount={5}
            tick={false}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip
            content={<ChartTooltipContent renderHeader={renderTooltipHeader} />}
            isAnimationActive={false}
          />
          {experiments.map((exp) => {
            const color = config[exp.name].color;
            return (
              <Radar
                key={exp.id}
                name={exp.name}
                dataKey={exp.name}
                stroke={color}
                fill={color}
                fillOpacity={0.3}
                strokeWidth={2}
                strokeOpacity={1}
                activeDot={{ strokeWidth: 1.5, r: 4, stroke: "white" }}
              />
            );
          })}
        </RadarChart>
      </ResponsiveContainer>
    );
  };

  return (
    <ChartContainer config={config} className="size-full">
      <RadarChartContent />
    </ChartContainer>
  );
};

export default CompareRadarChart;
