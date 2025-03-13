import MetricChartLegendContent from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChartLegendContent";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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
import { BarChart, Bar, XAxis, YAxis, CartesianGrid } from "recharts";

export type BarDataPoint = Record<string, string | number>;

interface ExperimentsBarChartProps {
  name: string;
  description: string;
  chartId: string;
  data: BarDataPoint[];
  names: string[];
}

const ExperimentsBarChart: React.FC<ExperimentsBarChartProps> = ({
  name,
  description,
  chartId,
  data,
  names,
}) => {
  const [activeBar, setActiveBar] = useState<string | null>(null);

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

  const renderContent = () => {
    return (
      <ChartContainer
        config={config}
        className="h-[var(--chart-height)] w-full"
      >
        <BarChart
          data={data}
          margin={{
            top: 5,
            right: 5,
            left: 5,
            bottom: 0,
          }}
        >
          <CartesianGrid vertical={false} />

          <XAxis
            dataKey="name"
            axisLine={false}
            tickLine={false}
            dy={10}
            tick={DEFAULT_CHART_TICK}
            tickFormatter={(value) => truncateChartLabel(value)}
          />
          <YAxis tick={DEFAULT_CHART_TICK} axisLine={false} tickLine={false} />
          <ChartLegend
            content={
              <MetricChartLegendContent
                setActiveLine={setActiveBar}
                chartId={chartId}
              />
            }
          />
          <ChartTooltip
            isAnimationActive={false}
            content={
              <ChartTooltipContent renderHeader={renderChartTooltipHeader} />
            }
          />
          {names.map((name) => {
            const isActive = name === activeBar;
            let fillOpacity = 1;

            if (activeBar) {
              fillOpacity = isActive ? 1 : 0.4;
            }

            return (
              <Bar
                key={name}
                name={name}
                dataKey={name}
                fill={config[name].color || ""}
                fillOpacity={fillOpacity}
                stackId="a"
                maxBarSize={52}
              />
            );
          })}
        </BarChart>
      </ChartContainer>
    );
  };

  return (
    <Card>
      <CardHeader className="space-y-0.5 px-5 pb-1.5 pt-4">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
        <CardDescription className="comet-body-xs text-xs">
          {description}
        </CardDescription>
      </CardHeader>
      <CardContent className="px-5 pb-3">{renderContent()}</CardContent>
    </Card>
  );
};

export default ExperimentsBarChart;
